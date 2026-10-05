# 토스증권 API 호출량 제한 대응 (호가/현재가/캔들 그룹, 장운영정보/환율 그룹)

## Context

토스증권 API는 그룹 단위로 초당 호출 한도가 있다.

- 그룹 A: 호가 + 현재가 + 캔들(일봉) 합쳐서 초당 최대 10회
- 그룹 B: 장 운영정보 + 환율(추가 예정) 합쳐서 초당 최대 10회

현재 코드는 이 한도를 전혀 감안하지 않고, 활성 종목 수만큼 매 폴링 틱(기본 1초)마다 무조건 토스를 호출한다. 활성 종목(관심 종목 + 미체결 주문 종목)이 10개를 넘으면 즉시 429가 나기 시작하고, 이건 "가끔 튀는 버스트"가 아니라 "종목이 많아질수록 계속되는 초과 수요"라서 재시도 큐잉만으로는 해결되지 않는다 (지연이 계속 누적됨).

또한 실제로 토스 호가 API를 호출하는 지점이 세 곳(정기 폴링, REST 캐시미스, 매칭엔진 캐시미스)으로 확인됐다 — 예산 체크를 폴링 스케줄러 한 곳에만 넣으면 나머지 두 경로가 우회해서 한도를 넘길 수 있으므로, 세 경로가 공통으로 거치는 지점에 예산 체크를 둬야 한다.

현재가/캔들 조회는 계속 사용하는 것으로 확정했다.

토스 API의 모든 응답(정상/429)에는 `X-RateLimit-Limit`(현재 허용된 초당 요청 수), `X-RateLimit-Remaining`(남은 토큰), `X-RateLimit-Reset`(토큰 1개 재충전까지 예상 초), 그리고 429 응답에만 `Retry-After`(권장 재시도 대기 초)가 담겨온다. 그룹별 한도를 하드코딩된 숫자로 고정하지 않고, **`X-RateLimit-Limit`을 실제 응답에서 읽어 동적으로 갱신**하는 방식으로 설계를 바꾼다.

`Retry-After`와 `X-RateLimit-Remaining`/`Reset`은 역할이 다르다:
- `X-RateLimit-Remaining`/`Limit`은 모든 응답에 오는 일반적인 버킷 상태 정보라, 거절당하기 전에 미리 속도를 늦추는 **사전 방어(현재 허용치 추정)**에 적합하다.
- `Retry-After`는 429 응답에만 오는, "거절당한 뒤 정확히 언제 재시도하면 되는지"를 서버가 직접 알려주는 값이다. 서버가 다른 클라이언트의 수요까지 감안해서 판단한 값이라, 우리가 `X-RateLimit-Reset`(토큰 1개 재충전 예상 초)으로 자체 계산하는 것보다 신뢰할 수 있다 — 그래서 **사후 대응(재시도 대기시간 계산)은 `Retry-After` 기준**으로 한다.

## 1. 호가 캐시 TTL 상향

`Config/OrderBookCacheProperties.java` / `application.yaml`의 `orderbook.cache.ttl-seconds`를 2초에서 **30초**로 늘린다 (설정값이므로 필요시 조정 가능).

**왜 필요한가**: 아래 2, 3번으로 종목별 폴링에 우선순위/예산 제한을 걸면, 활성 종목이 많을 때 어떤 종목은 다음 차례가 올 때까지 몇 초에서 십수 초씩 기다릴 수 있다. 지금처럼 TTL이 2초면 그 사이에 캐시가 만료되고, REST/매칭엔진 캐시미스 경로가 "캐시 없음"으로 판단해서 예산 체크 없이 즉시 토스를 또 호출해버린다 — 오히려 초과 호출을 늘리는 역효과가 난다. TTL을 폴링 로테이션의 최악 대기 시간보다 넉넉하게 잡아야, 아직 최신은 아니어도 "유효한 캐시"로 취급되어 불필요한 직접 호출을 막는다.

## 2. 공유 레이트리미터 — 실제 응답 헤더로 한도를 동적으로 갱신

신규 `Client/TossApiRateLimiter.java`: Redis 기반. "이번 초에 몇 번 썼는지" 카운터와 "현재 한도가 얼마인지" 두 가지를 관리한다.

