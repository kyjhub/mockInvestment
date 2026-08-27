package com.papertrade.paper_trading.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TossApiRateLimitProperties {

    private static final String MARKET_DATA_GROUP = "market-data";
    private static final String MARKET_DATA_CHART_GROUP = "market-data-chart";
    private static final String MARKET_INFO_GROUP = "market-info";

    @Value("${toss-invest.rate-limit.market-data.default-limit:15}")
    private long marketDataDefaultLimit;

    @Value("${toss-invest.rate-limit.market-data.safety-margin:0.8}")
    private double marketDataSafetyMargin;

    @Value("${toss-invest.rate-limit.market-data-chart.default-limit:20}")
    private long marketDataChartDefaultLimit;

    @Value("${toss-invest.rate-limit.market-data-chart.safety-margin:0.8}")
    private double marketDataChartSafetyMargin;

    @Value("${toss-invest.rate-limit.market-info.default-limit:3}")
    private long marketInfoDefaultLimit;

    @Value("${toss-invest.rate-limit.market-info.safety-margin:0.8}")
    private double marketInfoSafetyMargin;

    public long defaultLimit(String group) {
        return switch (group) {
            case MARKET_DATA_GROUP -> marketDataDefaultLimit;
            case MARKET_DATA_CHART_GROUP -> marketDataChartDefaultLimit;
            case MARKET_INFO_GROUP -> marketInfoDefaultLimit;
            default -> throw new IllegalArgumentException("Unknown Toss API rate-limit group: " + group);
        };
    }

    public double safetyMargin(String group) {
        return switch (group) {
            case MARKET_DATA_GROUP -> marketDataSafetyMargin;
            case MARKET_DATA_CHART_GROUP -> marketDataChartSafetyMargin;
            case MARKET_INFO_GROUP -> marketInfoSafetyMargin;
            default -> throw new IllegalArgumentException("Unknown Toss API rate-limit group: " + group);
        };
    }
}
