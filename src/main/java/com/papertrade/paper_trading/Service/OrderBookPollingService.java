package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Config.OrderBookActiveSymbolProperties;
import com.papertrade.paper_trading.Config.SchedulingConfig;
import com.papertrade.paper_trading.WebSocket.TossOrderBookWebSocketManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * WebSocket이 담당하지 못하는 종목만 REST로 채운다.
 *
 * <p>신선도 게이트는 <b>WebSocket 담당 종목에만</b> 적용한다. 아무도 채워주지 않는 종목까지 게이트에 걸면
 * 1초 주기여야 할 폴링이 신선도 임계값(기본 30초) 주기로 늘어난다.
 */
@Service
@RequiredArgsConstructor
public class OrderBookPollingService {

    private final ActiveOrderBookSymbolRegistry activeSymbolRegistry;
    private final OrderBookService orderBookService;
    private final TossOrderBookWebSocketManager webSocketManager;
    private final OrderBookActiveSymbolProperties properties;
    private int pendingRotationOffset;
    private int idleSubscriptionRotationOffset;

    @Scheduled(
        scheduler = SchedulingConfig.MARKET_DATA_POLLING_SCHEDULER,
        fixedDelayString = "${orderbook.polling.fixed-delay-ms:1000}"
    )
    public void pollPendingOrderSymbols() {
        List<String> symbols = pollTargets(activeSymbolRegistry.pendingOrderSymbols());
        for (String symbol : rotate(symbols, pendingRotationOffset++)) {
            orderBookService.refreshAndPublish(symbol);
        }
    }

    @Scheduled(
        scheduler = SchedulingConfig.MARKET_DATA_POLLING_SCHEDULER,
        fixedDelayString = "${orderbook.polling.idle-fixed-delay-ms:20000}"
    )
    public void pollIdleSubscriptionSymbols() {
        Set<String> pendingSymbols = new HashSet<>(activeSymbolRegistry.pendingOrderSymbols());
        List<String> idleSymbols = activeSymbolRegistry.subscribedSymbols().stream()
            .filter(symbol -> !pendingSymbols.contains(symbol))
            .toList();

        for (String symbol : rotate(pollTargets(idleSymbols), idleSubscriptionRotationOffset++)) {
            orderBookService.refreshAndPublish(symbol);
        }
    }

    /**
     * WebSocket이 채우는 중인 종목만 건너뛴다.
     *
     * <p>담당 종목의 캐시가 신선하면 WebSocket이 살아 있다는 뜻이므로 폴링이 불필요하고,
     * 임계값을 넘기면 자동으로 폴백된다. 임계값이 재연결 시간보다 넉넉해야 짧은 단절에 폴백이 헛돌지 않는다.
     * WebSocket 담당이 아닌 종목은 아무도 채워주지 않으므로 게이트를 적용하지 않는다.
     */
    private List<String> pollTargets(List<String> symbols) {
        Set<String> covered = Set.copyOf(webSocketManager.coveredSymbols());
        Duration threshold = Duration.ofMillis(properties.stalenessThresholdMs());

        return symbols.stream()
            .filter(symbol -> !covered.contains(symbol) || !orderBookService.isFresherThan(symbol, threshold))
            .toList();
    }

    private List<String> rotate(List<String> symbols, int offset) {
        if (symbols.isEmpty()) {
            return symbols;
        }
        List<String> rotated = new ArrayList<>(symbols);
        Collections.rotate(rotated, -Math.floorMod(offset, rotated.size()));
        return rotated;
    }
}
