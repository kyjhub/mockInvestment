package com.papertrade.paper_trading.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OrderBookCacheProperties {

    /**
     * {@code orderbook.polling.staleness-threshold-ms}보다 반드시 커야 한다.
     * 폴링이 되살리는 동안 키가 만료되면 구독 종목의 호가가 주기적으로 사라진다.
     */
    @Value("${orderbook.cache.ttl-seconds:120}")
    private long ttlSeconds;

    @Value("${orderbook.polling.fixed-delay-ms:1000}")
    private long pollingFixedDelayMs;

    @Value("${orderbook.polling.idle-fixed-delay-ms:20000}")
    private long idlePollingFixedDelayMs;

    @Value("${orderbook.polling.lock-ttl-ms:900}")
    private long pollingLockTtlMs;

    public long ttlSeconds() {
        return ttlSeconds;
    }

    public long pollingFixedDelayMs() {
        return pollingFixedDelayMs;
    }

    public long idlePollingFixedDelayMs() {
        return idlePollingFixedDelayMs;
    }

    public long pollingLockTtlMs() {
        return pollingLockTtlMs;
    }
}
