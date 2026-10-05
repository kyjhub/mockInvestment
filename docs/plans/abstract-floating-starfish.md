# 예외 처리 공백 + 구독 레지스트리 버그 + 종목 단위 즉시 매칭 전환

## Context

이전 라운드(매칭 엔진 안정성 수정)를 검증하면서 발견한 후속 문제들이다. 1/2번은 방금 추가한 주문 취소 기능이 바로 걸리는 구멍이고, 3번은 웹소켓 구독 3개 레지스트리에 동일하게 복제된 버그다. 4번은 지난번에 추가한 재매칭 스케줄러(2초 주기 전체 재발행)의 스트림 오염 리스크를 쿨다운으로 땜질하려다가, 더 근본적으로 "내부 주문 접수는 즉시 매칭되는데 토스 호가 갱신은 매칭 엔진을 전혀 트리거하지 않는다"는 구조적 비대칭을 발견해서, 쿨다운 대신 트리거 자체를 종목 단위 이벤트로 재설계한 결과다.

## 1&2. `GlobalExceptionHandler` 예외 처리 공백 (우선순위 높음)

**핵심 판단**: `IllegalStateException`을 그냥 `GlobalExceptionHandler`에 통째로 잡아서 하나의 상태코드로 매핑하면 안 된다. 이 예외는 지금 두 가지 서로 다른 성격으로 쓰이고 있다:
- `Order.cancel()`에서 "이미 종료된 주문은 취소 불가" — 이건 사용자 잘못(4xx)
- Toss 4개 클라이언트(`TossOrderBookClient`/`TossCandleClient`/`TossMarketCalendarClient`/`TossPriceClient`)의 I/O 실패·시크릿 토큰 미설정, `TokenHashService`의 SHA-256 알고리즘 부재 — 이건 서버/인프라 잘못(5xx)

이 둘을 한 핸들러로 묶으면 인프라 장애도 사용자 탓처럼 400으로 응답하게 되어 오히려 잘못된 신호를 준다. 그래서:

- **`Entity/Order.java`의 `cancel()`이 던지는 예외를 `IllegalStateException`에서 `IllegalArgumentException`으로 바꾼다.** 이 코드베이스는 이미 "보유 수량 부족", "주문 가능 금액 부족" 등 모든 비즈니스 규칙 위반을 `IllegalArgumentException` → 400 한 가지로 통일해서 처리하고 있으므로(`GlobalExceptionHandler`에 이미 핸들러 있음), 취소 실패도 같은 관례를 따르는 게 새 예외 타입/상태코드 체계를 추가하는 것보다 일관적이다.
- **`Controller/GlobalExceptionHandler.java`에 catch-all `@ExceptionHandler(Exception.class)` → 500 핸들러를 추가한다.** 응답 본문에는 내부 메시지/스택트레이스를 노출하지 않는 일반 메시지("서버 오류가 발생했습니다" 등)만 담고, 실제 예외는 서버 로그(`log.error(...)`)로 남긴다. Spring은 가장 구체적인 타입의 핸들러를 우선 매칭하므로, 이 catch-all을 추가해도 기존 `IllegalArgumentException`/`MethodArgumentNotValidException`/`ConstraintViolationException`/`TossOpenApiException` 핸들러의 동작에는 영향이 없다. 이 하나로 Toss 클라이언트들의 `IllegalStateException`, `TokenHashService`의 예외, 그 외 예상 못한 `RuntimeException`(JSON 파싱 실패 등)이 전부 안전하게 500으로 응답되면서 서버 로그에는 원인이 남는다.

## 3. 구독 레지스트리 3개 참조 카운팅 버그 통합 수정 (심각)

`PriceSubscriptionRegistry`/`OrderBookSubscriptionRegistry`/`DailyPriceRangeSubscriptionRegistry`는 확인 결과 로직이 byte-for-byte 동일하고 이름만 다르다. 같은 버그를 3곳에 따로 고치면 그 자체로 또 어긋날 위험이 있으므로, 공통 로직을 하나로 뽑아내는 걸 추천한다.

