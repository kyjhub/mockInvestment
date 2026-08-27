package com.papertrade.paper_trading.WebSocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.papertrade.paper_trading.Client.TossAccessTokenProvider;
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
    private final ObjectMapper objectMapper;

    private static final long SYMBOL_RECOMPUTE_INTERVAL_MILLIS = 1_000L;

    private final Map<Integer, TossOrderBookWebSocketConnection> connections = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private volatile List<String> cachedSymbols;
    private volatile long symbolsComputedAt;

    /** 슬롯을 잡거나 유지한다. 한 인스턴스가 두 슬롯을 다 잡아도 된다 — 인스턴스가 하나뿐일 때 필요하다. */
    @Scheduled(fixedDelayString = "${toss-invest.websocket.slot-heartbeat-ms:5000}")
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
    @Scheduled(fixedDelayString = "${toss-invest.websocket.declare-debounce-ms:250}")
    public void refreshSubscriptions() {
        if (!properties.enabled() || connections.isEmpty()) {
            return;
        }

        List<String> assigned = cachedWebSocketSymbols();
        for (Map.Entry<Integer, TossOrderBookWebSocketConnection> entry : connections.entrySet()) {
            TossOrderBookWebSocketConnection connection = entry.getValue();
            connection.setDesiredSymbols(symbolsForSlot(assigned, entry.getKey()));
            connection.ensureConnected();
            connection.declareIfChanged();
            connection.pingIfDue();
        }
    }

    /**
     * 선언 틱은 250ms마다 돌지만 활성 종목 재계산은 DB 조회를 동반하므로 그 주기로 반복하면 낭비다.
     * 구독 변화가 반영되기까지 최대 1초 늦어지는 대신 DB 부하를 1/4로 낮춘다.
     */
    private List<String> cachedWebSocketSymbols() {
        long now = System.currentTimeMillis();
        if (now - symbolsComputedAt < SYMBOL_RECOMPUTE_INTERVAL_MILLIS && cachedSymbols != null) {
            return cachedSymbols;
        }
        cachedSymbols = webSocketSymbols();
        symbolsComputedAt = now;
        return cachedSymbols;
    }

    /**
     * WebSocket이 담당하는 종목. 전역 활성 종목 중 우선순위 상위 {@code maxSymbols()}개다.
     * 이 목록에 들어가면 실시간 푸시를 받고, 밀려나면 REST 폴링으로 처리된다.
     */
    public List<String> webSocketSymbols() {
        if (!properties.enabled()) {
            return List.of();
        }
        List<String> ordered = activeSymbolRegistry.orderedActiveSymbols();
        int limit = Math.min(ordered.size(), properties.maxSymbols());
        return ordered.subList(0, limit);
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
