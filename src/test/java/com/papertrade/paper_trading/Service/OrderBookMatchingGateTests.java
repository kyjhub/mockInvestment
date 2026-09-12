package com.papertrade.paper_trading.Service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.papertrade.paper_trading.Client.TossApiRateLimiter;
import com.papertrade.paper_trading.Client.TossOrderBookClient;
import com.papertrade.paper_trading.Config.OrderBookCacheProperties;
import com.papertrade.paper_trading.Dto.OrderBookLevel;
import com.papertrade.paper_trading.Dto.OrderBookResponse;
import com.papertrade.paper_trading.Dto.OrderBookResult;
import com.papertrade.paper_trading.WebSocket.DeclaredWebSocketSymbolRegistry;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 체결용 호가 게이트.
 *
 * <p>토스는 호가가 <b>바뀔 때만</b> 프레임을 보내므로, 구독 종목의 {@code receivedAt}은
 * "마지막으로 확인한 시각"이 아니라 "마지막으로 바뀐 시각"이다. 그래서 주문 접수보다 앞선
 * 캐시는 낡은 게 아니라 "그 이후로 변동 없음"이고, REST를 불러도 같은 값을 받는다.
 *
 * <p>이 등식은 피드가 살아 있을 때만 성립하므로 판정은 반드시 <b>선언 목록</b> 기준이어야 한다.
 * {@code coveredSymbols()}는 연결이 끊겨도 남아서 이 자리에 쓸 수 없다.
 */
class OrderBookMatchingGateTests {

    private static final String SYMBOL = "AAPL";

    private final TossOrderBookClient tossOrderBookClient = mock(TossOrderBookClient.class);
    private final TossApiRateLimiter rateLimiter = mock(TossApiRateLimiter.class);
    private final StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final ObjectMapper objectMapper = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .build();
    private final OrderBookCacheProperties cacheProperties = mock(OrderBookCacheProperties.class);
    private final DirtyOrderBookSymbolRegistry dirtySymbolRegistry = mock(DirtyOrderBookSymbolRegistry.class);
    private final DeclaredWebSocketSymbolRegistry declaredSymbolRegistry = new DeclaredWebSocketSymbolRegistry();

    private final OrderBookService service = new OrderBookService(
        tossOrderBookClient,
        rateLimiter,
        stringRedisTemplate,
        objectMapper,
        cacheProperties,
        dirtySymbolRegistry,
        declaredSymbolRegistry
    );

    @Test
    void declaredSymbolUsesCacheEvenWhenItPredatesTheOrder() {
        // 프레임이 안 왔다 = 호가가 안 바뀌었다. REST를 부르면 같은 값을 받으면서 예산만 쓴다.
        LocalDateTime changedAt = LocalDateTime.now().minusMinutes(5);
        cache(orderBook(changedAt));
        declaredSymbolRegistry.replaceAll(List.of(SYMBOL));

        OrderBookResponse response = service.getOrderBookForMatching(SYMBOL, LocalDateTime.now());

        assertThat(response).isNotNull();
        assertThat(response.receivedAt()).isEqualTo(changedAt);
        verify(rateLimiter, never()).tryAcquire(anyString());
    }

    @Test
    void declaredSymbolWithoutCacheReturnsNullInsteadOfCallingToss() {
        // 배정 직후 구간. 토스는 구독 즉시 스냅샷을 주지 않으므로 첫 변동까지 캐시가 비어 있다.
        // 폴링이 1초 주기로 씨딩하므로 여기서 부를 필요가 없다. 이번 라운드는 내부 체결만 한다.
        cache(null);
        declaredSymbolRegistry.replaceAll(List.of(SYMBOL));

        assertThat(service.getOrderBookForMatching(SYMBOL, LocalDateTime.now())).isNull();
        verify(rateLimiter, never()).tryAcquire(anyString());
    }

    @Test
    void undeclaredSymbolKeepsTheOriginalFreshnessRule() {
        // 아무도 채워주지 않는 종목이다. 주문보다 오래된 캐시면 그 자리에서 갱신해야 한다.
        cache(orderBook(LocalDateTime.now().minusMinutes(5)));
        declaredSymbolRegistry.replaceAll(List.of());
        when(rateLimiter.tryAcquire(anyString())).thenReturn(true);
        when(tossOrderBookClient.getOrderBook(SYMBOL)).thenReturn(orderBook(LocalDateTime.now()));

        service.getOrderBookForMatching(SYMBOL, LocalDateTime.now());

        verify(tossOrderBookClient).getOrderBook(SYMBOL);
    }

    @Test
    void undeclaredSymbolWithFreshCacheDoesNotCallToss() {
        cache(orderBook(LocalDateTime.now()));
        declaredSymbolRegistry.replaceAll(List.of());

        service.getOrderBookForMatching(SYMBOL, LocalDateTime.now().minusSeconds(1));

        verify(rateLimiter, never()).tryAcquire(anyString());
    }

    @Test
    void emptyRegistryRoutesEverySymbolThroughRest() {
        // TOSS_WS_ENABLED=false이거나 슬롯을 전부 잃은 상태. 레지스트리가 비어 있어야
        // 매칭이 REST 경로로 돌아온다. 여기가 비지 않으면 외부 체결이 조용히 멈춘다.
        cache(orderBook(LocalDateTime.now().minusMinutes(5)));
        when(rateLimiter.tryAcquire(anyString())).thenReturn(true);
        when(tossOrderBookClient.getOrderBook(SYMBOL)).thenReturn(orderBook(LocalDateTime.now()));

        service.getOrderBookForMatching(SYMBOL, LocalDateTime.now());

        verify(tossOrderBookClient).getOrderBook(SYMBOL);
    }

    private void cache(OrderBookResponse response) {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        try {
            when(valueOperations.get(anyString()))
                .thenReturn(response == null ? null : objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private OrderBookResponse orderBook(LocalDateTime receivedAt) {
        return new OrderBookResponse(
            new OrderBookResult(
                null,
                "USD",
                List.of(new OrderBookLevel(new BigDecimal("100.0000"), 10L)),
                List.of(new OrderBookLevel(new BigDecimal("99.0000"), 10L))
            ),
            receivedAt
        );
    }
}