```java
public boolean tryAcquire(String group) {
    if (isCoolingDown(group)) {  // 4번의 next-allowed-at 확인
        return false;
    }
    String countKey = "toss-api:quota:" + group + ":" + Instant.now().getEpochSecond();
    Long count = stringRedisTemplate.opsForValue().increment(countKey);
    if (count != null && count == 1L) {
        stringRedisTemplate.expire(countKey, Duration.ofSeconds(2));
    }
    return count != null && count <= currentLimit(group);
}

private long currentLimit(String group) {
    String value = stringRedisTemplate.opsForValue().get("toss-api:observed-limit:" + group);
    long observedLimit = value != null ? Long.parseLong(value) : defaultLimit(group); // 최초엔 보수적 기본값
    return Math.max(1, (long) (observedLimit * safetyMarginFactor(group))); // 예: 0.8
}

public void recordResponseHeaders(String group, HttpHeaders headers) {
    headers.firstValue("X-RateLimit-Limit").ifPresent(limitValue ->
        stringRedisTemplate.opsForValue().set("toss-api:observed-limit:" + group, limitValue)
    );
}
```

- `X-RateLimit-Limit`은 매 응답(정상/429)마다 그룹별 Redis 키에 최신값으로 덮어쓴다. 하드코딩된 "10" 대신 실제 서버가 알려주는 값을 그대로 신뢰한다.
- 우리가 실제로 소진하는 한도는 관측된 `X-RateLimit-Limit`에 안전마진 비율(예: 0.8)을 곱한 값이다 — 다른 프로세스/인스턴스가 같은 키를 동시에 쓰는 오차를 흡수하기 위함.
- 아직 한 번도 응답을 못 받은 최초 상태(서버 기동 직후)에는 보수적 기본값(예: 5)으로 시작한다.
- Redis 기반이라 서버 인스턴스가 여러 대여도 관측값/카운터를 공유한다.

**적용 위치** — 폴링 스케줄러가 아니라, 세 경로가 전부 거치는 서비스 메서드 진입점에 건다:
- `Service/OrderBookService.fetchCacheAndPublish(symbol)` 맨 앞에서 `tryAcquire("orderbook-price-candle")` 확인
- `Service/PriceService.fetchCacheAndPublish(symbols)` — 마찬가지
- `Service/DailyPriceRangeService.fetchCacheAndPublish(symbol)` — 마찬가지
- (향후) 환율 서비스, `Service/MarketCalendarService` — `market-calendar-exchange-rate` 그룹으로

이렇게 하면 정기 폴링/REST 캐시미스/매칭엔진 캐시미스 중 어디서 호출하든 동일한 예산을 공유한다.

**헤더 기록은 클라이언트 쪽에서**: `TossOrderBookClient`/`TossPriceClient`/`TossCandleClient` 3개(그룹 A)와 `TossMarketCalendarClient`(그룹 B, 이후 환율 클라이언트도 포함)가 각각 응답을 받는 즉시(성공이든 429든) `tossApiRateLimiter.recordResponseHeaders(group, response.headers())`를 호출해서 관측값을 갱신한다.

**예산 소진 + 캐시도 없을 때**: 무리하게 예산을 넘겨서 강행 호출하지 않는다는 원칙은 유지한다. 매칭엔진 경로는 이번 사이클을 건너뛰고 다음 매칭 요청 이벤트(재시도/안전망)에서 다시 시도한다 — 어차피 캐시가 없다는 건 그 종목에 대해 아직 확실한 시세가 없다는 뜻이라 무리해서 체결시키지 않는 게 맞다.

> REST 경로의 응답 방식(그룹 A만 202 대기 응답으로 변경)은 `05-orderbook-rest-pending-response.md`에서 갱신했다 — 토스 API 초당 허용치가 작아서 "새 종목 첫 조회 + 예산 소진"이 생각보다 자주 발생할 수 있다는 점을 반영해, 이 경우를 더 이상 드문 경우로 취급하지 않는다. 장운영정보(그룹 B)는 푸시 채널이 없어 기존 503을 유지한다.

## 3. 폴링 대상 우선순위 로테이션

