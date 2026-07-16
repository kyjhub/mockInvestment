package com.papertrade.paper_trading.Dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderExecutionResponse(
    Long executionId,
    BigDecimal executionPrice,
    Long executionQuantity,
    BigDecimal commission,
    BigDecimal tax,
    LocalDateTime executedAt
) {
}
