package com.papertrade.paper_trading.Service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.papertrade.paper_trading.Dto.DailyPriceRangeResponse;
import com.papertrade.paper_trading.Dto.OrderBookResponse;
import com.papertrade.paper_trading.Repository.AccountRepository;
import com.papertrade.paper_trading.Repository.ExecutionRepository;
import com.papertrade.paper_trading.Repository.HoldingRepository;
import com.papertrade.paper_trading.Repository.LedgerEntryRepository;
import com.papertrade.paper_trading.Repository.LedgerTransactionRepository;
import com.papertrade.paper_trading.Repository.OrderRepository;
import com.papertrade.paper_trading.Repository.OrderRepository.MatchableOrder;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 종목 단위 매칭은 주문을 순회하는데, 한 주문이 던진 예외로 루프가 중단되면
 * 뒤에 줄 선 정상 주문이 영영 처리되지 않고 재시도해도 같은 자리에서 다시 막힌다.
 * 결과적으로 주문 하나가 종목 전체의 체결을 멈출 수 있어 그 경계를 고정한다.
 */
class MatchingEngineTransactionServiceTests {

    private static final String SYMBOL = "AAPL";

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderBookService orderBookService = mock(OrderBookService.class);
    private final List<Long> attemptedOrderIds = new ArrayList<>();

    @Test
    void oneUnmatchableOrderDoesNotBlockTheRestOfTheSymbol() {
        // 2번 주문이 데이터 정합성 위반으로 실패해도 1·3번은 처리되어야 한다.
        MatchingEngineTransactionService service = serviceWith(orderId ->
            orderId == 2L ? new IllegalArgumentException("보유 수량이 부족합니다.") : null
        );

        service.matchSymbol(SYMBOL, dailyPriceRange());

        assertThat(attemptedOrderIds).containsExactly(1L, 2L, 3L);
    }

    @Test
    void systemFailuresStillPropagateSoTheRecordCanBeRetried() {
        // DB·외부 API 장애는 격리하면 안 된다. 전파해야 재시도와 DLQ 경로를 탄다.
        MatchingEngineTransactionService service = serviceWith(orderId ->
            orderId == 2L ? new IllegalStateException("DB connection lost") : null
        );

        assertThatThrownBy(() -> service.matchSymbol(SYMBOL, dailyPriceRange()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("DB connection lost");

        // 장애 시점에서 멈추므로 3번은 시도되지 않는다.
        assertThat(attemptedOrderIds).containsExactly(1L, 2L);
    }

    /** matchOrder를 직접 실행하는 대신, 트랜잭션 콜백이 호출될 때 주어진 예외를 던지도록 대체한다. */
    private MatchingEngineTransactionService serviceWith(FailurePlan failurePlan) {
        when(orderRepository.findMatchableOrdersBySymbol(eq(SYMBOL), anyList())).thenReturn(List.of(
            new MatchableOrder(1L, LocalDateTime.now()),
            new MatchableOrder(2L, LocalDateTime.now()),
            new MatchableOrder(3L, LocalDateTime.now())
        ));
        when(orderBookService.getOrderBookForMatching(anyString(), any()))
            .thenReturn(new OrderBookResponse(null, LocalDateTime.now()));

        return new MatchingEngineTransactionService(
            mock(AccountRepository.class),
            orderRepository,
            mock(ExecutionRepository.class),
            mock(HoldingRepository.class),
            new LedgerPostingService(mock(LedgerTransactionRepository.class), mock(LedgerEntryRepository.class)),
            mock(DailyPriceRangeService.class),
            orderBookService,
            mock(CommissionCalculator.class),
            passThroughTransactionManager()
        ) {
            @Override
            public void matchOrder(
                Long orderId,
                OrderBookResponse orderBook,
                DailyPriceRangeResponse dailyPriceRange
            ) {
                attemptedOrderIds.add(orderId);
                RuntimeException failure = failurePlan.failureFor(orderId);
                if (failure != null) {
                    throw failure;
                }
            }
        };
    }

    /** TransactionTemplate이 콜백을 그대로 실행하도록 최소 동작만 흉내낸다. */
    private PlatformTransactionManager passThroughTransactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }

    private DailyPriceRangeResponse dailyPriceRange() {
        return new DailyPriceRangeResponse(SYMBOL, null, null, null, "USD");
    }

    @FunctionalInterface
    private interface FailurePlan {
        RuntimeException failureFor(Long orderId);
    }
}
