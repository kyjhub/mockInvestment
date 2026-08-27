package com.papertrade.paper_trading.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TossWebSocketProperties {

    @Value("${toss-invest.websocket.enabled:true}")
    private boolean enabled;

    @Value("${toss-invest.websocket.url:wss://openapi-ws.tossinvest.com/ws/v1}")
    private String url;

    @Value("${toss-invest.websocket.market:us}")
    private String market;

    /** 토스 한도: 연결당 구독 100건. */
    @Value("${toss-invest.websocket.max-symbols-per-connection:100}")
    private int maxSymbolsPerConnection;

    /** 토스 한도: 계정당 동시 연결 2개. */
    @Value("${toss-invest.websocket.connection-slots:2}")
    private int connectionSlots;

    /** 재선언 디바운스 주기. 토스 한도인 선언 5회/초 아래에 머물러야 한다. */
    @Value("${toss-invest.websocket.declare-debounce-ms:250}")
    private long declareDebounceMs;

    /** 서버는 클라이언트로부터의 수신이 180초 없으면 끊는다. 데이터 수신은 타이머를 리셋하지 않는다. */
    @Value("${toss-invest.websocket.ping-interval-seconds:60}")
    private long pingIntervalSeconds;

    @Value("${toss-invest.websocket.slot-lock-ttl-ms:15000}")
    private long slotLockTtlMs;

    @Value("${toss-invest.websocket.slot-heartbeat-ms:5000}")
    private long slotHeartbeatMs;

    public boolean enabled() {
        return enabled;
    }

    public String url() {
        return url;
    }

    public String market() {
        return market;
    }

    public int maxSymbolsPerConnection() {
        return maxSymbolsPerConnection;
    }

    public int connectionSlots() {
        return connectionSlots;
    }

    public long declareDebounceMs() {
        return declareDebounceMs;
    }

    public long pingIntervalSeconds() {
        return pingIntervalSeconds;
    }

    public long slotLockTtlMs() {
        return slotLockTtlMs;
    }

    public long slotHeartbeatMs() {
        return slotHeartbeatMs;
    }

    public int maxSymbols() {
        return maxSymbolsPerConnection * connectionSlots;
    }

    public String orderBookSubscriptionType() {
        return "orderbook:" + market;
    }
}
