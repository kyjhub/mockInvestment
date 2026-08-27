package com.papertrade.paper_trading.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TossInvestProperties {

    @Value("${toss-invest.openapi.base-url:https://openapi.tossinvest.com}")
    private String baseUrl;

    @Value("${toss-invest.openapi.client-id:}")
    private String clientId;

    /** OAuth2 client credentials의 client_secret. 이 값을 그대로 Bearer 토큰으로 쓰면 401이 난다. */
    @Value("${toss-invest.openapi.secret-token:}")
    private String secretToken;

    public String baseUrl() {
        return baseUrl;
    }

    public String clientId() {
        return clientId;
    }

    public String secretToken() {
        return secretToken;
    }
}
