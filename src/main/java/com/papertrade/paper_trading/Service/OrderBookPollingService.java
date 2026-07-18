package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Enum.OrderStatus;
import com.papertrade.paper_trading.Repository.OrderRepository;
import com.papertrade.paper_trading.Repository.StockRepository;
import com.papertrade.paper_trading.WebSocket.OrderBookSubscriptionRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderBookPollingService {

    private static final List<OrderStatus> MATCHABLE_STATUSES = List.of(
        OrderStatus.PENDING,
        OrderStatus.PARTIALLY_FILLED
    );

    private final OrderBookSubscriptionRegistry subscriptionRegistry;
    private final OrderBookService orderBookService;
    private final OrderRepository orderRepository;
    private final StockRepository stockRepository;
    private int pendingRotationOffset;
    private int subscriptionRotationOffset;

    @Scheduled(fixedDelayString = "${orderbook.polling.fixed-delay-ms:1000}")
    public void pollActiveOrderBooks() {
        List<String> pendingSymbols = new ArrayList<>();
        for (Long stockId : orderRepository.findDistinctStockIdsByStatusIn(MATCHABLE_STATUSES)) {
            stockRepository.findById(stockId).ifPresent(stock -> pendingSymbols.add(stock.getSymbol()));
        }
        Collections.sort(pendingSymbols);
        pendingSymbols = rotate(pendingSymbols, pendingRotationOffset++);

        Set<String> pendingSymbolSet = new HashSet<>(pendingSymbols);
        List<String> subscriptionSymbols = subscriptionRegistry.activeSymbols().stream()
            .filter(symbol -> !pendingSymbolSet.contains(symbol))
            .sorted()
            .toList();
        subscriptionSymbols = rotate(subscriptionSymbols, subscriptionRotationOffset++);

        for (String symbol : pendingSymbols) {
            orderBookService.refreshAndPublish(symbol);
        }
        for (String symbol : subscriptionSymbols) {
            orderBookService.refreshAndPublish(symbol);
        }
    }

    private List<String> rotate(List<String> symbols, int offset) {
        if (symbols.isEmpty()) {
            return symbols;
        }
        List<String> rotated = new ArrayList<>(symbols);
        Collections.rotate(rotated, -Math.floorMod(offset, rotated.size()));
        return rotated;
    }
}
