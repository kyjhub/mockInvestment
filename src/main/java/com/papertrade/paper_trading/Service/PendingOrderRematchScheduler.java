package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Dto.SymbolMatchRequestedEvent;
import com.papertrade.paper_trading.Enum.OrderStatus;
import com.papertrade.paper_trading.Repository.OrderRepository;
import com.papertrade.paper_trading.Repository.StockRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PendingOrderRematchScheduler {

    private static final List<OrderStatus> MATCHABLE_STATUSES = List.of(
        OrderStatus.PENDING,
        OrderStatus.PARTIALLY_FILLED
    );

    private final OrderRepository orderRepository;
    private final StockRepository stockRepository;
    private final SymbolMatchRequestedStreamPublisher symbolMatchRequestedStreamPublisher;

    @Scheduled(fixedDelayString = "${matching-engine.rematch.fixed-delay-ms:30000}")
    public void rematchPendingOrders() {
        for (Long stockId : orderRepository.findStockIdsByStatusInOrderByEarliestSubmittedAt(MATCHABLE_STATUSES)) {
            stockRepository.findById(stockId).ifPresent(stock ->
                symbolMatchRequestedStreamPublisher.publish(
                    new SymbolMatchRequestedEvent(stock.getSymbol(), "SAFETY_NET")
                )
            );
        }
    }
}
