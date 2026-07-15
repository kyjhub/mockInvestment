package com.papertrade.paper_trading.Dto;

public record PricePubSubMessage(
    String symbol,
    PriceResult price
) {
}
