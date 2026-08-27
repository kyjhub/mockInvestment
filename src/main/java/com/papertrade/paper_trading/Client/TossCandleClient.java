package com.papertrade.paper_trading.Client;

import com.papertrade.paper_trading.Config.TossInvestProperties;
import com.papertrade.paper_trading.Dto.CandleResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TossCandleClient {

    private static final String API_NAME = "candles";

    private final TossInvestProperties properties;
    private final TossOpenApiRequestExecutor requestExecutor;

    public CandleResponse getLatestDailyCandle(String symbol) {
        return requestExecutor.get(
            latestDailyCandleUri(symbol),
            TossApiRateLimiter.MARKET_DATA_CHART_GROUP,
            CandleResponse.class,
            API_NAME
        );
    }

    private URI latestDailyCandleUri(String symbol) {
        String encodedSymbol = URLEncoder.encode(symbol, StandardCharsets.UTF_8);
        return URI.create(properties.baseUrl()
            + "/api/v1/candles?symbol=" + encodedSymbol
            + "&interval=1d&count=1&adjusted=true");
    }
}
