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

        List<String> assigned = assignedSymbols();
        for (Map.Entry<Integer, TossOrderBookWebSocketConnection> entry : connections.entrySet()) {
            TossOrderBookWebSocketConnection connection = entry.getValue();
            connection.setDesiredSymbols(symbolsForSlot(assigned, entry.getKey()));
            connection.ensureConnected();
            connection.declareIfChanged();
            connection.pingIfDue();
        }
    }

    /**
     * 슬롯에 배정할 종목. <b>거절 여부로 거르지 않는다.</b>
     *
     * <p>거절 정보는 슬롯을 소유한 인스턴스만 아는데, 그것으로 목록을 먼저 걸러버리면
     * 서버마다 목록 길이가 달라져 인덱스 홀짝 배정이 어긋난다. 그러면 어떤 종목은 두 연결이
     * 중복 구독하고 어떤 종목은 아무도 구독하지 않는다. 그래서 배정은 모든 인스턴스가
     * 동일하게 계산할 수 있는 원본 목록으로 하고, 거절된 종목은 각 연결이 자기 선언에서만 뺀다.
     */
    private List<String> assignedSymbols() {
        long now = System.currentTimeMillis();
        if (now - symbolsComputedAt < SYMBOL_RECOMPUTE_INTERVAL_MILLIS && cachedSymbols != null) {
            return cachedSymbols;
        }
        cachedSymbols = computeAssignedSymbols();
        symbolsComputedAt = now;
        return cachedSymbols;
    }

    /**
     * 전역 활성 종목 중 우선순위 상위 {@code maxSymbols()}개.
     * 이 목록에 들어가면 실시간 푸시를 받고, 밀려나면 REST 폴링으로 처리된다.
     */
    private List<String> computeAssignedSymbols() {
        if (!properties.enabled()) {
            return List.of();
        }

        List<String> ordered = activeSymbolRegistry.orderedActiveSymbols();
        int limit = Math.min(ordered.size(), properties.maxSymbols());
        return List.copyOf(ordered.subList(0, limit));
    }

    /**
     * WebSocket이 실제로 채워줄 종목. 폴링 제외 판단에 쓰인다.
     *
     * <p>주의: 이건 "배정된 종목"이지 "지금 프레임을 받고 있는 종목"이 아니다.
     * 연결이 끊긴 동안에도 covered로 남는 것은 의도한 동작으로, 캐시가 신선도 임계값을 넘기면
     * 폴링이 자동으로 폴백한다. 다만 구독이 거절된 종목은 WebSocket이 채우지 않으므로 제외해야
     * REST 폴링이 가져간다. 거절 정보는 슬롯 소유자만 알기 때문에 Redis로 공유한다.
     */
    public List<String> coveredSymbols() {
        Set<String> rejected = rejectedSymbolRegistry.rejectedSymbols();
        if (rejected.isEmpty()) {
            return assignedSymbols();
        }
        return assignedSymbols().stream()
            .filter(symbol -> !rejected.contains(symbol))
            .toList();
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
     * 인덱스 홀짝으로 슬롯을 나눈다. 해시 샤딩은 한쪽에 몰려 too-many-topics가 날 수 있어서,
     * 각 슬롯이 연결당 구독 한도를 넘지 않음을 보장하는 이 방식을 쓴다.
     */
    // 배정 불변식(슬롯당 한도 준수, 중복 없음, 전량 배정)을 테스트에서 검증하기 위해 package-private.
    List<String> symbolsForSlot(List<String> assigned, int slotIndex) {
        List<String> symbols = new ArrayList<>();
        for (int i = slotIndex; i < assigned.size(); i += properties.connectionSlots()) {
            symbols.add(assigned.get(i));
        }
        int limit = Math.min(symbols.size(), properties.maxSymbolsPerConnection());
        return symbols.subList(0, limit);
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
