package com.papertrade.paper_trading.Dto;

public record AuthTokenResponse(
    String tokenType,
    String accessToken,
    String refreshToken
) {
}