- 신규 `WebSocket/SymbolSubscriptionRegistry.java` (평범한 클래스, `@Component` 아님 — 직접 빈으로 등록하지 않음):
  - 정방향: `Map<sessionId, Map<subscriptionId, symbol>>`
  - 역방향: `Map<symbol, Set<sessionId:subscriptionId>>` (합성키로 세션 간 충돌 방지)
  - `subscribe(sessionId, subscriptionId, symbol)`: 같은 subscriptionId가 이미 다른 symbol에 매핑되어 있으면(재구독 없이 심볼만 바뀐 경우) 먼저 이전 symbol 쪽 역방향 항목을 제거한 뒤 새 매핑을 등록 — 이렇게 하면 subscriptionId는 항상 최대 1개 symbol에만 연결된다.
  - `unsubscribe`/`disconnect`/`activeSymbols`도 역방향 맵이 `Set`이라 멱등하게 동작 (중복 추가/제거해도 카운트 어긋나지 않음).
- `PriceSubscriptionRegistry`/`OrderBookSubscriptionRegistry`/`DailyPriceRangeSubscriptionRegistry`는 각각 `@Component`가 붙은 빈 서브클래스로 축소:
  ```java
  @Component
  public class PriceSubscriptionRegistry extends SymbolSubscriptionRegistry {
  }
  ```
  클래스 이름과 스프링 빈 타입이 그대로 유지되므로 `*PollingService`, `*SubscriptionEventListener` 등 기존 주입 지점은 전혀 손댈 필요 없다.

(만약 3개를 계속 완전히 독립적으로 유지하고 싶으시면, 같은 수정을 3개 파일에 각각 반복 적용하는 방식도 가능합니다 — 다만 그러면 다음에 또 고칠 일이 생겼을 때 3곳을 다 기억해서 고쳐야 하는 부담이 남습니다.)

## 4. 내부 주문 접수 + 토스 호가 갱신을 모두 즉시 매칭으로 통합 (심각, 설계 변경)

**배경**: 지금은 내부 주문이 들어오면 `orders:submitted` 스트림 + `matchOrder(orderId)`로 즉시 매칭되지만, 토스 호가가 갱신될 때는 프론트용 Pub/Sub만 방송할 뿐 매칭 엔진을 전혀 건드리지 않는다. 이전에 검토했던 "재매칭 스케줄러 + 쿨다운" 방식은 결국 미체결 주문을 다시 스트림에 넣는 방식이라, (1) 조회 주기가 최소 2초라 시세 변화에 즉각 반응하지 못하고 (2) 같은 주문이 반복 재발행되면서 스트림이 오염될 여지가 남는다는 근본적 한계가 있었다. 그래서 "재매칭 쿨다운"을 얹는 대신, 트리거 자체를 다시 설계한다.

**핵심 아이디어**: 매칭 요청을 "주문 하나"가 아니라 "이 종목을 다시 매칭하라"는 **종목 단위 이벤트**로 바꾸고, 내부 주문 접수와 토스 호가 갱신이 똑같이 이 이벤트를 발행하게 만든다.

### 4-1. 기존 주문 단위 스트림을 종목 단위 스트림으로 완전히 대체

`orders:submitted` / `OrderSubmittedEvent` / `OrderSubmittedStreamPublisher` / `MatchingEngineStreamConsumer`의 주문 단위 처리 경로를 폐기하고, 신규 스트림 `symbols:match-requested`로 통합한다. 두 스트림을 동시에 유지하면 매칭 경로가 갈라지므로 완전 대체가 맞다.

- 신규 `Dto/SymbolMatchRequestedEvent.java`: `record SymbolMatchRequestedEvent(String symbol, String reason)` (`reason`은 `ORDER_SUBMITTED`/`ORDER_BOOK_UPDATED`/`SAFETY_NET` 등 관측/로깅용, 매칭 로직 자체에는 영향 없음)
- `Service/OrderSubmittedStreamPublisher.java` → `Service/SymbolMatchRequestedStreamPublisher.java`로 대체 (스트림 키 `symbols:match-requested`)
- `Service/OrderTradingService.java`의 `publishAfterCommit`이 커밋 후 `SymbolMatchRequestedEvent(symbol, "ORDER_SUBMITTED")`를 발행하도록 변경 (더 이상 `orderId`를 실어보내지 않음 — 매칭은 종목 단위이므로 불필요)
- `Service/MatchingEngineStreamConsumer.java`는 그대로 두되(컨슈머 그룹/재시도/DLQ 로직 재사용), 레코드 payload를 `{symbol, reason}`으로 읽고 `matchingEngineTransactionService.matchSymbol(symbol, orderBook, dailyPriceRange)`를 호출하도록 변경.

