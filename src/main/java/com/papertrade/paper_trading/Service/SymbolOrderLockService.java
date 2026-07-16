package com.papertrade.paper_trading.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SymbolOrderLockService {

    private static final String LOCK_KEY_PREFIX = "order-match-lock:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then "
            + "return redis.call('del', KEYS[1]) "
            + "else return 0 end",
        Long.class
    );

    private final StringRedisTemplate stringRedisTemplate;

    public String acquire(String symbol) {
        String lockValue = UUID.randomUUID().toString();
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
            lockKey(symbol),
            lockValue,
            LOCK_TTL
        );

        if (!Boolean.TRUE.equals(acquired)) {
            throw new IllegalArgumentException("동일 종목 주문이 처리 중입니다. 잠시 후 다시 시도해 주세요.");
        }

        return lockValue;
    }

    public void release(String symbol, String lockValue) {
        stringRedisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey(symbol)), lockValue);
    }

    private String lockKey(String symbol) {
        return LOCK_KEY_PREFIX + symbol;
    }
}