`Service/OrderBookPollingService.pollActiveOrderBooks()`를 아래처럼 바꾼다.

```text
1. 활성 종목 목록 구성: 미체결 주문 종목 우선, 그다음 WS 구독 종목
2. 목록을 순서대로 순회하며 각 종목마다 refreshAndPublish 시도
3. refreshAndPublish 내부(OrderBookService.fetchCacheAndPublish)에서 예산 초과로 tryAcquire가 false면
   그 종목은 이번 틱에 조용히 건너뜀 (별도 대기열에 넣지 않음)
4. 다음 틱(1초 후)에 같은 우선순위 계산을 다시 하므로, 건너뛴 종목도 자연스럽게 재도전한다
```

새로운 큐/백로그 자료구조는 만들지 않는다 — "이번 틱에 필요한 종목 목록"을 매번 다시 계산하는 것 자체가 자연스러운 재시도 메커니즘이 된다. `Service/PriceService`/`Service/DailyPriceRangePollingService`도 동일한 패턴을 적용한다.

**참고**: 이 구조에서 지연은 "활성 종목 수 ÷ 그룹 예산"에 비례한다. 그룹 예산 자체가 이제 고정값이 아니라 관측된 `X-RateLimit-Limit`을 따라 움직이므로, 토스 쪽에서 한도를 조정하면 지연도 그에 맞춰 자동으로 변한다.

## 4. 실제 429 응답에 대한 재시도 (안전망)

프로액티브 예산 관리를 해도(관측치 반영 지연, 다른 프로세스의 동시 소비 등으로) 429가 올 수 있으므로, 지수 백오프 + full jitter + `Retry-After` 하한 방식을 반영한다.

- `TossOrderBookClient`(및 다른 3개 클라이언트)가 429 응답을 받으면, **먼저 `recordResponseHeaders`로 `X-RateLimit-Limit` 관측값을 갱신**하고, 그다음 `Retry-After` + `random(0, min(maxDelay, baseDelay × 2^retryCount))`로 `nextAllowedAt`을 계산한다. `X-RateLimit-Remaining`/`Reset`은 대기시간 계산에 쓰지 않는다 (이유는 Context 참고).
- `Thread.sleep()` 대신 Redis에 `toss-api:next-allowed-at:{group}` 형태로 기록하고, `TossApiRateLimiter.tryAcquire()`가 이 값도 함께 확인해서 아직 지나지 않았으면 예산이 남아있어도 호출을 막는다 (여러 서버 인스턴스가 동시에 같은 429를 반복해서 맞는 걸 방지).
- 재시도 자체는 각 폴링 스케줄러의 다음 틱이 자연스럽게 담당하므로, 별도의 재시도 루프를 새로 만들 필요는 없다.

## 설정 추가 (`application.yaml`)

```yaml
orderbook:
  cache:
    ttl-seconds: ${ORDERBOOK_CACHE_TTL_SECONDS:30}

toss-invest:
  rate-limit:
    orderbook-price-candle:
      default-limit: ${TOSS_RATE_LIMIT_GROUP_A_DEFAULT:5}   # 최초 응답 받기 전까지의 보수적 기본값
      safety-margin: ${TOSS_RATE_LIMIT_GROUP_A_MARGIN:0.8}  # 관측된 X-RateLimit-Limit에 곱하는 비율
    market-calendar-exchange-rate:
      default-limit: ${TOSS_RATE_LIMIT_GROUP_B_DEFAULT:5}
      safety-margin: ${TOSS_RATE_LIMIT_GROUP_B_MARGIN:0.8}
```

## 확인이 필요한 가정

- 예산 소진 + 캐시 없음일 때 REST는 오류 반환, 매칭엔진은 이번 사이클 스킵으로 정했습니다 — 무리해서 강행 호출하는 대안도 있지만 안전한 쪽을 택했습니다.
- 호가 캐시 TTL 30초는 임의로 잡은 값입니다 — 실제 활성 종목 수 규모를 보고 조정하는 게 맞습니다.
- 안전마진 비율(0.8)과 최초 기본값(5)도 임의로 잡은 값입니다 — 실제 토스 응답에서 관측되는 `X-RateLimit-Limit` 값을 보고 조정하는 게 맞습니다.