### 4-2. `MatchingEngineTransactionService`에 종목 단위 매칭 추가

- 신규 `matchSymbol(String symbol, OrderBookResponse orderBook, DailyPriceRangeResponse dailyPriceRange)`:
  1. 해당 심볼의 `PENDING`/`PARTIALLY_FILLED` 주문 id를 가격·시간·수량 우선순위로 정렬해 조회 (기존 `findMatchableSellOrders`/`findMatchableBuyOrders`와 같은 정렬 기준을 재사용하는 조회 하나 추가).
  2. 조회된 각 주문 id에 대해, 기존 `matchOrder(orderId, orderBook, dailyPriceRange)`를 순차 호출 — 같은 호가/일봉범위 스냅샷을 심볼 스윕 전체에서 재사용하되, 주문 하나하나는 여전히 독립된 짧은 트랜잭션으로 처리한다 (락 보유 시간을 짧게 유지하는 기존 원칙 유지).
  3. 중간에 한 주문에서 예외가 나서 전체 스윕이 재시도돼도, 이미 처리된 주문은 상태가 바뀌어 있어 `MATCHABLE_STATUSES` 가드에 걸려 재처리가 스킵되므로 멱등하다 — 부분 실패 추적 로직 불필요.
- 기존 `matchOrder(orderId, orderBook, dailyPriceRange)`는 그대로 유지 (내부 구현 디테일로 재사용).

### 4-3. 토스 호가 갱신 시 매칭 이벤트 발행 + 버전 관리

`Service/OrderBookService.java`의 호가 갱신 경로(`fetchCacheAndPublish`)에 "실제로 값이 바뀌었는지" 비교 로직과 매칭 이벤트 발행을 추가한다.

- 새 호가를 캐싱하기 전에 기존 캐시값과 비교. 실제로 달라졌을 때만:
  - 기존처럼 Pub/Sub 방송 (화면 갱신용, 유실 허용)
  - `symbols:match-requested`에 `{symbol, "ORDER_BOOK_UPDATED"}` 발행 (체결 기회, 유실 불허)
  - Redis `orderbook:version:{symbol}` 카운터를 `INCR`
- `MatchingEngineStreamConsumer`가 심볼 스윕을 시작할 때 현재 버전을 기록해두고, `matchSymbol` 처리가 끝난 시점의 버전과 비교한다. 처리 도중 버전이 또 올라갔다면(스윕 도중 호가가 한 번 더 바뀐 것) 다음 폴링 틱(최대 1초)을 기다리지 않고 즉시 한 번 더 `matchSymbol`을 수행한다.
- 발행 측 중복 억제(`match-request-pending:{symbol}` SETNX 플래그)는 필수는 아니다 — 호가 폴링 자체가 종목당 초당 1회로 이미 상한이 있고 실제 변경 시에만 발행하므로 폭주 여지가 크지 않다. 다만 다중 서버 인스턴스 환경에서 같은 변경을 동시에 감지해 중복 발행하는 걸 줄이고 싶다면 추가해도 무방하다 (중복이 있어도 `matchSymbol`은 멱등하므로 안전성 문제는 없음).

### 4-4. 호가 폴링 대상 확장 — WS 구독 종목 ∪ DB 미체결 주문 종목

`Service/OrderBookPollingService.java`의 폴링 대상을 `orderBookSubscriptionRegistry.activeSymbols()` 하나가 아니라, 기존에 추가해둔 `OrderRepository.findDistinctStockIdsByStatusIn(...)`으로 얻은 "미체결 주문이 있는 종목"과의 합집합으로 확장한다. 사용자가 화면을 닫아도 미체결 주문이 남아있는 종목은 계속 호가를 갱신해야 체결 기회를 놓치지 않는다.

