package com.papertrade.paper_trading.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OrderBookCacheProperties {

    @Value("${orderbook.cache.ttl-seconds:2}")
    private long ttlSeconds;

    @Value("${orderbook.polling.fixed-delay-ms:1000}")
    private long pollingFixedDelayMs;

    @Value("${orderbook.polling.lock-ttl-ms:900}")
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
