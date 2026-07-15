package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Client.TossMarketCalendarClient;
import com.papertrade.paper_trading.Dto.MarketCalendarResponse;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MarketCalendarService {

    private final TossMarketCalendarClient tossMarketCalendarClient;

    public MarketCalendarResponse getUsMarketCalendar(LocalDate date) {
        return tossMarketCalendarClient.getUsMarketCalendar(date);
    }
}
