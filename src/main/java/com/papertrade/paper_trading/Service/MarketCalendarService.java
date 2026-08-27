package com.papertrade.paper_trading.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.papertrade.paper_trading.Client.TossMarketCalendarClient;
import com.papertrade.paper_trading.Client.TossApiQuotaUnavailableException;
import com.papertrade.paper_trading.Client.TossApiRateLimiter;
import com.papertrade.paper_trading.Config.MarketCalendarCacheProperties;
import com.papertrade.paper_trading.Dto.MarketCalendarResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MarketCalendarService {

    private static final String US_MARKET_CALENDAR_CACHE_KEY_PREFIX = "market-calendar:US:";
    private static final ZoneId MARKET_CALENDAR_ZONE = ZoneId.of("Asia/Seoul");

    private final TossMarketCalendarClient tossMarketCalendarClient;
    private final TossApiRateLimiter tossApiRateLimiter;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final MarketCalendarCacheProperties cacheProperties;

    public MarketCalendarResponse getUsMarketCalendar(LocalDate date) {
        LocalDate cacheDate = date != null ? date : LocalDate.now(MARKET_CALENDAR_ZONE);
        String cacheKey = US_MARKET_CALENDAR_CACHE_KEY_PREFIX + cacheDate;

        MarketCalendarResponse cachedResponse = getCachedMarketCalendar(cacheKey);
        if (cachedResponse != null) {
            return cachedResponse;
        }

        if (!tossApiRateLimiter.tryAcquire(TossApiRateLimiter.MARKET_INFO_GROUP)) {
            throw new TossApiQuotaUnavailableException(
                TossApiRateLimiter.MARKET_INFO_GROUP,
                tossApiRateLimiter.secondsUntilAvailable(TossApiRateLimiter.MARKET_INFO_GROUP),
                "일시적으로 장 운영정보를 가져올 수 없습니다."
            );
        }

        MarketCalendarResponse response = tossMarketCalendarClient.getUsMarketCalendar(cacheDate);
        cacheMarketCalendar(cacheKey, response);
        return response;
    }

    private MarketCalendarResponse getCachedMarketCalendar(String cacheKey) {
        try {
            String cachedValue = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cachedValue == null || cachedValue.isBlank()) {
                return null;
            }
            return objectMapper.readValue(cachedValue, MarketCalendarResponse.class);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private void cacheMarketCalendar(String cacheKey, MarketCalendarResponse response) {
        try {
            String value = objectMapper.writeValueAsString(response);
            stringRedisTemplate.opsForValue().set(
                cacheKey,
                value,
                Duration.ofHours(cacheProperties.ttlHours())
            );
        } catch (IOException | RuntimeException ignored) {
        }
    }
}
