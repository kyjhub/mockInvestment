package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.WebSocket.PriceSubscriptionRegistry;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PricePollingService {

    private final PriceSubscriptionRegistry subscriptionRegistry;
    private final PriceService priceService;

    @Scheduled(fixedDelayString = "${price.polling.fixed-delay-ms:1000}")
    public void pollActivePrices() {
        priceService.refreshAndPublish(new ArrayList<>(subscriptionRegistry.activeSymbols()));
    }
}
