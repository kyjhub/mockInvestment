package com.papertrade.paper_trading.Client;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.papertrade.paper_trading.Dto.TossOpenApiError;
import com.papertrade.paper_trading.Dto.TossOpenApiErrorResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 토스 OpenAPI GET 호출의 공통 처리.
 * 인증 헤더 부착, 401 재발급 재시도, rate limit 헤더 기록, 오류 변환이 4개 클라이언트에서 동일하므로 한곳에 모았다.
 */
@Component
@RequiredArgsConstructor
public class TossOpenApiRequestExecutor {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final TossAccessTokenProvider accessTokenProvider;
    private final TossApiRateLimiter tossApiRateLimiter;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(REQUEST_TIMEOUT)
        .build();

    /**
     * @param group   {@link TossApiRateLimiter}의 호출량 그룹
     * @param apiName 오류 메시지에 쓰이는 API 이름 (예: "order book")
     */
    public <T> T get(URI uri, String group, Class<T> responseType, String apiName) {
        HttpResponse<String> response = send(uri, group, apiName, accessTokenProvider.accessToken());

        // 토큰이 서버 측에서 조기 폐기된 경우를 위해 재발급 후 1회만 재시도한다.
        if (response.statusCode() == 401) {
            response = send(uri, group, apiName, accessTokenProvider.reissue());
        }

        return handleResponse(response, group, responseType, apiName);
    }

    private HttpResponse<String> send(URI uri, String group, String apiName, String accessToken) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            tossApiRateLimiter.recordResponseHeaders(group, response.headers());
            return response;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to call Toss " + apiName + " API", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Toss " + apiName + " API call was interrupted", e);
        }
    }

    private <T> T handleResponse(
        HttpResponse<String> response,
        String group,
        Class<T> responseType,
        String apiName
    ) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            tossApiRateLimiter.recordSuccessfulResponse(group);
            try {
                return objectMapper.readValue(response.body(), responseType);
            } catch (JacksonException e) {
                throw new IllegalStateException("Failed to parse Toss " + apiName + " API response", e);
            }
        }

        if (response.statusCode() == 429) {
            tossApiRateLimiter.recordRateLimitExceeded(group, response.headers());
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
            TossOpenApiErrorResponse response = objectMapper.readValue(responseBody, TossOpenApiErrorResponse.class);
            if (response.error() != null) {
                return response.error();
            }
        } catch (JacksonException ignored) {
        }

        return new TossOpenApiError(null, "toss-openapi-error", "토스증권 API 요청에 실패했습니다.");
    }
}
