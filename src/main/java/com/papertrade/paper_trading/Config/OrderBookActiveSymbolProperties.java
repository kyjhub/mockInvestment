package com.papertrade.paper_trading.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OrderBookActiveSymbolProperties {

    @Value("${orderbook.active-symbols.ttl-ms:30000}")
    private long ttlMs;

    /**
     * 이 시간보다 캐시가 낡았을 때만 REST 폴링 대상이 된다.
     * WebSocket 재연결(2~3초)보다 넉넉해야 짧은 단절에 폴백이 헛돌지 않는다.
     */
    @Value("${orderbook.polling.staleness-threshold-ms:30000}")
    private long stalenessThresholdMs;

    public long ttlMs() {
        return ttlMs;
    }

    public long stalenessThresholdMs() {
        return stalenessThresholdMs;
    }
}
