package com.papertrade.paper_trading.Service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.papertrade.paper_trading.Repository.OrderRepository;
import com.papertrade.paper_trading.Repository.OrderRepository.AwaitingPriceOrder;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 접수 검증 대기 주문은 종목 단위로 현재가를 한 번에 조회하고, 가격을 구한 주문만 검증을 마친다.
 */
class AwaitingPriceOrderSchedulerTests {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final MarketPriceLookup marketPriceLookup = mock(MarketPriceLookup.class);
    private final OrderTradingService orderTradingService = mock(OrderTradingService.class);
    private final AwaitingPriceOrderScheduler scheduler =
        new AwaitingPriceOrderScheduler(orderRepository, marketPriceLookup, orderTradingService);

    @Test
    void looksUpEachSymbolOnceAndConfirmsOnlyOrdersWithAPrice() {
        when(orderRepository.findAwaitingPriceOrders()).thenReturn(List.of(
            new AwaitingPriceOrder(1L, "AAPL"),
            new AwaitingPriceOrder(2L, "AAPL"),
            new AwaitingPriceOrder(3L, "TSLA")
        ));
        // TSLA는 이번 주기에 현재가를 못 구했다.
        when(marketPriceLookup.lastPricesOf(List.of("AAPL", "TSLA")))
            .thenReturn(Map.of("AAPL", new BigDecimal("150.0000")));

        scheduler.confirmAwaitingOrders();

        verify(orderTradingService).confirmAwaitingPrice(1L, new BigDecimal("150.0000"));
        verify(orderTradingService).confirmAwaitingPrice(2L, new BigDecimal("150.0000"));
        // 못 구한 주문은 그대로 대기하고 다음 주기에 다시 시도한다.
        verify(orderTradingService, never()).confirmAwaitingPrice(3L, null);
    }

    @Test
    void oneFailingOrderDoesNotStopTheRest() {
        when(orderRepository.findAwaitingPriceOrders()).thenReturn(List.of(
            new AwaitingPriceOrder(1L, "AAPL"),
            new AwaitingPriceOrder(2L, "AAPL")
        ));
        when(marketPriceLookup.lastPricesOf(List.of("AAPL"))).thenReturn(Map.of("AAPL", new BigDecimal("150.0000")));
        when(orderTradingService.confirmAwaitingPrice(1L, new BigDecimal("150.0000")))
            .thenThrow(new IllegalStateException("lock timeout"));

        scheduler.confirmAwaitingOrders();

        verify(orderTradingService).confirmAwaitingPrice(2L, new BigDecimal("150.0000"));
    }

    @Test
    void doesNothingWithoutAwaitingOrders() {
        when(orderRepository.findAwaitingPriceOrders()).thenReturn(List.of());

        scheduler.confirmAwaitingOrders();

        verify(marketPriceLookup, never()).lastPricesOf(any());
        verify(orderTradingService, never()).confirmAwaitingPrice(anyLong(), any());
    }
}
