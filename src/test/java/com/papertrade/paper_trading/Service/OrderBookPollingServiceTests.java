package com.papertrade.paper_trading.Service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.papertrade.paper_trading.Config.OrderBookActiveSymbolProperties;
import com.papertrade.paper_trading.WebSocket.TossOrderBookWebSocketManager;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 신선도 게이트는 WebSocket이 채워주는 종목에만 적용해야 한다.
 * 아무도 채우지 않는 종목까지 막으면 1초 주기 폴링이 신선도 임계값(30초) 주기로 늘어난다.
 */
class OrderBookPollingServiceTests {

    private final ActiveOrderBookSymbolRegistry activeRegistry = mock(ActiveOrderBookSymbolRegistry.class);
    private final OrderBookService orderBookService = mock(OrderBookService.class);
    private final TossOrderBookWebSocketManager webSocketManager = mock(TossOrderBookWebSocketManager.class);

    private final OrderBookPollingService service = new OrderBookPollingService(
        activeRegistry,
        orderBookService,
        webSocketManager,
        properties(30_000L)
    );

    @Test
    void pollsSymbolsWebSocketDoesNotCoverEvenWhenCacheLooksFresh() {
        // WS 담당이 아니면 캐시가 신선해 보여도 아무도 갱신해주지 않는다. 게이트를 적용하면 안 된다.
        when(activeRegistry.pendingOrderSymbols()).thenReturn(List.of("AAPL"));
        when(webSocketManager.coveredSymbols()).thenReturn(List.of());
        when(orderBookService.isFresherThan(anyString(), any(Duration.class))).thenReturn(true);

        service.pollPendingOrderSymbols();

        verify(orderBookService).refreshAndPublish("AAPL");
    }

    @Test
    void skipsCoveredSymbolsWhileWebSocketKeepsThemFresh() {
        when(activeRegistry.pendingOrderSymbols()).thenReturn(List.of("AAPL"));
        when(webSocketManager.coveredSymbols()).thenReturn(List.of("AAPL"));
        when(orderBookService.isFresherThan(anyString(), any(Duration.class))).thenReturn(true);

        service.pollPendingOrderSymbols();

        verify(orderBookService, never()).refreshAndPublish(anyString());
    }

    @Test
    void fallsBackForCoveredSymbolsOnceTheCacheGoesStale() {
        // WebSocket이 멎으면 캐시가 낡으면서 자동으로 폴백된다.
        when(activeRegistry.pendingOrderSymbols()).thenReturn(List.of("AAPL"));
        when(webSocketManager.coveredSymbols()).thenReturn(List.of("AAPL"));
        when(orderBookService.isFresherThan(anyString(), any(Duration.class))).thenReturn(false);

        service.pollPendingOrderSymbols();

        verify(orderBookService).refreshAndPublish("AAPL");
    }

    private OrderBookActiveSymbolProperties properties(long stalenessThresholdMs) {
        OrderBookActiveSymbolProperties properties = new OrderBookActiveSymbolProperties();
        try {
            Field field = OrderBookActiveSymbolProperties.class.getDeclaredField("stalenessThresholdMs");
            field.setAccessible(true);
            field.set(properties, stalenessThresholdMs);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return properties;
    }
}
