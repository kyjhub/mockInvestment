package com.papertrade.paper_trading.Client;

import com.papertrade.paper_trading.Config.TossInvestProperties;
import com.papertrade.paper_trading.Dto.PriceResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TossPriceClient {

    private static final String API_NAME = "prices";

    private final TossInvestProperties properties;
    private final TossOpenApiRequestExecutor requestExecutor;

    public PriceResponse getPrices(List<String> symbols) {
        return requestExecutor.get(
            pricesUri(symbols),
            TossApiRateLimiter.MARKET_DATA_GROUP,
            PriceResponse.class,
            API_NAME
        );
    }

    private URI pricesUri(List<String> symbols) {
        String joinedSymbols = String.join(",", symbols);
        String encodedSymbols = URLEncoder.encode(joinedSymbols, StandardCharsets.UTF_8);
        return URI.create(properties.baseUrl() + "/api/v1/prices?symbols=" + encodedSymbols);
    }
}
