package com.papertrade.paper_trading.WebSocket;

import com.papertrade.paper_trading.Config.TossWebSocketProperties;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 토스 WebSocket은 계정당 동시 연결이 2개뿐이다. 각 인스턴스가 제멋대로 연결하면
 * 새 연결이 가장 오래된 연결을 밀어내고, 밀려난 쪽이 재연결하며 서로 죽이는 flapping이 된다.
 * 그래서 Redis 락으로 슬롯을 점유한 인스턴스만 연결한다.
 */
@Component
@RequiredArgsConstructor
public class TossWebSocketSlotLock {

    private static final String SLOT_KEY_PREFIX = "toss-ws:slot:";
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then "
            + "return redis.call('pexpire', KEYS[1], ARGV[2]) "
            + "else return 0 end",
        Long.class
    );
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then "
            + "return redis.call('del', KEYS[1]) "
            + "else return 0 end",
        Long.class
    );

    private final StringRedisTemplate stringRedisTemplate;
    private final TossWebSocketProperties properties;
    private final String instanceId = UUID.randomUUID().toString();

    public boolean acquire(int slotIndex) {
        try {
            Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                slotKey(slotIndex),
                instanceId,
                Duration.ofMillis(properties.slotLockTtlMs())
            );
            return Boolean.TRUE.equals(acquired);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** 아직 이 인스턴스가 슬롯을 들고 있으면 TTL을 연장한다. false면 슬롯을 잃은 것이므로 연결을 닫아야 한다. */
    public boolean renew(int slotIndex) {
        try {
            Long renewed = stringRedisTemplate.execute(
                RENEW_SCRIPT,
                List.of(slotKey(slotIndex)),
                instanceId,
                Long.toString(properties.slotLockTtlMs())
            );
            return renewed != null && renewed > 0;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public void release(int slotIndex) {
        try {
            stringRedisTemplate.execute(RELEASE_SCRIPT, List.of(slotKey(slotIndex)), instanceId);
        } catch (RuntimeException ignored) {
        }
    }

    private String slotKey(int slotIndex) {
        return SLOT_KEY_PREFIX + slotIndex;
    }
}
