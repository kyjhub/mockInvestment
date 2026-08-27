package com.papertrade.paper_trading.Service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 호가가 갱신된 종목을 모으는 집합.
 *
 * <p>WebSocket 푸시는 종목당 초당 7회 안팎으로 들어오는데, 매칭에 필요한 정보는 "이 종목을 봐야 한다"는
 * 사실 하나뿐이라 횟수를 보존할 이유가 없다. 스트림(append-only)에 넣으면 같은 종목의 트리거가
 * 전부 별개 레코드로 쌓여 소비 능력을 넘기지만, 집합에 넣으면 중복이 구조적으로 제거된다.
 *
 * <p>저장 cardinality가 줄어드는 것이지 Redis 호출 횟수가 줄어드는 것은 아니다 —
 * {@code SADD}는 여전히 푸시마다 발생한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DirtyOrderBookSymbolRegistry {

    private static final String DIRTY_KEY = "orderbook:dirty";

    private final StringRedisTemplate stringRedisTemplate;

    /** Redis 장애로 실패해도 삼킨다 — 30초 안전망이 최종 복구를 담당한다. */
    public void markDirty(String symbol) {
        try {
            stringRedisTemplate.opsForSet().add(DIRTY_KEY, symbol);
        } catch (RuntimeException e) {
            log.warn("Failed to mark order book dirty. symbol={}", symbol, e);
        }
    }

    /**
     * 최대 {@code count}개를 꺼내면서 집합에서 제거한다.
     *
     * <p>{@code SPOP}은 원자적이라 여러 인스턴스가 같은 시점에 같은 종목을 중복으로 가져가지 않는다.
     * 다만 <b>처리 중 다시 추가된 종목까지 보호하지는 않는다</b> — 매칭하는 동안 호가가 갱신되면
     * 집합에 다시 들어오고, 그것을 다른 인스턴스가 꺼내 락 경합으로 버리면 신호가 사라진다.
     * 그래서 호출자는 락 경합 시 반드시 재등록해야 한다.
     *
     * <p>꺼낸 뒤에 매칭하는 순서도 중요하다. 매칭 후에 제거하면 그 사이 도착한 갱신까지 지워진다.
     */
    public List<String> pop(long count) {
        try {
            List<String> symbols = stringRedisTemplate.opsForSet().pop(DIRTY_KEY, count);
            return symbols == null ? List.of() : symbols;
        } catch (RuntimeException e) {
            log.warn("Failed to pop dirty order book symbols", e);
            return List.of();
        }
    }

    public long size() {
        try {
            Long size = stringRedisTemplate.opsForSet().size(DIRTY_KEY);
            return size == null ? 0L : size;
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }
}
