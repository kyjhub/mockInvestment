package com.papertrade.paper_trading.Dto;

public record SymbolMatchRequestedEvent(
    String symbol,
    String reason
) {
}
