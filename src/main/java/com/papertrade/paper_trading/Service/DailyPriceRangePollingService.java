package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.WebSocket.DailyPriceRangeSubscriptionRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DailyPriceRangePollingService {

    private final DailyPriceRangeSubscriptionRegistry subscriptionRegistry;
    private final DailyPriceRangeService dailyPriceRangeService;
    private int rotationOffset;

    @Scheduled(fixedDelayString = "${daily-price-range.polling.fixed-delay-ms:1000}")
    public void pollActiveDailyPriceRanges() {
        List<String> symbols = new ArrayList<>(subscriptionRegistry.activeSymbols());
        Collections.sort(symbols);
        if (!symbols.isEmpty()) {
            Collections.rotate(symbols, -Math.floorMod(rotationOffset++, symbols.size()));
        }
        dailyPriceRangeService.refreshAndPublish(symbols);
    }
}
