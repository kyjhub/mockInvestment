package com.papertrade.paper_trading.Dto;

public record OrderBookPubSubMessage(
    String symbol,
    OrderBookResponse orderBook
) {
}
