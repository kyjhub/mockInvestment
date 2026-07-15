package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.WebSocket.OrderBookSubscriptionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderBookPollingService {

    private final OrderBookSubscriptionRegistry subscriptionRegistry;
    private final OrderBookService orderBookService;

    @Scheduled(fixedDelayString = "${orderbook.polling.fixed-delay-ms:1000}")
    public void pollActiveOrderBooks() {
        for (String symbol : subscriptionRegistry.activeSymbols()) {
            orderBookService.refreshAndPublish(symbol);
        }
    }
}
