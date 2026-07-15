package com.papertrade.paper_trading.Dto;

import jakarta.validation.constraints.NotBlank;

public record TokenRefreshRequest(
    @NotBlank
    String refreshToken
) {
}
