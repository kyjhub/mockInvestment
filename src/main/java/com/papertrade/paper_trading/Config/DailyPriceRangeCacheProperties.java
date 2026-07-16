package com.papertrade.paper_trading.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DailyPriceRangeCacheProperties {

    @Value("${daily-price-range.cache.ttl-seconds:5}")
    private long ttlSeconds;

    @Value("${daily-price-range.polling.fixed-delay-ms:1000}")
    private long pollingFixedDelayMs;

    @Value("${daily-price-range.polling.lock-ttl-ms:900}")
    private long pollingLockTtlMs;

    public long ttlSeconds() {
        return ttlSeconds;
    }

    public long pollingFixedDelayMs() {
        return pollingFixedDelayMs;
    }

    public long pollingLockTtlMs() {
        return pollingLockTtlMs;
    }
}
