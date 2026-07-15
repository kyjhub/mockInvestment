package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Client.TossOrderBookClient;
import com.papertrade.paper_trading.Dto.OrderBookResponse;
import org.springframework.stereotype.Service;

@Service
public class OrderBookService {

    private final TossOrderBookClient tossOrderBookClient;

    public OrderBookService(TossOrderBookClient tossOrderBookClient) {
        this.tossOrderBookClient = tossOrderBookClient;
    }

    public OrderBookResponse getOrderBook(String symbol) {
        return tossOrderBookClient.getOrderBook(symbol);
    }
}
