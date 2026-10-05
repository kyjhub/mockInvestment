# REST 조회에서 예산 소진 + 캐시 없음 응답을 503 대신 202 대기 응답으로 변경

## Context

`04-toss-api-rate-limit-management.md`에서는 캐시가 없고 예산도 소진된 REST 조회를 "드문 경우"로 보고 즉시 503 오류로 응답하도록 계획했고, 이미 그대로 구현되어 있다:

- `Client/TossApiQuotaUnavailableException.java`는 그룹 구분 없이 메시지만 담은 단순 `RuntimeException`.
- `OrderBookService.fetchCacheAndPublish` / `PriceService.fetchCacheAndPublish` / `DailyPriceRangeService.fetchCacheAndPublish` / `MarketCalendarService.getUsMarketCalendar`가 `tossApiRateLimiter.tryAcquire(group)`이 false를 반환하면 이 예외를 던진다 (그룹 정보는 넘기지 않음).
- `Controller/GlobalExceptionHandler.java`의 `handleTossApiQuotaUnavailableException`이 그룹 구분 없이 무조건 503 + 메시지로 응답한다.

그런데 토스 API의 초당 허용치가 매우 작아서, 실제로는 "한 번도 조회된 적 없는 새 종목을 하필 예산이 바닥난 순간에 조회"하는 상황이 드물지 않을 수 있다는 지적이 있었다. 그래서 즉시 오류를 반환하는 대신, **요청을 받아들이되 실제 데이터는 이미 있는 WebSocket 푸시 채널로 전달**하는 방식으로 바꾼다. 이번 계획은 04번 계획으로 이미 구현된 코드를 수정하는 것이라, 04번 문서를 따로 안 봐도 이 파일만으로 무엇을 바꾸는지 알 수 있도록 현재 구현 상태를 위에 그대로 옮겨 적었다.

이 변경은 주문 접수(매수/매도)와는 무관하다 — `OrderTradingService.placeOrder()`는 토스 API를 동기 호출하지 않고 매칭 요청 이벤트만 발행하므로, 이 문제 자체가 발생하지 않는다. 이번 변경은 순수하게 호가/현재가/캔들 **조회 REST 엔드포인트**에만 해당한다.

**적용 대상 구분**: 호가/현재가/캔들(그룹 A)은 이미 Redis Pub/Sub + WebSocket 푸시 채널(`/topic/orderbook/{symbol}` 등)이 있으므로 202 pending으로 응답한다. 장운영정보(그룹 B)는 푸시 채널 자체가 없어서 클라이언트가 직접 재시도하는 것 말고는 방법이 없으므로 기존처럼 503을 유지하되, `Retry-After`를 추가해서 "언제 다시 요청하면 되는지" 힌트를 준다 — 오히려 대체 채널이 없는 그룹 B가 이 힌트를 더 필요로 한다.

## 1. `TossApiRateLimiter`에 "지금부터 몇 초 뒤에 가능한지" 계산 추가

`tryAcquire`가 false를 반환하는 이유는 두 가지다.

- **케이스 A (쿨다운 중)**: 실제로 토스 429를 받아서 `next-allowed-at`에 백오프+jitter가 반영된 실제 재개 시각이 저장되어 있는 상태. 이땐 이 값이 진짜 근거 있는 대기시간이다.
- **케이스 B (이번 초 자체 예산 소진)**: 토스에 요청 자체를 보내지 않고 우리 쪽 카운터만으로 선제 차단한 상태. 이땐 토스가 준 `Retry-After`라는 게 애초에 없다 — 그냥 다음 초 윈도우가 열릴 때까지의 시간을 쓴다.

그래서 토스의 원본 `Retry-After`를 그대로 우리 클라이언트에 전달하는 게 아니라, `TossApiRateLimiter`가 이 두 케이스를 구분해서 "지금부터 몇 초 뒤에 다시 시도하면 되는지"를 직접 계산한다. 그룹 A/B 둘 다 이 계산을 그대로 쓴다 — 오히려 그룹 B는 WebSocket 푸시 채널이 없어서 클라이언트가 직접 재시도하는 것 말고는 방법이 없으므로 이 힌트가 더 절실하다.

```java
public long secondsUntilAvailable(String group) {
    Long coolingDownRemainingMillis = coolingDownRemainingMillis(group);
    if (coolingDownRemainingMillis != null) {
        return Math.max(1L, (long) Math.ceil(coolingDownRemainingMillis / 1000.0));
    }
    return QUOTA_TTL.toSeconds(); // 케이스 B: 다음 초 윈도우까지
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
```

