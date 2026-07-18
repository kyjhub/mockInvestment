package com.papertrade.paper_trading.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TossApiRateLimitProperties {

    private static final String ORDERBOOK_PRICE_CANDLE_GROUP = "orderbook-price-candle";
    private static final String MARKET_CALENDAR_EXCHANGE_RATE_GROUP = "market-calendar-exchange-rate";

    @Value("${toss-invest.rate-limit.orderbook-price-candle.default-limit:5}")
    private long groupADefaultLimit;

    @Value("${toss-invest.rate-limit.orderbook-price-candle.safety-margin:0.8}")
    private double groupASafetyMargin;

    @Value("${toss-invest.rate-limit.market-calendar-exchange-rate.default-limit:5}")
    private long groupBDefaultLimit;

    @Value("${toss-invest.rate-limit.market-calendar-exchange-rate.safety-margin:0.8}")
    private double groupBSafetyMargin;

    public long defaultLimit(String group) {
        return switch (group) {
            case ORDERBOOK_PRICE_CANDLE_GROUP -> groupADefaultLimit;
            case MARKET_CALENDAR_EXCHANGE_RATE_GROUP -> groupBDefaultLimit;
            default -> throw new IllegalArgumentException("Unknown Toss API rate-limit group: " + group);
        };
    }

    public double safetyMargin(String group) {
        return switch (group) {
            case ORDERBOOK_PRICE_CANDLE_GROUP -> groupASafetyMargin;
            case MARKET_CALENDAR_EXCHANGE_RATE_GROUP -> groupBSafetyMargin;
            default -> throw new IllegalArgumentException("Unknown Toss API rate-limit group: " + group);
        };
    }
}
