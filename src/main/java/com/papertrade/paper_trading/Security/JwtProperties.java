package com.papertrade.paper_trading.Security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtProperties {

    @Value("${security.jwt.secret:paper-trading-local-development-jwt-secret-key-change-me}")
    private String secret;

    @Value("${security.jwt.access-token-expiration-minutes:30}")
    private long accessTokenExpirationMinutes;

    @Value("${security.jwt.refresh-token-expiration-days:14}")
    private long refreshTokenExpirationDays;

    public String secret() {
        return secret;
    }

    public long accessTokenExpirationMinutes() {
        return accessTokenExpirationMinutes;
    }

    public long refreshTokenExpirationDays() {
        return refreshTokenExpirationDays;
    }
}
