package com.papertrade.paper_trading.Dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record Candle(
    OffsetDateTime timestamp,
    BigDecimal openPrice,
    BigDecimal highPrice,
    BigDecimal lowPrice,
    BigDecimal closePrice,
    Long volume,
    String currency
) {
}
