package com.papertrade.paper_trading.Service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.papertrade.paper_trading.Entity.Stock;
import com.papertrade.paper_trading.Repository.OrderRepository;
import com.papertrade.paper_trading.Repository.StockRepository;
import com.papertrade.paper_trading.WebSocket.OrderBookSubscriptionRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 미체결 종목의 순서가 WebSocket 실시간 호가 정원을 누가 차지할지 정한다.
 * 밀려나면 REST 폴링으로 가서 갱신이 크게 느려지므로 순서가 그대로 보존돼야 한다.
 */
class ActiveOrderBookSymbolRegistryTests {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final StockRepository stockRepository = mock(StockRepository.class);

    private final ActiveOrderBookSymbolRegistry registry = new ActiveOrderBookSymbolRegistry(
        mock(OrderBookSubscriptionRegistry.class),
        orderRepository,
        stockRepository,
        null,
        null
    );

    @Test
    void keepsEarliestSubmittedOrderFirstEvenWhenLookupReturnsAnotherOrder() {
        // 쿼리는 접수 시각 순으로 [3, 1, 2]를 준다.
        // 스터빙 중에 mock을 만들면 Mockito가 미완성 스터빙으로 본다. 먼저 만들어 둔다.
        List<Stock> stocks = List.of(stock(1L, "AAA"), stock(2L, "BBB"), stock(3L, "CCC"));
        when(orderRepository.findStockIdsByStatusInOrderByEarliestSubmittedAt(anyList()))
            .thenReturn(List.of(3L, 1L, 2L));
        // findAllById는 입력 순서를 보장하지 않는다 — 여기서는 id 순으로 돌려준다.
        when(stockRepository.findAllById(any())).thenReturn(stocks);

        assertThat(registry.pendingOrderSymbols()).containsExactly("CCC", "AAA", "BBB");
    }

    @Test
    void skipsStockIdsThatNoLongerResolve() {
        List<Stock> stocks = List.of(stock(1L, "AAA"), stock(2L, "BBB"));
        when(orderRepository.findStockIdsByStatusInOrderByEarliestSubmittedAt(anyList()))
            .thenReturn(List.of(1L, 99L, 2L));
        when(stockRepository.findAllById(any())).thenReturn(stocks);

        assertThat(registry.pendingOrderSymbols()).containsExactly("AAA", "BBB");
    }

    @Test
    void returnsEmptyWithoutQueryingStocksWhenNothingIsPending() {
        when(orderRepository.findStockIdsByStatusInOrderByEarliestSubmittedAt(anyList()))
            .thenReturn(List.of());

        assertThat(registry.pendingOrderSymbols()).isEmpty();
    }

    private Stock stock(Long id, String symbol) {
        Stock stock = mock(Stock.class);
        org.mockito.Mockito.doReturn(id).when(stock).getId();
        org.mockito.Mockito.doReturn(symbol).when(stock).getSymbol();
        return stock;
    }
}
