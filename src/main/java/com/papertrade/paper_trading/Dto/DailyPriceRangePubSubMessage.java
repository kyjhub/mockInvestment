package com.papertrade.paper_trading.Dto;

public record DailyPriceRangePubSubMessage(
    String symbol,
    DailyPriceRangeResponse dailyPriceRange
) {
}
