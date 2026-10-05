package com.papertrade.paper_trading.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PriceCacheProperties {

    /**
     * 현재가 캐시 수명. 주문 접수가 허용하는 현재가의 나이이기도 하다({@code PriceService.findPriceForOrder}).
     *
     * <p>구독 중인 종목은 폴링이 1초마다 덮어쓰므로 이 값과 무관하게 신선하다. 이 값이 의미를 갖는 것은
     * 구독이 없는 종목이고, 시장가 주문은 이 나이의 현재가에 10%를 더해 지정가로 바꾼다.
     */
    @Value("${price.cache.ttl-seconds:30}")
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
