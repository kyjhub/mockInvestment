package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Client.TossCandleClient;
import com.papertrade.paper_trading.Client.TossApiQuotaUnavailableException;
import com.papertrade.paper_trading.Client.TossApiRateLimiter;
import com.papertrade.paper_trading.Config.DailyPriceRangeCacheProperties;
import com.papertrade.paper_trading.Config.RedisPubSubConfig;
import com.papertrade.paper_trading.Dto.Candle;
import com.papertrade.paper_trading.Dto.CandleResponse;
import com.papertrade.paper_trading.Dto.DailyPriceRangePubSubMessage;
import com.papertrade.paper_trading.Dto.DailyPriceRangeResponse;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class DailyPriceRangeService {

    private static final Pattern SYMBOL_PATTERN = Pattern.compile("^[A-Za-z0-9.\\-]+$");
    private static final String DAILY_PRICE_RANGE_CACHE_KEY_PREFIX = "daily-price-range:";
    private static final String DAILY_PRICE_RANGE_POLL_LOCK_KEY_PREFIX = "daily-price-range:poll-lock:";
    private static final String LOCK_VALUE = "1";

    private final TossCandleClient tossCandleClient;
    private final TossApiRateLimiter tossApiRateLimiter;
    private final StringRedisTemplate stringRedisTemplate;
    private final JsonMapper jsonMapper;
    private final DailyPriceRangeCacheProperties cacheProperties;

    public DailyPriceRangeResponse getDailyPriceRange(String symbol) {
        validateSymbol(symbol);

        DailyPriceRangeResponse cachedResponse = getCachedDailyPriceRange(symbol);
        if (cachedResponse != null) {
            return cachedResponse;
        }

        return fetchCacheAndPublish(symbol);
    }

    public void refreshAndPublish(List<String> symbols) {
        for (String symbol : symbols) {
            validateSymbol(symbol);
            if (tryAcquirePollingLock(symbol)) {
                try {
                    fetchCacheAndPublish(symbol);
                } catch (TossApiQuotaUnavailableException ignored) {
                }
            }
        }
    }

    public DailyPriceRangeResponse updateWithExecutionPrice(
        String symbol,
        DailyPriceRangeResponse currentRange,
        BigDecimal executionPrice
    ) {
        validateSymbol(symbol);
        if (executionPrice == null) {
            return currentRange != null ? currentRange : getDailyPriceRange(symbol);
        }

        DailyPriceRangeResponse baseRange = currentRange != null ? currentRange : getDailyPriceRange(symbol);
        BigDecimal dailyHighPrice = maxPrice(baseRange.dailyHighPrice(), executionPrice);
        BigDecimal dailyLowPrice = minPrice(baseRange.dailyLowPrice(), executionPrice);

        if (samePrice(baseRange.dailyHighPrice(), dailyHighPrice)
            && samePrice(baseRange.dailyLowPrice(), dailyLowPrice)) {
            return baseRange;
        }

        DailyPriceRangeResponse updatedResponse = new DailyPriceRangeResponse(
            baseRange.symbol(),
            baseRange.timestamp(),
            dailyHighPrice,
            dailyLowPrice,
            baseRange.currency()
        );
        cacheDailyPriceRange(updatedResponse);
        publishDailyPriceRange(updatedResponse);
        return updatedResponse;
    }

    private DailyPriceRangeResponse fetchCacheAndPublish(String symbol) {
        if (!tossApiRateLimiter.tryAcquire(TossApiRateLimiter.ORDERBOOK_PRICE_CANDLE_GROUP)) {
            throw new TossApiQuotaUnavailableException(
                TossApiRateLimiter.ORDERBOOK_PRICE_CANDLE_GROUP,
                tossApiRateLimiter.secondsUntilAvailable(TossApiRateLimiter.ORDERBOOK_PRICE_CANDLE_GROUP),
                "일시적으로 일봉 데이터를 가져올 수 없습니다."
            );
        }
        DailyPriceRangeResponse response = toDailyPriceRange(symbol, tossCandleClient.getLatestDailyCandle(symbol));
        cacheDailyPriceRange(response);
        publishDailyPriceRange(response);
        return response;
    }

    private DailyPriceRangeResponse toDailyPriceRange(String symbol, CandleResponse candleResponse) {
        Candle candle = latestCandle(candleResponse);
        return new DailyPriceRangeResponse(
            symbol,
            candle.timestamp(),
            candle.highPrice(),
            candle.lowPrice(),
            candle.currency()
        );
    }

    private Candle latestCandle(CandleResponse candleResponse) {
        if (candleResponse == null
            || candleResponse.result() == null
            || candleResponse.result().candles() == null
            || candleResponse.result().candles().isEmpty()) {
            throw new IllegalArgumentException("일봉 데이터를 찾을 수 없습니다.");
        }
        return candleResponse.result().candles().getFirst();
    }

    private DailyPriceRangeResponse getCachedDailyPriceRange(String symbol) {
        try {
            String cachedValue = stringRedisTemplate.opsForValue().get(cacheKey(symbol));
            if (cachedValue == null || cachedValue.isBlank()) {
                return null;
            }
            return jsonMapper.readValue(cachedValue, DailyPriceRangeResponse.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void cacheDailyPriceRange(DailyPriceRangeResponse response) {
        try {
            stringRedisTemplate.opsForValue().set(
                cacheKey(response.symbol()),
                jsonMapper.writeValueAsString(response),
                Duration.ofSeconds(cacheProperties.ttlSeconds())
            );
        } catch (RuntimeException ignored) {
        }
    }

    private void publishDailyPriceRange(DailyPriceRangeResponse response) {
        try {
            DailyPriceRangePubSubMessage message = new DailyPriceRangePubSubMessage(response.symbol(), response);
            stringRedisTemplate.convertAndSend(
                RedisPubSubConfig.DAILY_PRICE_RANGE_UPDATES_CHANNEL,
                jsonMapper.writeValueAsString(message)
            );
        } catch (RuntimeException ignored) {
        }
    }

    private boolean tryAcquirePollingLock(String symbol) {
        try {
            Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                DAILY_PRICE_RANGE_POLL_LOCK_KEY_PREFIX + symbol,
                LOCK_VALUE,
                Duration.ofMillis(cacheProperties.pollingLockTtlMs())
            );
            return Boolean.TRUE.equals(acquired);
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private void validateSymbol(String symbol) {
        if (symbol == null || !SYMBOL_PATTERN.matcher(symbol).matches()) {
            throw new IllegalArgumentException("symbol 형식이 올바르지 않습니다.");
        }
    }

    private BigDecimal maxPrice(BigDecimal currentPrice, BigDecimal executionPrice) {
        if (currentPrice == null || executionPrice.compareTo(currentPrice) > 0) {
            return executionPrice;
        }
        return currentPrice;
    }

    private BigDecimal minPrice(BigDecimal currentPrice, BigDecimal executionPrice) {
        if (currentPrice == null || executionPrice.compareTo(currentPrice) < 0) {
            return executionPrice;
        }
        return currentPrice;
    }

    private boolean samePrice(BigDecimal firstPrice, BigDecimal secondPrice) {
        if (firstPrice == null || secondPrice == null) {
            return firstPrice == secondPrice;
        }
        return firstPrice.compareTo(secondPrice) == 0;
    }

    private String cacheKey(String symbol) {
        return DAILY_PRICE_RANGE_CACHE_KEY_PREFIX + symbol;
    }
}
