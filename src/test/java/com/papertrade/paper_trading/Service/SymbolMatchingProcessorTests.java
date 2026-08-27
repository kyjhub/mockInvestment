package com.papertrade.paper_trading.Service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.papertrade.paper_trading.Client.TossApiQuotaUnavailableException;
import com.papertrade.paper_trading.Dto.DailyPriceRangeResponse;
import org.junit.jupiter.api.Test;

/**
 * 스트림 컨슈머와 dirty drainer가 공유하는 실행 경로.
 * 락을 반드시 잡고 반드시 놓아야 하며, 결과 분류가 두 호출자의 후속 처리를 결정한다.
 */
class SymbolMatchingProcessorTests {

    private static final String SYMBOL = "AAPL";
    private static final String LOCK_VALUE = "lock-1";

    private final SymbolOrderLockService lockService = mock(SymbolOrderLockService.class);
    private final DailyPriceRangeService dailyPriceRangeService = mock(DailyPriceRangeService.class);
    private final MatchingEngineTransactionService matchingService = mock(MatchingEngineTransactionService.class);
    private final SymbolMatchingProcessor processor =
        new SymbolMatchingProcessor(lockService, dailyPriceRangeService, matchingService);

    @Test
    void returnsLockBusyWithoutTouchingTheMatchingEngine() {
        when(lockService.acquire(SYMBOL)).thenThrow(new IllegalArgumentException("동일 종목 주문이 처리 중입니다."));

        assertThat(processor.process(SYMBOL)).isEqualTo(SymbolMatchingResult.LOCK_BUSY);
        verify(matchingService, org.mockito.Mockito.never()).matchSymbol(anyString(), any());
    }

    @Test
    void classifiesQuotaExhaustionAsWaitingRatherThanFailure() {
        // 예산 부족을 실패로 취급하면 DLQ가 인프라 상태 때문에 오염된다.
        when(lockService.acquire(SYMBOL)).thenReturn(LOCK_VALUE);
        when(dailyPriceRangeService.getDailyPriceRange(SYMBOL))
            .thenThrow(new TossApiQuotaUnavailableException("market-data-chart", 2L, "예산 없음"));

        assertThat(processor.process(SYMBOL)).isEqualTo(SymbolMatchingResult.QUOTA_UNAVAILABLE);
        verify(lockService).release(SYMBOL, LOCK_VALUE);
    }

    @Test
    void propagatesUnexpectedFailuresSoCallersCanRetryOrDlq() {
        when(lockService.acquire(SYMBOL)).thenReturn(LOCK_VALUE);
        when(dailyPriceRangeService.getDailyPriceRange(SYMBOL)).thenReturn(dailyPriceRange());
        doThrow(new IllegalStateException("DB connection lost"))
            .when(matchingService).matchSymbol(anyString(), any());

        assertThatThrownBy(() -> processor.process(SYMBOL))
            .isInstanceOf(IllegalStateException.class);

        // 예외가 나가도 락은 반드시 풀려야 한다.
        verify(lockService).release(SYMBOL, LOCK_VALUE);
    }

    @Test
    void matchesOnlyOncePerCallSoLockHoldTimeStaysPredictable() {
        // version 안정화 루프를 제거했으므로 시장 변동성과 무관하게 1회만 실행된다.
        when(lockService.acquire(SYMBOL)).thenReturn(LOCK_VALUE);
        when(dailyPriceRangeService.getDailyPriceRange(SYMBOL)).thenReturn(dailyPriceRange());

        assertThat(processor.process(SYMBOL)).isEqualTo(SymbolMatchingResult.SUCCESS);

        verify(matchingService, org.mockito.Mockito.times(1)).matchSymbol(anyString(), any());
        verify(lockService).release(SYMBOL, LOCK_VALUE);
    }

    private DailyPriceRangeResponse dailyPriceRange() {
        return new DailyPriceRangeResponse(SYMBOL, null, null, null, "USD");
    }
}
