package com.papertrade.paper_trading.Dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record DailyPriceRangeResponse(
    String symbol,
    OffsetDateTime timestamp,
    BigDecimal dailyHighPrice,
    BigDecimal dailyLowPrice,
    String currency
) {
}
