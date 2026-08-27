package com.papertrade.paper_trading.Dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code POST /oauth2/token} 응답. OAuth2 표준을 따라 snake_case로 내려온다. */
public record TossAccessTokenResponse(
    @JsonProperty("access_token") String accessToken,
    @JsonProperty("token_type") String tokenType,
    @JsonProperty("expires_in") long expiresIn
) {
}
