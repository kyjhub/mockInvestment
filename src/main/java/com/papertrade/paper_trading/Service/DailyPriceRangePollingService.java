package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.WebSocket.DailyPriceRangeSubscriptionRegistry;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DailyPriceRangePollingService {

    private final DailyPriceRangeSubscriptionRegistry subscriptionRegistry;
    private final DailyPriceRangeService dailyPriceRangeService;

    @Scheduled(fixedDelayString = "${daily-price-range.polling.fixed-delay-ms:1000}")
    public void pollActiveDailyPriceRanges() {
        dailyPriceRangeService.refreshAndPublish(new ArrayList<>(subscriptionRegistry.activeSymbols()));
    }
}