`isCoolingDown(group)`도 이 `coolingDownRemainingMillis`를 재사용하도록 정리한다 (중복 로직 제거).

## 2. `TossApiQuotaUnavailableException`에 그룹 + 동적 재시도 시간 추가

```java
public class TossApiQuotaUnavailableException extends RuntimeException {
    private final String group;
    private final long retryAfterSeconds;

    public TossApiQuotaUnavailableException(String group, long retryAfterSeconds, String message) {
        super(message);
        this.group = group;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String group() {
        return group;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
```

`OrderBookService`/`PriceService`/`DailyPriceRangeService`/`MarketCalendarService`의 기존 throw 지점에서 `tossApiRateLimiter.secondsUntilAvailable(group)`을 호출해 그 값과 그룹 상수를 함께 넘긴다.

## 3. `GlobalExceptionHandler`가 그룹에 따라 다른 상태코드 + 공통 `Retry-After`로 응답

```java
@ExceptionHandler(TossApiQuotaUnavailableException.class)
public ResponseEntity<Map<String, String>> handleTossApiQuotaUnavailableException(
    TossApiQuotaUnavailableException exception
) {
    HttpStatus status = TossApiRateLimiter.ORDERBOOK_PRICE_CANDLE_GROUP.equals(exception.group())
        ? HttpStatus.ACCEPTED
        : HttpStatus.SERVICE_UNAVAILABLE;

    Map<String, String> body = TossApiRateLimiter.ORDERBOOK_PRICE_CANDLE_GROUP.equals(exception.group())
        ? Map.of("status", "pending", "message", "잠시 후 실시간 갱신으로 반영됩니다.")
        : Map.of("message", exception.getMessage());

    return ResponseEntity
        .status(status)
        .header(HttpHeaders.RETRY_AFTER, String.valueOf(exception.retryAfterSeconds()))
        .body(body);
}
```

- 그룹 A(호가/현재가/캔들)는 **202 Accepted** + `Retry-After` + `{"status":"pending"}` — "접수됐고 이미 열려있는 WebSocket으로 곧 온다"는 의미.
- 그룹 B(장운영정보)는 **503** + `Retry-After` — WebSocket 푸시가 없으니 "지금은 안 되고, 이 시간 뒤에 네가 직접 다시 요청하라"는 의미. (503에 `Retry-After`를 붙이는 건 HTTP 표준에서도 원래 정석적인 조합이다.)
- 두 그룹 다 고정 상수 대신 `TossApiRateLimiter`가 실시간 상태를 보고 계산한 값을 쓴다.

## 4. 실제 데이터 전달(그룹 A)은 기존 폴링·WebSocket 경로에 맡긴다

컨트롤러/서비스 쪽에 새로운 로직을 추가하지 않는다. REST 요청과 거의 동시에 프론트가 해당 종목의 WebSocket 구독(`/topic/orderbook/{symbol}` 등)을 이미 열게 되므로, `OrderBookSubscriptionRegistry.activeSymbols()`에 자연스럽게 잡혀 다음 폴링 틱(예산이 풀리는 대로)에 갱신되고, 갱신되면 기존 Pub/Sub → WebSocket 경로로 프론트에 실제 데이터가 전달된다. 별도의 "이 종목을 우선 큐에 넣는" 로직은 추가하지 않는다 — REST 요청 시점과 WebSocket 구독 시점이 약간 어긋나도 최대 한 틱(약 1초) 정도만 늦어질 뿐이라 실익보다 복잡도가 더 크다고 판단했다.

그룹 A라도 WebSocket을 쓰지 않는 클라이언트(외부 스크립트 등)는 응답에 실린 `Retry-After`를 보고 직접 재요청하면 된다 — 두 종류의 클라이언트가 같은 응답으로 자연스럽게 처리된다.

## 설정 추가 없음

별도 설정값을 추가하지 않는다. `secondsUntilAvailable`이 `TossApiRateLimiter`가 이미 갖고 있는 `QUOTA_TTL`/`next-allowed-at` 상태를 그대로 활용해서 계산하기 때문이다.

## 확인이 필요한 가정

- 프론트엔드가 202 pending 응답을 받았을 때 에러로 취급하지 않고 WebSocket 값을 기다리도록 처리하는 부분과, 그룹 B의 503 + `Retry-After`를 보고 자동 재시도하는 부분은 이 백엔드 계획 범위 밖입니다 — 프론트 쪽에서 별도로 반영이 필요합니다.
