package com.papertrade.paper_trading.Dto;

public record OrderSubmittedEvent(
    Long orderId,
    String symbol
) {
}
