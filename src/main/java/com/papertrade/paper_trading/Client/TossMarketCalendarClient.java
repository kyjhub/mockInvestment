package com.papertrade.paper_trading.Client;

import com.papertrade.paper_trading.Config.TossInvestProperties;
import com.papertrade.paper_trading.Dto.MarketCalendarResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TossMarketCalendarClient {

    private static final String API_NAME = "market calendar";

    private final TossInvestProperties properties;
    private final TossOpenApiRequestExecutor requestExecutor;

    public MarketCalendarResponse getUsMarketCalendar(LocalDate date) {
        return requestExecutor.get(
            marketCalendarUri(date),
            TossApiRateLimiter.MARKET_INFO_GROUP,
            MarketCalendarResponse.class,
            API_NAME
        );
    }

    private URI marketCalendarUri(LocalDate date) {
        String uri = properties.baseUrl() + "/api/v1/market-calendar/US";
        if (date == null) {
            return URI.create(uri);
        }

        String encodedDate = URLEncoder.encode(date.toString(), StandardCharsets.UTF_8);
        return URI.create(uri + "?date=" + encodedDate);
    }
}
