package com.papertrade.paper_trading.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MarketCalendarCacheProperties {

    @Value("${market-calendar.cache.ttl-hours:12}")
    private long ttlHours;

    public long ttlHours() {
        return ttlHours;
    }
}
