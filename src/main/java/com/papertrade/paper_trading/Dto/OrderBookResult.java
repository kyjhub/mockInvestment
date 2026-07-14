package com.papertrade.paper_trading.Dto;

import java.time.OffsetDateTime;
import java.util.List;

public record OrderBookResult(
    OffsetDateTime timestamp,
    String currency,
    List<OrderBookLevel> asks,
    List<OrderBookLevel> bids
) {
}
