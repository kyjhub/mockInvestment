package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Config.OrderBookActiveSymbolProperties;
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
 * 제외 기준은 "WebSocket 담당 종목인가"가 아니라 "호가 캐시가 신선한가"다 —
 * 그래야 WebSocket이 멎으면 자동으로 폴백되고 푸시가 재개되면 자동으로 빠진다.
 */
@Service
@RequiredArgsConstructor
public class OrderBookPollingService {

    private final ActiveOrderBookSymbolRegistry activeSymbolRegistry;
    private final OrderBookService orderBookService;
    private final OrderBookActiveSymbolProperties properties;
    private int pendingRotationOffset;
    private int idleSubscriptionRotationOffset;

    @Scheduled(fixedDelayString = "${orderbook.polling.fixed-delay-ms:1000}")
    public void pollPendingOrderSymbols() {
        List<String> symbols = staleOnly(activeSymbolRegistry.pendingOrderSymbols());
        for (String symbol : rotate(symbols, pendingRotationOffset++)) {
            orderBookService.refreshAndPublish(symbol);
        }
    }

    @Scheduled(fixedDelayString = "${orderbook.polling.idle-fixed-delay-ms:20000}")
    public void pollIdleSubscriptionSymbols() {
        Set<String> pendingSymbols = new HashSet<>(activeSymbolRegistry.pendingOrderSymbols());
        List<String> idleSymbols = activeSymbolRegistry.subscribedSymbols().stream()
            .filter(symbol -> !pendingSymbols.contains(symbol))
            .toList();

        for (String symbol : rotate(staleOnly(idleSymbols), idleSubscriptionRotationOffset++)) {
            orderBookService.refreshAndPublish(symbol);
        }
    }

    /**
     * 캐시가 신선한 종목은 누군가(WebSocket이든 직전 폴링이든) 이미 채우고 있으므로 건너뛴다.
     * 임계값이 WebSocket 재연결 시간보다 넉넉해야 짧은 단절에 폴백이 헛돌지 않는다.
     */
    private List<String> staleOnly(List<String> symbols) {
        Duration threshold = Duration.ofMillis(properties.stalenessThresholdMs());
        return symbols.stream()
            .filter(symbol -> !orderBookService.isFresherThan(symbol, threshold))
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
