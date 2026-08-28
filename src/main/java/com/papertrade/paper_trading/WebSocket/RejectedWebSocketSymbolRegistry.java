package com.papertrade.paper_trading.WebSocket;

import com.papertrade.paper_trading.Config.TossWebSocketProperties;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 구독이 거절된 종목을 인스턴스 간에 공유한다.
 *
 * <p>거절은 슬롯을 소유한 인스턴스만 알 수 있는데, 그 정보로 폴링 여부를 판단해야 하는 것은 모든 인스턴스다.
 * 공유하지 않으면 거절된 종목이 WebSocket 담당으로 잡혀 REST 폴링에서도 제외되고,
 * 결국 아무도 채우지 않는 종목이 된다.
 *
 * <p>Redis Set은 멤버별 만료가 없어 sorted set에 거절 시각을 score로 넣고 범위로 읽는다.
 * 종목 마스터에 뒤늦게 등록되는 경우가 있으므로 영구 제외하지 않고 TTL이 지나면 다시 시도한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RejectedWebSocketSymbolRegistry {

    private static final String REJECTED_KEY = "orderbook:ws-rejected";

    private final StringRedisTemplate stringRedisTemplate;
    private final TossWebSocketProperties properties;

    public void markRejected(String symbol) {
        long now = System.currentTimeMillis();
        try {
            stringRedisTemplate.opsForZSet().add(REJECTED_KEY, symbol, now);
            stringRedisTemplate.opsForZSet().removeRangeByScore(
                REJECTED_KEY,
                Double.NEGATIVE_INFINITY,
                now - properties.rejectedSymbolTtlMs()
            );
        } catch (RuntimeException e) {
            log.warn("Failed to record rejected WebSocket symbol. symbol={}", symbol, e);
        }
    }

    public Set<String> rejectedSymbols() {
        try {
            Set<String> symbols = stringRedisTemplate.opsForZSet().rangeByScore(
                REJECTED_KEY,
                System.currentTimeMillis() - properties.rejectedSymbolTtlMs(),
                Double.POSITIVE_INFINITY
            );
            return symbols == null ? Set.of() : symbols;
        } catch (RuntimeException e) {
            log.warn("Failed to read rejected WebSocket symbols", e);
            return Set.of();
        }
    }
}
