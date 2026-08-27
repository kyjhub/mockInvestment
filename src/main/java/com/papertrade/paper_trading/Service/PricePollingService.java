package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Config.SchedulingConfig;
import com.papertrade.paper_trading.WebSocket.PriceSubscriptionRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PricePollingService {

    private final PriceSubscriptionRegistry subscriptionRegistry;
    private final PriceService priceService;
    private int rotationOffset;

    @Scheduled(scheduler = SchedulingConfig.MARKET_DATA_POLLING_SCHEDULER, fixedDelayString = "${price.polling.fixed-delay-ms:1000}")
    public void pollActivePrices() {
        List<String> symbols = new ArrayList<>(subscriptionRegistry.activeSymbols());
        Collections.sort(symbols);
        if (!symbols.isEmpty()) {
            Collections.rotate(symbols, -Math.floorMod(rotationOffset++, symbols.size()));
        }
        priceService.refreshAndPublish(symbols);
    }
}