### 4-5. 기존 재매칭 스케줄러는 낮은 빈도의 안전망으로 격하

`Service/PendingOrderRematchScheduler.java`는 더 이상 주 경로가 아니므로, 매 2초 전체 재발행이 아니라 **훨씬 낮은 빈도(예: 10~30초)**로 낮추고, 같은 `symbols:match-requested` 스트림에 `{symbol, "SAFETY_NET"}`로 발행하도록 재사용한다 (레디스 장애 등으로 이벤트가 유실됐을 때만 보정하는 역할). 이 빈도에서는 중복 이벤트가 쌓여 문제될 정도가 아니므로 별도 쿨다운 로직은 필요 없다.

### 참고 — 근본적인 지연 한계

호가 폴링 간격(기본 1초, `orderbook.polling.fixed-delay-ms`) 자체가 "실제 시세 변화를 얼마나 빨리 감지할 수 있는가"의 하한선이다. 이 설계를 다 반영해도 최대 약 1초의 원천 지연은 남는다. 더 줄이려면 폴링 간격을 낮추는 수밖에 없고, 이는 토스 API 호출량과의 트레이드오프이지 설계 문제가 아니다.

## 경미 항목

**(a) `PriceService.tryAcquirePollingLock`의 fail-open 정책 — fail-close로 전환 추천**
Redis 예외 시 현재는 "락 획득 성공"으로 간주해 폴링을 강행하는데, 이러면 Redis 장애 중에 오히려 모든 활성 심볼이 동시에 Toss를 두드리게 된다. 직접 호출(`getPrices` REST 경로)은 캐시 미스 시 이미 Toss로 자연스럽게 폴백하므로 사용자 응답성은 유지되고, 대량 폴링만 잠시 멈추는 게 더 안전하다고 판단해 **Redis 예외 시 락 획득 실패(false)로 처리**하는 방향을 추천합니다. 다른 의도(가용성 우선)가 있으시면 그대로 두셔도 됩니다 — 이 부분은 판단이 갈릴 수 있어 명시적으로 여쭤봅니다.

**(b) 검증 예외 메시지 보존**
`GlobalExceptionHandler`의 `MethodArgumentNotValidException`/`ConstraintViolationException` 핸들러가 실제 필드 오류 대신 항상 "Invalid request"만 반환하는 부분을, 실제 필드별 오류 메시지를 응답 본문에 담도록 수정.

**(c) PriceController vs OrderBook/DailyPriceRange 검증 방식 불일치 — 굳이 통일하지 않는 것을 추천**
전자는 "콤마로 구분된 여러 심볼 + 최대 200개 제한"이라는, bean validation 애너테이션으로 표현하기 애매한 검증이라 서비스 레이어 검증이 자연스럽고, 후자는 단일 심볼 포맷 검증이라 애너테이션이 자연스럽습니다. 메커니즘을 억지로 통일하기보다는 (b)로 양쪽 다 오류 메시지 품질만 맞추는 게 더 실효적이라고 봅니다.

**(d) Toss 4개 클라이언트의 2xx-이지만-JSON파싱실패 케이스 처리 통일**
`TossOrderBookClient`/`TossCandleClient`/`TossMarketCalendarClient`/`TossPriceClient`의 `handleResponse()`에서 `jsonMapper.readValue(...)`가 던지는 `JacksonException`을 다른 실패 경로(I/O 실패 등)와 동일하게 감싸서 일관된 예외 타입으로 던지도록 수정 (4개 파일 동일 패턴 적용).

**(e) `TossPriceClient`의 다중 심볼 `%2C` 인코딩 — 코드 수정 대신 먼저 실제 동작 확인 필요**
이건 Toss 서버가 실제로 URL 인코딩된 쉼표를 올바르게 처리하는지 확인 안 된 상태라, 코드부터 바꾸면 오히려 지금 되던 걸 깨뜨릴 수도 있습니다. 실제 API로 여러 심볼 요청을 날려서 정상 동작하는지 먼저 확인해보시고, 문제가 있을 때만 인코딩 방식을 바꾸는 걸 추천합니다 — 이 항목은 계획에서 코드 변경으로 다루지 않습니다.
