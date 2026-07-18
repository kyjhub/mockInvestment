package com.papertrade.paper_trading.Client;

import com.papertrade.paper_trading.Config.TossInvestProperties;
import com.papertrade.paper_trading.Dto.PriceResponse;
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
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class TossPriceClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final TossInvestProperties properties;
    private final JsonMapper jsonMapper;
    private final TossApiRateLimiter tossApiRateLimiter;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(REQUEST_TIMEOUT)
        .build();

    public PriceResponse getPrices(List<String> symbols) {
        validateSecretToken();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(pricesUri(symbols))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + properties.secretToken())
            .GET()
            .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            tossApiRateLimiter.recordResponseHeaders(
                TossApiRateLimiter.ORDERBOOK_PRICE_CANDLE_GROUP,
                response.headers()
            );
            return handleResponse(response);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to call Toss prices API", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Toss prices API call was interrupted", e);
        }
    }

    private URI pricesUri(List<String> symbols) {
        String joinedSymbols = String.join(",", symbols);
        String encodedSymbols = URLEncoder.encode(joinedSymbols, StandardCharsets.UTF_8);
        return URI.create(properties.baseUrl() + "/api/v1/prices?symbols=" + encodedSymbols);
    }

    private PriceResponse handleResponse(HttpResponse<String> response) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            tossApiRateLimiter.recordSuccessfulResponse(TossApiRateLimiter.ORDERBOOK_PRICE_CANDLE_GROUP);
            try {
                return jsonMapper.readValue(response.body(), PriceResponse.class);
            } catch (JacksonException e) {
                throw new IllegalStateException("Failed to parse Toss prices API response", e);
            }
        }

        if (response.statusCode() == 429) {
            tossApiRateLimiter.recordRateLimitExceeded(
                TossApiRateLimiter.ORDERBOOK_PRICE_CANDLE_GROUP,
                response.headers()
            );
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
