package com.papertrade.paper_trading.Client;

import com.papertrade.paper_trading.Config.TossApiRateLimitProperties;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TossApiRateLimiter {

    /** 호가, 현재가, 최근 체결, 상/하한가. 토스 기준 초당 15회. */
    public static final String MARKET_DATA_GROUP = "market-data";
    /** 캔들 차트. 호출 부하 특성이 달라 토스가 별도 그룹으로 분리했고 초당 20회. */
    public static final String MARKET_DATA_CHART_GROUP = "market-data-chart";
    /** 장 운영정보, 환율. 토스 기준 초당 3회. */
    public static final String MARKET_INFO_GROUP = "market-info";

    private static final String QUOTA_KEY_PREFIX = "toss-api:quota:";
    // X-RateLimit-Limit	현재 허용된 초당 요청 수 (burst capacity)
    private static final String OBSERVED_LIMIT_KEY_PREFIX = "toss-api:observed-limit:";
    private static final String NEXT_ALLOWED_AT_KEY_PREFIX = "toss-api:next-allowed-at:";
    private static final String RETRY_COUNT_KEY_PREFIX = "toss-api:retry-count:";
    private static final Duration QUOTA_TTL = Duration.ofSeconds(2);
    private static final Duration OBSERVED_LIMIT_TTL = Duration.ofDays(1);
    private static final Duration RETRY_COUNT_TTL = Duration.ofMinutes(10);
    private static final long BASE_BACKOFF_MILLIS = 1_000L;
    private static final long MAX_BACKOFF_MILLIS = 30_000L;
    /**
     *   KEYS[1] → List.of(...)로 넘긴 첫 번째 Redis key
     *   ARGV[1] → 첫 번째 일반 인자
     *   ARGV[2] → 두 번째 일반 인자
     */
    private static final DefaultRedisScript<Long> INCREMENT_WITH_TTL_SCRIPT = new DefaultRedisScript<>(
        "local count = redis.call('incr', KEYS[1]); "
            + "if count == 1 then redis.call('pexpire', KEYS[1], ARGV[1]); end; "
            + "return count;",
        Long.class
    );
    //  KEYS[1] → nextAllowedAt Redis key, ARGV[1] → 새롭게 계산한 nextAllowedAt, ARGV[2] → key TTL
    private static final DefaultRedisScript<Long> STORE_LATER_TIMESTAMP_SCRIPT = new DefaultRedisScript<>(
        "local current = redis.call('get', KEYS[1]); "
            + "if not current or tonumber(current) < tonumber(ARGV[1]) then "
            + "redis.call('psetex', KEYS[1], ARGV[2], ARGV[1]); return 1; "
            + "end; return 0;",
        Long.class
    );

    private final StringRedisTemplate stringRedisTemplate;
    private final TossApiRateLimitProperties properties;

    public boolean tryAcquire(String group) {
        try {
            if (isCoolingDown(group)) {
                return false;
            }
            // 해당시각(1초)에 토스증권 api에 나간 요청 횟수
            String countKey = QUOTA_KEY_PREFIX + group + ":" + Instant.now().getEpochSecond();
            Long count = stringRedisTemplate.execute(
                INCREMENT_WITH_TTL_SCRIPT,
                List.of(countKey),
                Long.toString(QUOTA_TTL.toMillis())
            );
            return count != null && count <= currentLimit(group);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public long secondsUntilAvailable(String group) {
        try {
            Long coolingDownRemainingMillis = coolingDownRemainingMillis(group);
            if (coolingDownRemainingMillis != null) {
                return Math.max(1L, (long) Math.ceil(coolingDownRemainingMillis / 1_000.0));
            }
        } catch (RuntimeException ignored) {
        }
        return QUOTA_TTL.toSeconds();
    }

    public void recordResponseHeaders(String group, HttpHeaders headers) {
        headers.firstValue("X-RateLimit-Limit").ifPresent(value -> {
            try {
                long limit = Long.parseLong(value);
                // Limit은 차단 여부보다 전체 허용량
                // Limit <= 0을 정상적인 제한값이 아니라 다음과 같은 잘못된 응답으로 간주합니다.
                //  - 헤더 파싱 오류
                //  - 공급자의 비정상 값
                //  - 일시적인 헤더 데이터 오류
                //  - 의미가 정의되지 않은 음수 값
                if (limit > 0) {
                    stringRedisTemplate.opsForValue().set(
                        OBSERVED_LIMIT_KEY_PREFIX + group,
                        Long.toString(limit),
                        OBSERVED_LIMIT_TTL
                    );
                }
            } catch (RuntimeException ignored) {
            }
        });
    }

    public void recordSuccessfulResponse(String group) {
        try {
            stringRedisTemplate.delete(RETRY_COUNT_KEY_PREFIX + group);
        } catch (RuntimeException ignored) {
        }
    }

    public void recordRateLimitExceeded(String group, HttpHeaders headers) {
        try {
            Long retryCount = stringRedisTemplate.execute(
                INCREMENT_WITH_TTL_SCRIPT,
                List.of(RETRY_COUNT_KEY_PREFIX + group),
                Long.toString(RETRY_COUNT_TTL.toMillis())
            );

            long exponentialBackoff = exponentialBackoffMillis(retryCount == null ? 1L : retryCount);
            long jitter = ThreadLocalRandom.current().nextLong(exponentialBackoff + 1L);
            long retryAfterMillis = retryAfterMillis(headers);
            long nextAllowedAt = System.currentTimeMillis() + retryAfterMillis + jitter;
            long ttlMillis = Math.max(1_000L, retryAfterMillis + exponentialBackoff + 1_000L);
            storeLaterNextAllowedAt(group, nextAllowedAt, ttlMillis);
        } catch (RuntimeException ignored) {
        }
    }

    private long currentLimit(String group) {
        String value = stringRedisTemplate.opsForValue().get(OBSERVED_LIMIT_KEY_PREFIX + group);
        long observedLimit = value == null ? properties.defaultLimit(group) : parseLimit(value, group);
        return Math.max(1L, (long) Math.floor(observedLimit * properties.safetyMargin(group)));
    }

    private long parseLimit(String value, String group) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : properties.defaultLimit(group);
        } catch (NumberFormatException ignored) {
            return properties.defaultLimit(group);
        }
    }

    // 현재 토스증권 api에 요청을 해도 되는지 여부
    private boolean isCoolingDown(String group) {
        return coolingDownRemainingMillis(group) != null;
    }

    private Long coolingDownRemainingMillis(String group) {
        String value = stringRedisTemplate.opsForValue().get(NEXT_ALLOWED_AT_KEY_PREFIX + group);
        if (value == null) {
            return null;
        }
        try {
            long remaining = Long.parseLong(value) - System.currentTimeMillis();
            return remaining > 0 ? remaining : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    // 1초 -> 2초 -> 4초 -> ... -> 최대 30초
    private long exponentialBackoffMillis(long retryCount) {
        int shift = (int) Math.min(Math.max(0L, retryCount - 1L), 30L);
        long backoff = BASE_BACKOFF_MILLIS * (1L << shift);
        return Math.min(MAX_BACKOFF_MILLIS, backoff);
    }

    private long retryAfterMillis(HttpHeaders headers) {
        return headers.firstValue("Retry-After")
            .map(this::parseRetryAfterMillis)
            .orElse(0L);
    }

    private long parseRetryAfterMillis(String value) {
        try {
            return Math.max(0L, (long) Math.ceil(Double.parseDouble(value) * 1_000D));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private void storeLaterNextAllowedAt(String group, long candidate, long ttlMillis) {
        String key = NEXT_ALLOWED_AT_KEY_PREFIX + group;
        stringRedisTemplate.execute(
            STORE_LATER_TIMESTAMP_SCRIPT,
            List.of(key),
            Long.toString(candidate),
            Long.toString(ttlMillis)
        );
    }
}
