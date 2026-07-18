package com.papertrade.paper_trading.Client;

import com.papertrade.paper_trading.Config.TossInvestProperties;
import com.papertrade.paper_trading.Dto.MarketCalendarResponse;
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
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class TossMarketCalendarClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final TossInvestProperties properties;
    private final JsonMapper jsonMapper;
    private final TossApiRateLimiter tossApiRateLimiter;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(REQUEST_TIMEOUT)
        .build();

    public MarketCalendarResponse getUsMarketCalendar(LocalDate date) {
        validateSecretToken();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(marketCalendarUri(date))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + properties.secretToken())
            .GET()
            .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            tossApiRateLimiter.recordResponseHeaders(
                TossApiRateLimiter.MARKET_CALENDAR_EXCHANGE_RATE_GROUP,
                response.headers()
            );
            return handleResponse(response);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to call Toss market calendar API", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Toss market calendar API call was interrupted", e);
        }
    }

    private URI marketCalendarUri(LocalDate date) {
        String uri = properties.baseUrl() + "/api/v1/market-calendar/US";
        if (date == null) {
            return URI.create(uri);
        }

        String encodedDate = URLEncoder.encode(date.toString(), StandardCharsets.UTF_8);
        return URI.create(uri + "?date=" + encodedDate);
    }

    private MarketCalendarResponse handleResponse(HttpResponse<String> response) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            tossApiRateLimiter.recordSuccessfulResponse(
                TossApiRateLimiter.MARKET_CALENDAR_EXCHANGE_RATE_GROUP
            );
            try {
                return jsonMapper.readValue(response.body(), MarketCalendarResponse.class);
            } catch (JacksonException e) {
                throw new IllegalStateException("Failed to parse Toss market calendar API response", e);
            }
        }

        if (response.statusCode() == 429) {
            tossApiRateLimiter.recordRateLimitExceeded(
                TossApiRateLimiter.MARKET_CALENDAR_EXCHANGE_RATE_GROUP,
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
