package com.papertrade.paper_trading.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.papertrade.paper_trading.Client.TossOrderBookClient;
import com.papertrade.paper_trading.Client.TossApiQuotaUnavailableException;
import com.papertrade.paper_trading.Client.TossApiRateLimiter;
import com.papertrade.paper_trading.Config.OrderBookCacheProperties;
import com.papertrade.paper_trading.Config.RedisPubSubConfig;
import com.papertrade.paper_trading.Dto.OrderBookPubSubMessage;
import com.papertrade.paper_trading.Dto.OrderBookResponse;
import com.papertrade.paper_trading.Dto.OrderBookResult;
import com.papertrade.paper_trading.WebSocket.DeclaredWebSocketSymbolRegistry;
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
    private static final String LOCK_VALUE = "1";

    private final TossOrderBookClient tossOrderBookClient;
    private final TossApiRateLimiter tossApiRateLimiter;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final OrderBookCacheProperties cacheProperties;
    private final DirtyOrderBookSymbolRegistry dirtySymbolRegistry;
    private final DeclaredWebSocketSymbolRegistry declaredSymbolRegistry;

    public OrderBookResponse getOrderBook(String symbol) {
        OrderBookResponse cachedResponse = getCachedOrderBook(symbol);
        if (cachedResponse != null) {
            return cachedResponse;
        }

        return fetchCacheAndPublish(symbol);
    }

    /**
     * 체결에 쓸 호가.
     *
     * <p>WebSocket 구독 종목은 캐시를 그대로 쓴다. 토스는 호가가 <b>바뀔 때만</b> 프레임을 보내므로,
     * {@code receivedAt}이 주문 접수보다 앞선다는 건 낡았다는 뜻이 아니라 그 이후로 변동이 없었다는 뜻이다.
     * REST를 불러도 같은 값을 받고, 오히려 왕복 지연만큼 낡은 응답이 {@code receivedAt=now}로 덮어써서
     * 신선도 메타데이터만 망가진다.
     *
     * <p>이 등식은 피드가 살아 있을 때만 성립한다. 판정에 {@code coveredSymbols}가 아니라
     * {@link DeclaredWebSocketSymbolRegistry}를 쓰는 이유가 그것이다 — 연결 실패·error frame·슬롯 상실·
     * 구독 ACK 불일치 모두에서 선언이 비워지므로, 피드가 죽으면 그 순간부터 REST 경로로 돌아온다.
     *
     * <p>구독 종목인데 캐시가 없으면 {@code null}을 돌려준다. 아직 첫 프레임도 폴링 씨딩도 오지 않은 짧은
     * 구간이고, 이번 라운드는 외부 유동성 없이(내부 체결만) 넘어간다. 씨딩은 {@code OrderBookPollingService}가
     * 1초 주기로 이미 하고 있다 — {@code isFresherThan()}이 캐시 null을 "신선하지 않음"으로 보기 때문이다.
     *
     * <p>비구독 종목은 기존 규칙 그대로다. 캐시가 {@code notBefore} 이후 값이 아니면 Toss를 호출한다.
     */
    public OrderBookResponse getOrderBookForMatching(String symbol, LocalDateTime notBefore) {
        OrderBookResponse cachedResponse = getCachedOrderBook(symbol);

        if (declaredSymbolRegistry.isDeclared(symbol)) {
            return cachedResponse;
        }

        if (cachedResponse != null && cachedResponse.receivedAt() != null && !cachedResponse.receivedAt().isBefore(notBefore)) {
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

    /**
     * REST 응답과 WebSocket 푸시가 공통으로 합류하는 지점.
     * 캐시 저장 → 변경 감지 → 화면 전파 → 매칭 트리거까지 한 번에 처리하므로,
     * 호출자는 호가를 어디서 얻었는지 신경 쓰지 않아도 된다.
     */
    public OrderBookResponse applyOrderBook(String symbol, OrderBookResult result) {
        OrderBookResponse previousResponse = getCachedOrderBook(symbol);
        OrderBookResponse response = new OrderBookResponse(result, LocalDateTime.now());
        cacheOrderBook(symbol, response);
        if (orderBookChanged(previousResponse, response)) {
            publishOrderBook(symbol, response);          // 화면 전파는 매 변경마다 필요하다
            dirtySymbolRegistry.markDirty(symbol);       // 매칭 트리거는 종목 단위로 합쳐진다
        }
        return response;
    }

    /** 캐시된 호가가 {@code threshold} 이내에 갱신됐는지. 공급원이 REST인지 WebSocket인지는 구분하지 않는다. */
    public boolean isFresherThan(String symbol, Duration threshold) {
        OrderBookResponse cachedResponse = getCachedOrderBook(symbol);
        if (cachedResponse == null || cachedResponse.receivedAt() == null) {
            return false;
        }
        return cachedResponse.receivedAt().isAfter(LocalDateTime.now().minus(threshold));
    }

    private OrderBookResponse fetchCacheAndPublish(String symbol) {
        if (!tossApiRateLimiter.tryAcquire(TossApiRateLimiter.MARKET_DATA_GROUP)) {
            throw new TossApiQuotaUnavailableException(
                TossApiRateLimiter.MARKET_DATA_GROUP,
                tossApiRateLimiter.secondsUntilAvailable(TossApiRateLimiter.MARKET_DATA_GROUP),
                "일시적으로 호가를 가져올 수 없습니다."
            );
        }
        return applyOrderBook(symbol, tossOrderBookClient.getOrderBook(symbol).result());
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

}
