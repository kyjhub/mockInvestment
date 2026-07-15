package com.papertrade.paper_trading.Client;

import com.papertrade.paper_trading.Config.TossInvestProperties;
import com.papertrade.paper_trading.Dto.OrderBookResponse;
import com.papertrade.paper_trading.Dto.TossOpenApiError;
import com.papertrade.paper_trading.Dto.TossOpenApiErrorResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class TossOrderBookClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final TossInvestProperties properties;
    private final JsonMapper jsonMapper;
    private final HttpClient httpClient;

    public TossOrderBookClient(TossInvestProperties properties, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build();
    }

    public OrderBookResponse getOrderBook(String symbol) {
        validateSecretToken();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(orderBookUri(symbol))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + properties.secretToken())
            .GET()
            .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return handleResponse(response);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to call Toss order book API", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Toss order book API call was interrupted", e);
        }
    }

    private URI orderBookUri(String symbol) {
        String encodedSymbol = URLEncoder.encode(symbol, StandardCharsets.UTF_8);
        return URI.create(properties.baseUrl() + "/api/v1/orderbook?symbol=" + encodedSymbol);
    }

    private OrderBookResponse handleResponse(HttpResponse<String> response) throws JacksonException {
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return jsonMapper.readValue(response.body(), OrderBookResponse.class);
        }

        TossOpenApiError error = parseError(response.body());
        throw new TossOpenApiException(
            response.statusCode(),
            error.requestId(),
            error.code(),
            error.message()
        );
    }

    private TossOpenApiError parseError(String responseBody) {
        try {
            TossOpenApiErrorResponse response = jsonMapper.readValue(responseBody, TossOpenApiErrorResponse.class);
            if (response.error() != null) {
                return response.error();
            }
        } catch (JacksonException ignored) {
        }

        return new TossOpenApiError(null, "toss-openapi-error", "토스증권 API 요청에 실패했습니다.");
    }

    private void validateSecretToken() {
        if (properties.secretToken() == null || properties.secretToken().isBlank()) {
            throw new IllegalStateException("toss-invest.openapi.secret-token is not configured");
        }
    }
}
