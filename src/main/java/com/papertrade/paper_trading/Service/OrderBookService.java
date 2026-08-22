package com.papertrade.paper_trading.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.papertrade.paper_trading.Client.TossOrderBookClient;
import com.papertrade.paper_trading.Client.TossApiQuotaUnavailableException;
import com.papertrade.paper_trading.Client.TossApiRateLimiter;
import com.papertrade.paper_trading.Config.OrderBookCacheProperties;
import com.papertrade.paper_trading.Config.RedisPubSubConfig;
import com.papertrade.paper_trading.Dto.OrderBookPubSubMessage;
import com.papertrade.paper_trading.Dto.OrderBookResponse;
import com.papertrade.paper_trading.Dto.SymbolMatchRequestedEvent;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderBookService {

    private static final String ORDER_BOOK_CACHE_KEY_PREFIX = "orderbook:";
    private static final String ORDER_BOOK_POLL_LOCK_KEY_PREFIX = "orderbook:poll-lock:";
    private static final String ORDER_BOOK_VERSION_KEY_PREFIX = "orderbook:version:";
    private static final String LOCK_VALUE = "1";

    private final TossOrderBookClient tossOrderBookClient;
    private final TossApiRateLimiter tossApiRateLimiter;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final OrderBookCacheProperties cacheProperties;
    private final SymbolMatchRequestedStreamPublisher symbolMatchRequestedStreamPublisher;

    public OrderBookResponse getOrderBook(String symbol) {
        OrderBookResponse cachedResponse = getCachedOrderBook(symbol);
        if (cachedResponse != null) {
            return cachedResponse;
        }

        return fetchCacheAndPublish(symbol);
    }

    public OrderBookResponse getOrderBookNoOlderThan(String symbol, LocalDateTime notBefore) {
        OrderBookResponse cachedResponse = getCachedOrderBook(symbol);
        if (cachedResponse != null
            && cachedResponse.receivedAt() != null
            && !cachedResponse.receivedAt().isBefore(notBefore)) {
            return cachedResponse;
        }

        return fetchCacheAndPublish(symbol);
    }

    public void refreshAndPublish(String symbol) {
        if (!tryAcquirePollingLock(symbol)) {
            return;
        }

        try {
            fetchCacheAndPublish(symbol);
        } catch (TossApiQuotaUnavailableException ignored) {
        }
    }

    private OrderBookResponse fetchCacheAndPublish(String symbol) {
        if (!tossApiRateLimiter.tryAcquire(TossApiRateLimiter.ORDERBOOK_PRICE_CANDLE_GROUP)) {
            throw new TossApiQuotaUnavailableException(
                TossApiRateLimiter.ORDERBOOK_PRICE_CANDLE_GROUP,
                tossApiRateLimiter.secondsUntilAvailable(TossApiRateLimiter.ORDERBOOK_PRICE_CANDLE_GROUP),
                "일시적으로 호가를 가져올 수 없습니다."
            );
        }
        OrderBookResponse previousResponse = getCachedOrderBook(symbol);
        OrderBookResponse rawResponse = tossOrderBookClient.getOrderBook(symbol);
        OrderBookResponse response = new OrderBookResponse(rawResponse.result(), LocalDateTime.now());
        cacheOrderBook(symbol, response);
        if (orderBookChanged(previousResponse, response)) {
            incrementOrderBookVersion(symbol);
            publishOrderBook(symbol, response);
            symbolMatchRequestedStreamPublisher.publish(
                new SymbolMatchRequestedEvent(symbol, "ORDER_BOOK_UPDATED")
            );
        }
        return response;
    }

    public long getOrderBookVersion(String symbol) {
        try {
            String value = stringRedisTemplate.opsForValue().get(versionKey(symbol));
            return value == null ? 0L : Long.parseLong(value);
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private OrderBookResponse getCachedOrderBook(String symbol) {
        try {
            String cachedValue = stringRedisTemplate.opsForValue().get(cacheKey(symbol));
            if (cachedValue == null || cachedValue.isBlank()) {
                return null;
            }
            return objectMapper.readValue(cachedValue, OrderBookResponse.class);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private void cacheOrderBook(String symbol, OrderBookResponse response) {
        try {
            stringRedisTemplate.opsForValue().set(
                cacheKey(symbol),
                objectMapper.writeValueAsString(response),
                Duration.ofSeconds(cacheProperties.ttlSeconds())
            );
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private void publishOrderBook(String symbol, OrderBookResponse response) {
        try {
            OrderBookPubSubMessage message = new OrderBookPubSubMessage(symbol, response);
            stringRedisTemplate.convertAndSend(
                RedisPubSubConfig.ORDER_BOOK_UPDATES_CHANNEL,
                objectMapper.writeValueAsString(message)
            );
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private void incrementOrderBookVersion(String symbol) {
        stringRedisTemplate.opsForValue().increment(versionKey(symbol));
    }

    private boolean orderBookChanged(OrderBookResponse previousResponse, OrderBookResponse response) {
        if (previousResponse == null || previousResponse.result() == null) {
            return response != null && response.result() != null;
        }
        if (response == null || response.result() == null) {
            return true;
        }
        return !Objects.equals(previousResponse.result().currency(), response.result().currency())
            || !Objects.equals(previousResponse.result().asks(), response.result().asks())
            || !Objects.equals(previousResponse.result().bids(), response.result().bids());
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

    private String versionKey(String symbol) {
        return ORDER_BOOK_VERSION_KEY_PREFIX + symbol;
    }
}
