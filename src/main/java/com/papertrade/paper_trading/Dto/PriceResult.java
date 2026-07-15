package com.papertrade.paper_trading.Dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record PriceResult(
    String symbol,
    OffsetDateTime timestamp,
    BigDecimal lastPrice,
    String currency
) {
}
