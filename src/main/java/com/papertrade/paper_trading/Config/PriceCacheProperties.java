package com.papertrade.paper_trading.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PriceCacheProperties {

    @Value("${price.cache.ttl-seconds:2}")
    private long ttlSeconds;

    @Value("${price.polling.fixed-delay-ms:1000}")
    private long pollingFixedDelayMs;

    @Value("${price.polling.lock-ttl-ms:900}")
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
