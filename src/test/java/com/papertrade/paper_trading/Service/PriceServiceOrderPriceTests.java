package com.papertrade.paper_trading.Service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.papertrade.paper_trading.Client.TossApiRateLimiter;
import com.papertrade.paper_trading.Client.TossCandleClient;
import com.papertrade.paper_trading.Client.TossOpenApiException;
import com.papertrade.paper_trading.Client.TossPriceClient;
import com.papertrade.paper_trading.Config.PriceCacheProperties;
import com.papertrade.paper_trading.Dto.Candle;
import com.papertrade.paper_trading.Dto.CandleResponse;
import com.papertrade.paper_trading.Dto.CandleResult;
import com.papertrade.paper_trading.Dto.PriceResult;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 주문 접수에 쓰는 현재가 조회.
 *
 * <p>여기서 예외가 새어 나가면 주문 API가 실패한다. 예산 부족은 {@code GlobalExceptionHandler}에서
 * 202로 바뀌어 접수되지 않은 주문에 "접수됨"을 응답하게 된다. 그래서 어떤 실패든 "현재가 모름"으로 끝나야 한다.
 */
class PriceServiceOrderPriceTests {

    private static final String SYMBOL = "AAPL";

    private final TossPriceClient tossPriceClient = mock(TossPriceClient.class);
    private final TossCandleClient tossCandleClient = mock(TossCandleClient.class);
    private final TossApiRateLimiter rateLimiter = mock(TossApiRateLimiter.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private PriceService priceService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        priceService = new PriceService(
            tossPriceClient, tossCandleClient, rateLimiter, redisTemplate, objectMapper, new PriceCacheProperties());
    }

    @Test
    void usesTheCachedPriceWithoutCallingToss() throws Exception {
        when(valueOperations.get("price:" + SYMBOL)).thenReturn(objectMapper.writeValueAsString(
            new PriceResult(SYMBOL, OffsetDateTime.now(), new BigDecimal("150.0000"), "USD")));

        assertThat(priceService.findPriceForOrder(SYMBOL)).contains(new BigDecimal("150.0000"));
        verifyNoInteractions(tossCandleClient, tossPriceClient);
    }

    @Test
    void fallsBackToTheDailyCloseOnTheChartBudgetNotTheOrderBookBudget() {
        // 현재가 API는 호가와 예산을 같이 쓴다. 주문마다 부르면 매칭에 쓸 호가 조회를 밀어낸다.
        when(rateLimiter.tryAcquire(TossApiRateLimiter.MARKET_DATA_CHART_GROUP)).thenReturn(true);
        when(tossCandleClient.getLatestDailyCandle(SYMBOL)).thenReturn(dailyCandleClosingAt("151.2300"));

        assertThat(priceService.findPriceForOrder(SYMBOL)).contains(new BigDecimal("151.2300"));
        verify(rateLimiter, never()).tryAcquire(TossApiRateLimiter.MARKET_DATA_GROUP);
        verifyNoInteractions(tossPriceClient);
    }

    @Test
    void reportsNoPriceWhenTheBudgetIsExhausted() {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(false);

        assertThat(priceService.findPriceForOrder(SYMBOL)).isEmpty();
        verifyNoInteractions(tossCandleClient);
    }

    @Test
    void reportsNoPriceWhenTossFails() {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(true);
        when(tossCandleClient.getLatestDailyCandle(SYMBOL))
            .thenThrow(new TossOpenApiException(500, "req-1", "internal-error", "시세 조회 중 일시적 오류"));

        assertThat(priceService.findPriceForOrder(SYMBOL)).isEmpty();
    }

    @Test
    void reportsNoPriceWhenThereIsNoDailyCandle() {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(true);
        when(tossCandleClient.getLatestDailyCandle(SYMBOL))
            .thenReturn(new CandleResponse(new CandleResult(List.of(), null)));

        assertThat(priceService.findPriceForOrder(SYMBOL)).isEmpty();
    }

    private CandleResponse dailyCandleClosingAt(String closePrice) {
        Candle candle = new Candle(OffsetDateTime.now(), null, null, null, new BigDecimal(closePrice), 0L, "USD");
        return new CandleResponse(new CandleResult(List.of(candle), null));
    }
}
