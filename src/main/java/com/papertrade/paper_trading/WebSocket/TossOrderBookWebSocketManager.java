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
     * 슬롯이 담당할 종목을 고른다.
     *
     * <p>각 슬롯은 전체 목록에서 <b>자기 인덱스 계열만</b> 훑는다(슬롯 2개면 짝수/홀수).
     * 계열이 서로 겹치지 않으므로 <b>어떤 슬롯이 무엇을 거절로 건너뛰든 다른 슬롯과 중복될 수 없고</b>,
     * 인스턴스마다 거절 정보가 달라도 마찬가지다. 슬롯 간 조율 없이 중복과 누락이 동시에 막힌다.
     *
     * <p>거절된 종목은 건너뛰고 자기 계열에서 다음 종목을 당겨온다. 이렇게 하지 않으면
     * 거절된 종목이 배정 자리만 차지해 슬롯이 한도보다 적게 채워진다.
     *
     * <p>해시 샤딩을 쓰지 않는 이유는 한쪽에 몰려 연결당 구독 한도를 넘길 수 있기 때문이다.
     */
    // 배정 불변식(슬롯당 한도 준수, 중복 없음, 누락 없음)을 테스트에서 검증하기 위해 package-private.
    List<String> symbolsForSlot(List<String> ordered, int slotIndex, Set<String> rejected) {
        List<String> symbols = new ArrayList<>();
        int limit = properties.maxSymbolsPerConnection();
        for (int i = slotIndex; i < ordered.size() && symbols.size() < limit; i += properties.connectionSlots()) {
            String symbol = ordered.get(i);
            if (!rejected.contains(symbol)) {
                symbols.add(symbol);
            }
        }
        return symbols;
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
