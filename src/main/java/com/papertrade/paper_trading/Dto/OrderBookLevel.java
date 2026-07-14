package com.papertrade.paper_trading.Dto;

import java.math.BigDecimal;

public record OrderBookLevel(
    BigDecimal price,
    Long volume
) {
}
