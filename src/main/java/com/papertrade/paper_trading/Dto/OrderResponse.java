package com.papertrade.paper_trading.Dto;

import com.papertrade.paper_trading.Enum.OrderSide;
import com.papertrade.paper_trading.Enum.OrderStatus;
import com.papertrade.paper_trading.Enum.OrderType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record OrderResponse(
    Long orderId,
    String clientOrderId,
    String symbol,
    OrderSide side,
    OrderType orderType,
    BigDecimal orderPrice,
    Long orderQuantity,
    Long filledQuantity,
    Long remainingQuantity,
    OrderStatus status,
    String rejectReason,
    LocalDateTime submittedAt,
    LocalDateTime updatedAt,
    List<OrderExecutionResponse> executions
) {
}
