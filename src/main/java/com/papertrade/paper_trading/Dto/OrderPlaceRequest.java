package com.papertrade.paper_trading.Dto;

import com.papertrade.paper_trading.Enum.OrderSide;
import com.papertrade.paper_trading.Enum.OrderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record OrderPlaceRequest(
    @Size(max = 36)
    @Pattern(regexp = "^[a-zA-Z0-9\\-_]+$")
    String clientOrderId,

    @NotBlank
    @Pattern(regexp = "^[A-Za-z0-9.\\-]+$")
    String symbol,

    @NotNull
    OrderSide side,

    @NotNull
    OrderType orderType,

    @Positive
    BigDecimal price,

    @NotNull
    @Positive
    Long quantity
) {
}
