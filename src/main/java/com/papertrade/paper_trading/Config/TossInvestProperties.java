package com.papertrade.paper_trading.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TossInvestProperties {

    @Value("${toss-invest.openapi.base-url:https://openapi.tossinvest.com}")
    private String baseUrl;

    @Value("${toss-invest.openapi.secret-token:}")
    private String secretToken;

    public String baseUrl() {
        return baseUrl;
    }

    public String secretToken() {
        return secretToken;
    }
}
