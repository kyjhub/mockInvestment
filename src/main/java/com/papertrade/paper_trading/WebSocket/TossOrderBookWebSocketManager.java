package com.papertrade.paper_trading.WebSocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.papertrade.paper_trading.Client.TossAccessTokenProvider;
import com.papertrade.paper_trading.Config.SchedulingConfig;
import com.papertrade.paper_trading.Config.TossWebSocketProperties;
import com.papertrade.paper_trading.Service.ActiveOrderBookSymbolRegistry;
import com.papertrade.paper_trading.Service.OrderBookService;
import jakarta.annotation.PreDestroy;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 슬롯 점유와 종목 배정을 관리한다. 실제 연결/수신은 {@link TossOrderBookWebSocketConnection}이 담당한다.
 * 모든 상태 전이가 스케줄 틱에서 일어나므로 별도 스레드 관리가 없다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TossOrderBookWebSocketManager {

    private final TossWebSocketProperties properties;
    private final TossWebSocketSlotLock slotLock;
    private final TossAccessTokenProvider accessTokenProvider;
    private final ActiveOrderBookSymbolRegistry activeSymbolRegistry;
    private final OrderBookService orderBookService;
    private final RejectedWebSocketSymbolRegistry rejectedSymbolRegistry;
    private final ObjectMapper objectMapper;

    private static final long SYMBOL_RECOMPUTE_INTERVAL_MILLIS = 1_000L;

    private final Map<Integer, TossOrderBookWebSocketConnection> connections = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private volatile List<String> cachedSymbols;
    private volatile long symbolsComputedAt;

    /** 슬롯을 잡거나 유지한다. 한 인스턴스가 두 슬롯을 다 잡아도 된다 — 인스턴스가 하나뿐일 때 필요하다. */
    @Scheduled(
        scheduler = SchedulingConfig.WEBSOCKET_SCHEDULER,
        fixedDelayString = "${toss-invest.websocket.slot-heartbeat-ms:5000}"
    )
    public void manageSlots() {
        if (!properties.enabled()) {
            releaseAll();
            return;
        }

        for (int slotIndex = 0; slotIndex < properties.connectionSlots(); slotIndex++) {
            if (connections.containsKey(slotIndex)) {
                if (!slotLock.renew(slotIndex)) {
                    log.warn("Toss WebSocket slot lost. slot={}", slotIndex);
                    closeSlot(slotIndex);
                }
                continue;
            }

            if (slotLock.acquire(slotIndex)) {
                log.info("Toss WebSocket slot acquired. slot={}", slotIndex);
                connections.put(slotIndex, newConnection(slotIndex));
            }
        }
    }

    /** 담당 종목 재계산 + 변경 시 재선언. 이 주기가 곧 선언 디바운스다. */
    @Scheduled(
        scheduler = SchedulingConfig.WEBSOCKET_SCHEDULER,
        fixedDelayString = "${toss-invest.websocket.declare-debounce-ms:250}"
    )
    public void refreshSubscriptions() {
        if (!properties.enabled() || connections.isEmpty()) {
            return;
        }

        List<String> ordered = orderedActiveSymbols();
        Set<String> rejected = rejectedSymbolRegistry.rejectedSymbols();
        for (Map.Entry<Integer, TossOrderBookWebSocketConnection> entry : connections.entrySet()) {
            TossOrderBookWebSocketConnection connection = entry.getValue();
            connection.setDesiredSymbols(symbolsForSlot(ordered, entry.getKey(), rejected));
            connection.ensureConnected();
            connection.declareIfChanged();
            connection.pingIfDue();
        }
    }

    /** 우선순위 정렬된 전역 활성 종목 전체. DB 조회를 동반하므로 캐싱한다. */
    private List<String> orderedActiveSymbols() {
        long now = System.currentTimeMillis();
        if (now - symbolsComputedAt < SYMBOL_RECOMPUTE_INTERVAL_MILLIS && cachedSymbols != null) {
            return cachedSymbols;
        }
        cachedSymbols = properties.enabled() ? activeSymbolRegistry.orderedActiveSymbols() : List.of();
        symbolsComputedAt = now;
        return cachedSymbols;
    }

    /**
     * WebSocket이 실제로 채워줄 종목. 폴링 제외 판단에 쓰인다.
     *
     * <p>주의: 이건 "구독하기로 한 종목"이지 "지금 프레임을 받고 있는 종목"이 아니다.
     * 연결이 끊긴 동안에도 covered로 남는 것은 의도한 동작으로, 캐시가 신선도 임계값을 넘기면
     * 폴링이 자동으로 폴백한다.
     */
    public List<String> coveredSymbols() {
        List<String> ordered = orderedActiveSymbols();
        Set<String> rejected = rejectedSymbolRegistry.rejectedSymbols();
        Set<String> covered = new LinkedHashSet<>();
        for (int slotIndex = 0; slotIndex < properties.connectionSlots(); slotIndex++) {
            covered.addAll(symbolsForSlot(ordered, slotIndex, rejected));
        }
        return List.copyOf(covered);
    }

    /** 실제로 이 인스턴스가 구독을 선언해 둔 종목. 폴링 제외 판단이 아니라 관측용이다. */
    public Set<String> declaredSymbols() {
        Set<String> declared = new HashSet<>();
        for (TossOrderBookWebSocketConnection connection : connections.values()) {
            declared.addAll(connection.declaredSymbols());
        }
        return declared;
    }

    /**
     * 슬롯이 담당할 종목을 고른다. 소유권은 <b>목록에서의 위치가 아니라 종목 이름</b>이 정한다.
     *
     * <p>위치 기반(인덱스 홀짝)으로 나누면 모든 인스턴스의 목록이 완전히 같아야만 성립한다.
     * 실제로는 구독·주문이 추가되는 시점 차이나 Redis 장애로 목록이 갈릴 수 있고, 그러면
     * 같은 위치가 서로 다른 종목을 가리켜 중복 구독과 누락이 동시에 발생한다.
     *
     * <pre>
     * 서버1 slot0, 목록 [B,C,D]   -> 위치 0,2 -> B, D
     * 서버2 slot1, 목록 [A,B,C,D] -> 위치 1,3 -> B, D
     * 결과: B·D 중복, A·C 누락
     * </pre>
     *
     * <p>종목 이름으로 소유 슬롯을 정하면 목록이 달라도 같은 종목은 언제나 같은 슬롯에 속하므로
     * <b>중복 구독이 구조적으로 불가능</b>하다. {@code String.hashCode()}는 명세로 고정된 값이라
     * JVM이나 인스턴스가 달라도 동일하다.
     *
     * <p>한 슬롯에 몰려 연결당 한도를 넘으면 우선순위 상위부터 자르고 나머지는 REST 폴링이 가져간다.
     * 한도를 넘기면 토스가 선언 전체를 {@code too-many-topics}로 거부하기 때문에 자르는 쪽이 안전하다.
     */
    // 배정 불변식(슬롯당 한도 준수, 중복 없음, 목록이 갈려도 중복 없음)을 테스트에서 검증하기 위해 package-private.
    List<String> symbolsForSlot(List<String> ordered, int slotIndex, Set<String> rejected) {
        List<String> symbols = new ArrayList<>();
        int limit = properties.maxSymbolsPerConnection();
        for (String symbol : ordered) {
            if (symbols.size() >= limit) {
                break;
            }
            if (ownerSlot(symbol) == slotIndex && !rejected.contains(symbol)) {
                symbols.add(symbol);
            }
        }
        return symbols;
    }

    private int ownerSlot(String symbol) {
        return Math.floorMod(symbol.hashCode(), properties.connectionSlots());
    }

    private TossOrderBookWebSocketConnection newConnection(int slotIndex) {
        return new TossOrderBookWebSocketConnection(
            slotIndex,
            properties,
            accessTokenProvider,
            orderBookService,
            rejectedSymbolRegistry,
            objectMapper,
            httpClient
        );
    }

    private void closeSlot(int slotIndex) {
        TossOrderBookWebSocketConnection connection = connections.remove(slotIndex);
        if (connection != null) {
            connection.close();
        }
    }

    private void releaseAll() {
        for (Integer slotIndex : List.copyOf(connections.keySet())) {
            closeSlot(slotIndex);
            slotLock.release(slotIndex);
        }
    }

    @PreDestroy
    public void shutdown() {
        releaseAll();
    }
}
