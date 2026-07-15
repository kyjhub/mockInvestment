package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Client.TossOrderBookClient;
import com.papertrade.paper_trading.Config.OrderBookCacheProperties;
import com.papertrade.paper_trading.Config.RedisPubSubConfig;
import com.papertrade.paper_trading.Dto.OrderBookPubSubMessage;
import com.papertrade.paper_trading.Dto.OrderBookResponse;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class OrderBookService {

    private static final String ORDER_BOOK_CACHE_KEY_PREFIX = "orderbook:";
    private static final String ORDER_BOOK_POLL_LOCK_KEY_PREFIX = "orderbook:poll-lock:";
    private static final String LOCK_VALUE = "1";

    private final TossOrderBookClient tossOrderBookClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final JsonMapper jsonMapper;
    private final OrderBookCacheProperties cacheProperties;

    public OrderBookResponse getOrderBook(String symbol) {
        OrderBookResponse cachedResponse = getCachedOrderBook(symbol);
        if (cachedResponse != null) {
            return cachedResponse;
        }

        return fetchCacheAndPublish(symbol);
    }

    public void refreshAndPublish(String symbol) {
        if (!tryAcquirePollingLock(symbol)) {
            return;
        }

        fetchCacheAndPublish(symbol);
    }

    private OrderBookResponse fetchCacheAndPublish(String symbol) {
        OrderBookResponse response = tossOrderBookClient.getOrderBook(symbol);
        cacheOrderBook(symbol, response);
        publishOrderBook(symbol, response);
        return response;
    }

    private OrderBookResponse getCachedOrderBook(String symbol) {
        try {
            String cachedValue = stringRedisTemplate.opsForValue().get(cacheKey(symbol));
            if (cachedValue == null || cachedValue.isBlank()) {
                return null;
            }
            return jsonMapper.readValue(cachedValue, OrderBookResponse.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void cacheOrderBook(String symbol, OrderBookResponse response) {
        try {
            stringRedisTemplate.opsForValue().set(
                cacheKey(symbol),
                jsonMapper.writeValueAsString(response),
                Duration.ofSeconds(cacheProperties.ttlSeconds())
            );
        } catch (RuntimeException ignored) {
        }
    }

    private void publishOrderBook(String symbol, OrderBookResponse response) {
        try {
            OrderBookPubSubMessage message = new OrderBookPubSubMessage(symbol, response);
            stringRedisTemplate.convertAndSend(
                RedisPubSubConfig.ORDER_BOOK_UPDATES_CHANNEL,
                jsonMapper.writeValueAsString(message)
            );
        } catch (RuntimeException ignored) {
        }
    }

    private boolean tryAcquirePollingLock(String symbol) {
        try {
            Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                ORDER_BOOK_POLL_LOCK_KEY_PREFIX + symbol,
                LOCK_VALUE,
                Duration.ofMillis(cacheProperties.pollingLockTtlMs())
            );
            return Boolean.TRUE.equals(acquired);
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private String cacheKey(String symbol) {
        return ORDER_BOOK_CACHE_KEY_PREFIX + symbol;
    }
}
