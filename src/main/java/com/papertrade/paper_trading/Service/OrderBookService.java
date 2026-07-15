package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Client.TossOrderBookClient;
import com.papertrade.paper_trading.Dto.OrderBookResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderBookService {

    private final TossOrderBookClient tossOrderBookClient;

    public OrderBookResponse getOrderBook(String symbol) {
        return tossOrderBookClient.getOrderBook(symbol);
    }
}
