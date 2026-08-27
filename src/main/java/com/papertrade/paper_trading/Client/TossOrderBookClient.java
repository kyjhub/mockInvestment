package com.papertrade.paper_trading.Client;

import com.papertrade.paper_trading.Config.TossInvestProperties;
import com.papertrade.paper_trading.Dto.OrderBookResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TossOrderBookClient {

    private static final String API_NAME = "order book";

    private final TossInvestProperties properties;
    private final TossOpenApiRequestExecutor requestExecutor;

    public OrderBookResponse getOrderBook(String symbol) {
        return requestExecutor.get(
            orderBookUri(symbol),
            TossApiRateLimiter.MARKET_DATA_GROUP,
            OrderBookResponse.class,
            API_NAME
        );
    }

    private URI orderBookUri(String symbol) {
        String encodedSymbol = URLEncoder.encode(symbol, StandardCharsets.UTF_8);
        return URI.create(properties.baseUrl() + "/api/v1/orderbook?symbol=" + encodedSymbol);
    }
}
