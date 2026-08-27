package com.papertrade.paper_trading.Client;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.papertrade.paper_trading.Config.TossInvestProperties;
import com.papertrade.paper_trading.Dto.TossAccessTokenResponse;
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
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 토스 OpenAPI는 OAuth2 client credentials로 발급받은 access token을 Bearer로 요구한다.
 * 토큰 수명이 24시간이라 호출마다 발급하지 않고 Redis에 캐싱해 인스턴스 간에 공유한다.
 */
@Component
@RequiredArgsConstructor
public class TossAccessTokenProvider {

    private static final String ACCESS_TOKEN_KEY = "toss-api:access-token";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    /** 만료 직전에 걸쳐 401이 나지 않도록 실제 만료보다 앞당겨 캐시를 버린다. */
    private static final Duration EXPIRY_MARGIN = Duration.ofMinutes(10);
    private static final Duration MIN_CACHE_TTL = Duration.ofMinutes(1);

    private final TossInvestProperties properties;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(REQUEST_TIMEOUT)
        .build();

    public String accessToken() {
        String cachedToken = cachedToken();
        if (cachedToken != null) {
            return cachedToken;
        }
        return issueAndCache();
    }

    /** 401을 받은 호출자가 캐시를 버리고 새 토큰을 받을 때 사용한다. */
    public String reissue() {
        try {
            stringRedisTemplate.delete(ACCESS_TOKEN_KEY);
        } catch (RuntimeException ignored) {
        }
        return issueAndCache();
    }

    private String cachedToken() {
        try {
            String value = stringRedisTemplate.opsForValue().get(ACCESS_TOKEN_KEY);
            return value == null || value.isBlank() ? null : value;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private synchronized String issueAndCache() {
        // 대기하는 동안 다른 스레드가 이미 발급했을 수 있다.
        String cachedToken = cachedToken();
        if (cachedToken != null) {
            return cachedToken;
        }

        validateCredentials();
        TossAccessTokenResponse token = requestToken();
        cacheToken(token);
        return token.accessToken();
    }

    private TossAccessTokenResponse requestToken() {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(properties.baseUrl() + "/oauth2/token"))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(tokenRequestBody()))
            .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to call Toss OAuth2 token API", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Toss OAuth2 token API call was interrupted", e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            TossOpenApiError error = parseError(response.body());
            throw new TossOpenApiException(
                response.statusCode(),
                error.requestId(),
                error.code(),
                error.message()
            );
        }

        try {
            TossAccessTokenResponse token = objectMapper.readValue(
                response.body(),
                TossAccessTokenResponse.class
            );
            if (token.accessToken() == null || token.accessToken().isBlank()) {
                throw new IllegalStateException("Toss OAuth2 token response has no access_token");
            }
            return token;
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to parse Toss OAuth2 token API response", e);
        }
    }

    private String tokenRequestBody() {
        return "grant_type=client_credentials"
            + "&client_id=" + URLEncoder.encode(properties.clientId(), StandardCharsets.UTF_8)
            + "&client_secret=" + URLEncoder.encode(properties.secretToken(), StandardCharsets.UTF_8);
    }

    private void cacheToken(TossAccessTokenResponse token) {
        try {
            stringRedisTemplate.opsForValue().set(ACCESS_TOKEN_KEY, token.accessToken(), cacheTtl(token));
        } catch (RuntimeException ignored) {
            // Redis가 죽어도 이번 호출은 방금 받은 토큰으로 진행한다.
        }
    }

    private Duration cacheTtl(TossAccessTokenResponse token) {
        Duration ttl = Duration.ofSeconds(token.expiresIn()).minus(EXPIRY_MARGIN);
        return ttl.compareTo(MIN_CACHE_TTL) < 0 ? MIN_CACHE_TTL : ttl;
    }

    private TossOpenApiError parseError(String responseBody) {
        try {
            TossOpenApiErrorResponse response = objectMapper.readValue(responseBody, TossOpenApiErrorResponse.class);
            if (response.error() != null) {
                return response.error();
            }
        } catch (JacksonException ignored) {
        }

        return new TossOpenApiError(null, "toss-oauth2-error", "토스증권 API 토큰 발급에 실패했습니다.");
    }

    private void validateCredentials() {
        if (properties.clientId() == null || properties.clientId().isBlank()) {
            throw new IllegalStateException("toss-invest.openapi.client-id is not configured");
        }
        if (properties.secretToken() == null || properties.secretToken().isBlank()) {
            throw new IllegalStateException("toss-invest.openapi.secret-token is not configured");
        }
    }
}
