# Paper Trading 현재 구현 현황

## 1. 문서 목적

이 문서는 현재 저장소에 실제로 구현된 기능과 각 기능의 구현 방식을 코드 기준으로 설명한다.

- 기준일: 2026-08-16
- 기준 브랜치의 현재 작업 트리 기준
- 엔티티가 존재하더라도 Controller, Service, Repository 흐름이 아직 없는 기능은 "현재 구현 경계"에서 별도로 구분한다.
- 도메인 모델의 컬럼과 관계에 대한 상세 명세는 `docs/entity-spec.md`를 참고한다.

## 2. 기술 구성

| 영역 | 사용 기술 | 현재 역할 |
| --- | --- | --- |
| 애플리케이션 | Java 21, Spring Boot 3.5 | 단일 Spring Boot 애플리케이션 |
| HTTP API | Spring Web MVC | 인증, 시세 조회, 주문 접수 및 취소 API |
| 인증/인가 | Spring Security, JWT, BCrypt | Stateless 인증과 사용자 상태 확인 |
| 영속성 | Spring Data JPA, PostgreSQL | 사용자, 계좌, 주문, 체결, 보유량, 현금 원장 저장 |
| 실시간 상태 | Spring Data Redis | 시세 캐시, 분산 호출량 제한, 분산 락 |
| 이벤트 처리 | Redis Stream | 종목 단위 비동기 매칭 요청과 재처리 |
| 서버 간 실시간 전파 | Redis Pub/Sub | 여러 애플리케이션 인스턴스 간 시세 갱신 팬아웃 |
| 클라이언트 실시간 전파 | STOMP WebSocket | 종목별 호가, 현재가, 일일 고저가 전송 |
| 외부 시세 | Toss Securities Open API | 장 운영정보, 호가, 현재가, 일봉 조회 |
| 직렬화 | Jackson 2 `ObjectMapper` | Toss 응답 및 Redis 캐시/Pub/Sub 메시지 처리 |
| 보조 코드 | Lombok | 생성자, Getter, Builder 생성 |

애플리케이션 시작 클래스에는 `@SpringBootApplication`과 `@EnableScheduling`이 선언되어 있다. 따라서 컴포넌트 스캔과 함께 시세 폴링, 매칭 Stream 소비, 미체결 주문 안전망 같은 스케줄 작업이 활성화된다.

## 3. 전체 요청 및 데이터 흐름

```text
HTTP / STOMP WebSocket
        |
        v
Controller / Spring Security
        |
        v
Service
  |-- PostgreSQL + JPA
  |     사용자, 계좌, 주문, 체결, 보유량, 현금 원장
  |
  |-- Redis
  |     최신 시세 캐시
  |     Toss API 공유 호출량 제한
  |     폴링 및 종목 매칭 분산 락
  |     시세 Pub/Sub
  |     매칭 요청 Stream, PEL, 재시도 횟수, DLQ
  |
  `-- Toss Securities Open API
        장 운영정보, 호가, 현재가, 일봉
```

관계형 데이터베이스는 주문과 자산 변동의 원본 데이터를 담당한다. Redis는 빠르게 바뀌거나 여러 서버 사이에서 공유해야 하는 운영 상태를 담당한다. 실시간 시세 틱은 관계형 데이터베이스에 계속 적재하지 않고 Redis 최신값 캐시로 유지한다.

## 4. 인증과 보안

### 4.1 제공 API

| Method | Endpoint | 동작 |
| --- | --- | --- |
| `POST` | `/api/v1/auth/signup` | 사용자 생성 후 access/refresh token 발급 |
| `POST` | `/api/v1/auth/login` | 이메일과 비밀번호 검증 후 token 발급 |
| `POST` | `/api/v1/auth/refresh` | refresh token 회전 후 새 token 쌍 발급 |
| `POST` | `/api/v1/auth/logout` | 제출된 refresh token 폐기 |

### 4.2 회원가입

`AuthService.signup()`은 다음 순서로 처리한다.

1. 이메일 중복 여부를 확인한다.
2. 닉네임 중복 여부를 확인한다.
3. 원문 비밀번호를 `BCryptPasswordEncoder`로 해시한다.
4. `Role.USER`, `Status.ACTIVE` 상태의 `User`를 저장한다.
5. JWT access token과 refresh token을 발급한다.
6. refresh token 원문은 응답으로 반환하고, 데이터베이스에는 SHA-256 해시만 저장한다.

현재 회원가입 흐름은 `User`만 생성한다. `Account` 자동 생성과 초기 투자금 지급은 아직 연결되어 있지 않다.

### 4.3 로그인

`AuthService.login()`은 이메일로 사용자를 찾고 다음 조건을 모두 확인한다.

- 사용자 상태가 `ACTIVE`인지 확인한다.
- BCrypt로 입력 비밀번호와 `passwordHash`를 비교한다.

검증에 성공하면 access/refresh token을 새로 발급하고 refresh token 해시를 데이터베이스에 저장한다.

### 4.4 Access token 인증

`JwtAuthenticationFilter`는 모든 HTTP 요청에서 다음 방식으로 인증을 구성한다.

1. `Authorization: Bearer {token}` 헤더를 읽는다.
2. JWT 서명과 만료를 검증한다.
3. `typ` claim이 `access`인지 확인한다.
4. JWT subject의 사용자 ID로 사용자를 다시 조회한다.
5. 데이터베이스의 사용자 상태가 `ACTIVE`인 경우에만 인증 객체를 만든다.
6. 권한은 `ROLE_{User.role}` 형식으로 등록한다.

JWT 안의 사용자 정보만 신뢰하지 않고 매 요청마다 현재 사용자 상태를 데이터베이스에서 확인한다.

### 4.5 Refresh token 회전

`AuthService.refresh()`는 다음 조건을 검증한다.

- JWT 서명과 만료가 유효하다.
- `typ` claim이 `refresh`이다.
- SHA-256 해시가 `refresh_tokens` 테이블에 존재한다.
- 저장된 token이 폐기되지 않았고 만료되지 않았다.
- token의 사용자 ID에 해당하는 사용자가 현재 `ACTIVE`이다.

성공하면 사용한 refresh token의 `revokedAt`을 기록하고 새 token 쌍을 발급한다. 같은 refresh token의 재사용은 차단된다.

### 4.6 보안 경로 정책

현재 공개 경로는 다음과 같다.

- `/api/v1/auth/**`
- `/ws`, `/ws/**`
- `/actuator/health`

그 밖의 HTTP 요청은 인증이 필요하다. 세션은 생성하지 않으며 CSRF는 비활성화되어 있다.

WebSocket endpoint는 인증 없이 연결할 수 있고 허용 origin 패턴은 `*`이다. 현재 구현상 시세 topic도 공개 연결을 통해 구독할 수 있다.

## 5. Toss Securities API 연동

### 5.1 공통 클라이언트 방식

외부 API별로 Java `HttpClient` 기반 클라이언트를 사용한다.

- `TossMarketCalendarClient`
- `TossOrderBookClient`
- `TossPriceClient`
- `TossCandleClient`

공통 처리 방식은 다음과 같다.

1. 설정된 secret token이 비어 있지 않은지 확인한다.
2. `Authorization: Bearer {TOSS_INVEST_SECRET_TOKEN}` 헤더를 구성한다.
3. 연결과 요청 timeout을 5초로 적용한다.
4. 응답의 `X-RateLimit-Limit`을 공유 레이트 리미터에 기록한다.
5. 2xx 응답은 DTO record로 역직렬화한다.
6. 429 응답은 Redis에 재시도 상태와 다음 허용 시각을 기록한다.
7. 오류 응답은 `TossOpenApiException`으로 변환해 상태코드, request ID, 오류 code와 message를 보존한다.

서비스가 외부 API 호출 직전에 Redis 공유 예산을 획득하며, 각 HTTP 클라이언트는 실제 응답을 이용해 관측 한도와 429 상태를 갱신한다.

### 5.2 공유 호출량 제한

`TossApiRateLimiter`는 애플리케이션 인스턴스별 메모리가 아닌 Redis로 호출 예산을 공유한다.

호출 그룹은 두 개다.

| 그룹 | 포함 API |
| --- | --- |
| `orderbook-price-candle` | 호가, 현재가, 캔들 |
| `market-calendar-exchange-rate` | 장 운영정보, 향후 환율 API |

사용하는 주요 Redis key는 다음과 같다.

```text
toss-api:quota:{group}:{epochSecond}
toss-api:observed-limit:{group}
toss-api:retry-count:{group}
toss-api:next-allowed-at:{group}
```

#### 로컬 예산 획득

- Lua script로 현재 초의 카운터를 `INCR`하고 첫 생성 시 2초 TTL을 설정한다.
- 관측된 한도가 있으면 해당 값을 사용하고, 없으면 설정의 기본 한도를 사용한다.
- 실제 사용 한도는 `floor(observedLimit * safetyMargin)`이며 최소 1이다.
- 기본 한도는 그룹별 5, 기본 safety margin은 0.8이다.
- Redis 접근이 실패하면 외부 요청을 보내지 않는 fail-close 정책을 사용한다.

#### 429 처리

- 그룹별 재시도 횟수를 Redis에서 증가시킨다.
- 1초부터 최대 30초까지 지수 백오프를 계산한다.
- 백오프 범위 안에서 full jitter를 더한다.
- 공급자의 `Retry-After`와 jitter를 합쳐 `next-allowed-at`을 계산한다.
- 여러 인스턴스가 동시에 기록할 때 Lua script로 더 늦은 시각을 유지한다.
- 처리 thread에서 `sleep`하거나 즉시 반복 요청하지 않는다.

#### 내부 API 응답 정책

캐시가 없고 공유 API 예산도 없을 때 다음과 같이 응답한다.

- 호가/현재가/캔들 그룹: HTTP 202, `Retry-After`, `status=pending`
- 장 운영정보 그룹: HTTP 503, `Retry-After`

그룹 A는 기존 WebSocket 폴링 경로를 통해 나중에 실제 값이 전달될 수 있으므로 pending 응답을 사용한다. WebSocket을 사용하지 않는 클라이언트는 `Retry-After` 이후 REST 요청을 다시 할 수 있다.

## 6. 미국 장 운영정보

### 6.1 API

```text
GET /api/v1/market-calendar/US
GET /api/v1/market-calendar/US?date={yyyy-MM-dd}
```

### 6.2 구현 방식

`MarketCalendarService`는 read-through Redis cache 방식으로 구현되어 있다.

1. 요청 date가 없으면 `Asia/Seoul` 기준 현재 날짜를 선택한다.
2. `market-calendar:US:{date}` key로 Redis를 조회한다.
3. 캐시가 있으면 역직렬화하여 즉시 반환한다.
4. 캐시가 없으면 `market-calendar-exchange-rate` 예산을 획득한다.
5. Toss 장 운영정보 API를 호출한다.
6. 응답을 Redis에 저장하고 반환한다.

기본 캐시 TTL은 12시간이다. 캐시 읽기 또는 쓰기 실패는 무시하지만, 호출량 제한을 위한 Redis 상태를 사용할 수 없으면 Toss 호출은 보내지 않는다.

장 운영정보는 관계형 데이터베이스에 저장하지 않는다.

## 7. 실시간 호가

### 7.1 API와 WebSocket

```text
GET /api/v1/orderbook?symbol={symbol}
SUBSCRIBE /topic/orderbook/{symbol}
```

심볼은 `^[A-Za-z0-9.\-]+$` 형식으로 제한한다.

### 7.2 REST 조회

`OrderBookService.getOrderBook()`은 다음 순서로 동작한다.

1. `orderbook:{symbol}` Redis cache를 읽는다.
2. 값이 있으면 반환한다.
3. 값이 없으면 Toss API 공유 예산을 획득한다.
4. Toss 호가 API를 호출한다.
5. 최신 응답을 Redis에 저장한다.
6. 이전 값과 asks, bids, currency를 비교한다.
7. 실제 호가가 달라졌다면 Pub/Sub 발행과 매칭 트리거 등록을 수행한다.

기본 cache TTL은 30초다.

응답에는 실제 조회 시각을 담은 `receivedAt` 필드가 포함된다. 이 값은 매칭 엔진이 호가 신선도를 판단하는 데 사용된다(§13.1 "호가 신선도 보장" 참고).

### 7.3 호가 변경 이벤트

호가가 변경되면 다음 세 동작이 연결된다.

```text
orderbook:updates Pub/Sub
        `--> /topic/orderbook/{symbol}

orderbook:dirty SADD {symbol}
        `--> dirty drain scheduler --> 종목 매칭
```

Pub/Sub는 화면 갱신용이라 모든 변경을 그대로 전달한다. 매칭 트리거는 성격이 달라 Redis Set에 모은다 —
필요한 정보는 "이 종목을 봐야 한다"는 사실 하나뿐이라 횟수를 보존할 이유가 없기 때문이다.
WebSocket 푸시는 종목당 초당 7회 안팎이라 Stream(append-only)에 넣으면 소비 능력을 넘겨 백로그가 쌓이지만,
Set은 같은 종목을 한 번만 담으므로 저장 크기가 푸시 횟수에 비례해 늘지 않는다.

`ORDER_SUBMITTED`와 `SAFETY_NET`은 유실되면 주문이 지연되므로 at-least-once와 PEL 복구가 필요하고,
그래서 계속 `symbols:match-requested` Stream을 쓴다.

### 7.4 호가 폴링

`OrderBookPollingService`는 두 종목 그룹을 서로 다른 주기의 `@Scheduled` 메서드로 폴링한다.

1. `pollPendingOrderSymbols()` — `PENDING`, `PARTIALLY_FILLED` 주문이 존재하는 종목. 기본 1초 fixed delay(`orderbook.polling.fixed-delay-ms`).
2. `pollIdleSubscriptionSymbols()` — WebSocket에서 호가를 구독 중이지만 미체결 주문 종목에는 포함되지 않은 종목. 기본 20초 fixed delay(`orderbook.polling.idle-fixed-delay-ms`).

미체결 주문 종목의 호가는 매칭 엔진이 신선한 체결 기준을 확보하는 데 직접 쓰이므로 짧은 주기를 유지한다. 단순히 화면만 보고 있는 구독 종목은 몇 분 단위로 갱신돼도 되는 지연 시세로 취급해 주기를 크게 늘리고, 그만큼 Toss API 공유 예산을 아낀다. 각 그룹 안에서는 시작 offset을 회전해 특정 종목이 항상 마지막에 처리되지 않도록 한다.

다중 서버의 중복 폴링을 줄이기 위해 `orderbook:poll-lock:{symbol}` key에 기본 900ms TTL의 짧은 락을 사용한다.

## 8. 실시간 현재가

### 8.1 API와 WebSocket

```text
GET /api/v1/prices?symbols=AAPL,MSFT
SUBSCRIBE /topic/prices/{symbol}
```

직접 REST 조회는 1개 이상, 최대 200개의 중복 제거된 심볼을 받는다.

### 8.2 구현 방식

`PriceService.getPrices()`는 심볼별 read-through cache를 사용한다.

1. 입력 문자열의 공백을 제거하고 중복 심볼을 제거한다.
2. `price:{symbol}` key로 각 심볼의 cache를 확인한다.
3. 누락된 심볼만 모아 한 번의 Toss 현재가 API로 조회한다.
4. 응답을 심볼별 cache에 저장한다.
5. 각 가격을 `price:updates` Pub/Sub로 발행한다.
6. 가능한 결과를 요청 심볼 순서로 정렬해 반환한다.

기본 현재가 cache TTL은 2초다.

### 8.3 폴링

`PricePollingService`는 현재가 WebSocket 구독이 있는 심볼만 기본 1초 간격으로 폴링한다.

- 심볼 순서를 매 tick 회전한다.
- 최대 200개씩 묶어 Toss API 제약에 맞게 batch 호출한다.
- `price:poll-lock:{symbol}`로 다중 인스턴스 중복 폴링을 줄인다.
- 폴링 락을 위한 Redis 접근 실패 시 해당 폴링을 중단하는 fail-close 정책을 사용한다.

## 9. 일일 고가/저가

### 9.1 API와 WebSocket

```text
GET /api/v1/daily-price-range?symbol={symbol}
SUBSCRIBE /topic/daily-price-range/{symbol}
```

### 9.2 데이터 생성 방식

일일 고가/저가는 별도 관계형 엔티티가 아니라 Toss의 최신 일봉에서 파생한다.

```text
GET /api/v1/candles
  ?symbol={symbol}
  &interval=1d
  &count=1
  &adjusted=true
```

`DailyPriceRangeService`는 `daily-price-range:{symbol}` Redis cache를 먼저 읽고, cache miss일 때만 Toss API를 호출한다. 기본 TTL은 5초다.

### 9.3 체결가 반영

매칭 엔진에서 체결이 발생하면 `updateWithExecutionPrice()`를 호출한다.

- 체결가가 현재 일일 고가보다 높으면 고가를 갱신한다.
- 체결가가 현재 일일 저가보다 낮으면 저가를 갱신한다.
- 값이 바뀐 경우 cache를 다시 저장하고 Pub/Sub로 발행한다.

시장가 주문이 일부만 체결되고 잔량이 남으면 다음 대기 가격을 정하는 데도 사용한다.

- 시장가 매수 잔량: 현재 일일 고가를 `orderPrice`로 설정
- 시장가 매도 잔량: 현재 일일 저가를 `orderPrice`로 설정

### 9.4 폴링

일일 고저가 WebSocket 구독이 있는 심볼을 기본 1초 간격으로 폴링한다. 심볼 순서를 회전하며 `daily-price-range:poll-lock:{symbol}`로 중복 폴링을 줄인다.

## 10. WebSocket 구독 추적과 팬아웃

### 10.1 STOMP 구성

- handshake endpoint: `/ws`
- simple broker prefix: `/topic`
- application destination prefix: `/app`

서버가 받는 application message handler는 현재 없으며, WebSocket은 서버에서 클라이언트로 시세를 전송하는 용도로 사용한다.

### 10.2 구독 레지스트리

세 가지 시세 유형은 `SymbolSubscriptionRegistry` 공통 구현을 사용한다.

- `PriceSubscriptionRegistry`
- `OrderBookSubscriptionRegistry`
- `DailyPriceRangeSubscriptionRegistry`

레지스트리는 두 방향의 map을 함께 관리한다.

```text
Map<sessionId, Map<subscriptionId, symbol>>
Map<symbol, Set<sessionId:subscriptionId>>
```

이 구조로 다음 상황을 처리한다.

- 같은 세션에서 동일 심볼을 여러 subscription ID로 구독
- 동일 subscription ID가 다른 심볼로 이동
- 개별 unsubscribe
- 세션 disconnect 시 해당 세션의 전체 구독 제거
- 중복 subscribe/unsubscribe의 멱등 처리

`activeSymbols()`는 폴링 서비스가 사용할 방어적이고 수정 불가능한 snapshot을 반환한다.

### 10.3 Redis Pub/Sub에서 WebSocket까지

각 인스턴스의 Redis listener가 다음 channel을 구독한다.

| Redis channel | WebSocket topic |
| --- | --- |
| `orderbook:updates` | `/topic/orderbook/{symbol}` |
| `price:updates` | `/topic/prices/{symbol}` |
| `daily-price-range:updates` | `/topic/daily-price-range/{symbol}` |

Redis 메시지를 DTO로 역직렬화한 뒤 `SimpMessagingTemplate`로 해당 topic에 전송한다. 이를 통해 어느 서버 인스턴스가 Toss 데이터를 조회했는지와 무관하게 모든 인스턴스가 자신에게 연결된 WebSocket 클라이언트로 전달할 수 있다.

## 11. 주문 접수와 취소

### 11.1 API

| Method | Endpoint | 동작 |
| --- | --- | --- |
| `POST` | `/api/v1/orders` | 주문 저장 후 비동기 매칭 요청 |
| `DELETE` | `/api/v1/orders/{orderId}` | 미체결 또는 부분 체결 주문 취소 |

두 API 모두 JWT 인증 사용자를 `@AuthenticationPrincipal User`로 전달받는다.

### 11.2 주문 접수

`OrderTradingService.placeOrder()`은 하나의 DB transaction에서 다음을 수행한다.

1. 인증 사용자가 존재하는지 확인한다.
2. 지정가 주문은 가격이 필수인지 확인한다.
3. 시장가 주문에는 가격이 없는지 확인한다.
4. 사용자 ID로 계좌를 조회한다.
5. `clientOrderId`가 있으면 `(account_id, client_order_id)`로 기존 주문을 조회한다.
6. 기존 주문이 있으면 새 주문을 만들지 않고 기존 주문과 체결 이력을 반환한다.
7. 심볼로 종목을 조회한다.
8. `PENDING`, `filledQuantity=0`, `remainingQuantity=orderQuantity` 상태의 주문을 저장한다.
9. DB commit 후 `symbols:match-requested` Stream에 이벤트를 발행한다.

Stream 이벤트는 다음 값을 가진다.

```text
symbol={stock.symbol}
reason=ORDER_SUBMITTED
```

DB transaction 안에서 먼저 Redis 이벤트를 발행하지 않고 `afterCommit` callback을 사용하므로 rollback된 주문이 매칭되는 문제를 피한다.

### 11.3 주문 멱등성

`clientOrderId`는 선택 값이다. 값이 제공되면 계좌 안에서 유일해야 한다.

- 같은 `(account, clientOrderId)` 요청은 기존 주문을 반환한다.
- 응답에는 기존 주문의 체결 내역도 ID 순서로 포함한다.
- 값이 없거나 공백이면 `null`로 저장한다.

### 11.4 주문 취소

`OrderTradingService.cancelOrder()`은 다음 방식으로 동시성을 제어한다.

1. `findByIdForUpdate()`로 주문 row에 pessimistic write lock을 획득한다.
2. 인증 사용자가 주문 계좌의 소유자인지 확인한다.
3. 상태가 `PENDING` 또는 `PARTIALLY_FILLED`인지 확인한다.
4. `CANCELED` 상태와 `canceledAt`을 기록한다.
5. 이미 생성된 체결과 체결 수량은 유지한다.

매칭 엔진도 동일 주문 row를 같은 종류의 락으로 읽기 때문에 취소와 체결이 직렬화된다.

## 12. 비동기 매칭 요청 처리

### 12.1 Redis Stream

주요 Stream과 보조 key는 다음과 같다.

```text
symbols:match-requested
symbols:match-requested:retry-counts
symbols:match-requested:dlq
```

Publisher는 `MAXLEN` approximate trimming을 적용한다. 기본 source Stream 최대 길이는 1,000,000이고 DLQ 최대 길이는 100,000이다.

### 12.2 Consumer Group 초기화

`MatchingEngineStreamConsumer`는 시작할 때 다음을 수행한다.

1. source Stream이 없으면 `type=bootstrap` 레코드를 하나 추가한다.
2. `matching-engine` consumer group을 `0-0` offset으로 생성한다.
3. 이미 group이 존재하는 등의 `DataAccessException`은 무시한다.

기본 consumer 이름은 `${spring.application.name}-${random.uuid}`이므로 여러 서버 인스턴스가 같은 consumer group을 안전하게 공유할 수 있다.

### 12.3 신규 레코드 소비

기본 100ms fixed delay로 최대 10개의 신규 레코드를 읽는다. 각 레코드는 다음 순서로 처리한다.

1. bootstrap 레코드처럼 `symbol`이 없으면 ACK한다.
2. symbol payload를 검증한다.
3. `reason`이 `ORDER_BOOK_UPDATED`면 매칭하지 않고 dirty set에 넘긴 뒤 ACK한다.
4. 그 외에는 `SymbolMatchingProcessor`가 `order-match-lock:{symbol}` 분산 락을 획득한다.
5. 종목 전체를 한 번 매칭한다.
6. 성공하거나 예산이 부족하면 ACK하고 재시도 횟수를 제거한다. 락 경합이면 ACK하지 않고 dirty set에 등록한다.
7. 마지막에 token 일치 Lua script로 종목 락을 해제한다.

종목 락 기본 TTL은 15초다. 락 token이 현재 Redis 값과 일치하는 경우에만 삭제하므로, 만료 후 다른 작업이 획득한 락을 이전 작업이 삭제하지 않는다.

### 12.4 매칭 실행 경로

종목 락 획득부터 해제까지는 `SymbolMatchingProcessor`가 담당하고, Stream 컨슈머와 dirty drain 스케줄러가
같은 경로를 공유한다. 락이 이 클래스 안에 있으므로 `matchSymbol()`을 직접 호출하면 안 된다 —
락 없이 매칭하면 다중 인스턴스에서 중복 체결이 발생한다.

결과는 예외가 아니라 값으로 돌려주고 호출자가 다르게 처리한다.

| 결과 | Stream consumer | Dirty drainer |
|---|---|---|
| `SUCCESS` | ACK | 종료 |
| `QUOTA_UNAVAILABLE` | ACK + 재시도 횟수 제거 | 재등록하지 않음 (hot loop 방지) |
| `LOCK_BUSY` | ACK 안 함(PEL 유지) + dirty set 등록 | dirty set 재등록 |
| 예외 전파 | 재시도 → DLQ | 로그 + 카운터 |

이전에는 호가 version(`orderbook:version:{symbol}`)이 안정될 때까지 매칭을 반복하는 loop가 있었으나 제거했다.
호가가 바뀌면 그 변경이 스스로 다음 트리거를 만들므로 중복이었고, 처리 시간이 시장 변동성에 비례해 늘어나
가장 바쁜 순간에 종목 락을 가장 오래 붙잡는 구조였다.

### 12.5 API 예산 부족 처리

호가나 일일 고저가 cache가 없고 Toss API 예산도 없으면 `TossApiQuotaUnavailableException`이 발생한다. 이 경우 현재 Stream 레코드는 ACK한다.

다음 기회는 다음 중 하나가 만든 새 종목 이벤트로 제공된다.

- 이후 호가 갱신
- 새 주문 접수
- 30초 안전망 스케줄러

### 12.6 Pending Entry 복구

기본 1초마다 consumer group의 PEL을 조회한다.

1. 최대 20개의 pending message를 조회한다.
2. 기본 5초 이상 idle 상태인 record ID를 고른다.
3. `XCLAIM`으로 현재 consumer에게 소유권을 옮긴다.
4. 신규 레코드와 동일한 처리 경로를 다시 수행한다.

종목 락 경합이 발생하면 레코드를 ACK하지 않으므로 PEL에 남고 이 복구 경로로 다시 처리된다.

### 12.7 재시도와 DLQ

일반 처리 예외는 Redis hash에서 record별 재시도 횟수를 증가시킨다.

- 기본 최대 재시도 횟수: 5
- 최대 횟수 미만: ACK하지 않고 PEL에 유지
- 최대 횟수 도달: DLQ에 실패 정보를 기록한 뒤 source 레코드를 ACK

DLQ에는 다음 정보가 포함된다.

- source Stream과 record ID
- consumer group과 consumer 이름
- 재시도 횟수와 실패 시각
- 예외 class와 message
- 원본 payload

현재 DLQ 조회, 재실행, 삭제를 위한 관리 API는 없다.

### 12.8 안전망 재매칭

`PendingOrderRematchScheduler`는 기본 30초마다 미체결 주문이 있는 종목을 조회하고 다음 이벤트를 발행한다.

```text
symbol={stock.symbol}
reason=SAFETY_NET
```

호가 변경 이벤트나 주문 접수 후 이벤트 발행이 유실된 경우를 보완하는 낮은 빈도의 복구 경로다.

## 13. 매칭 알고리즘

### 13.1 종목 스윕

`MatchingEngineTransactionService.matchSymbol()`은 해당 심볼의 `PENDING`, `PARTIALLY_FILLED` 주문 ID를 조회한다.

조회 우선순위는 다음과 같다.

```text
BUY 주문 그룹 우선, SELL 주문 그룹 다음

BUY:
1. 높은 주문가격
2. 빠른 submittedAt
3. 큰 remainingQuantity

SELL:
1. 낮은 주문가격
2. 빠른 submittedAt
3. 큰 remainingQuantity
```

각 주문은 `TransactionTemplate`을 사용해 별도의 짧은 DB transaction으로 처리한다. 종목 전체를 하나의 큰 transaction으로 묶지 않으므로 한 주문 처리 동안의 row lock 유지 시간을 줄인다.

#### 호가 신선도 보장

각 주문을 실제로 매칭하기 전에, 그 주문의 `submittedAt` 이후에 조회된 호가만 체결에 사용하도록 강제한다.

```text
for (id, submittedAt) in 매칭대상목록:
    orderBook = orderBookService.getOrderBookNoOlderThan(symbol, submittedAt)
    matchOrder(id, orderBook, dailyPriceRange)   # pessimistic lock은 이 안에서 걸린다
```

`OrderBookService.getOrderBookNoOlderThan()`은 Redis 캐시의 `receivedAt`이 주문의 `submittedAt` 이상이면 캐시를 그대로 반환하고, 그렇지 않으면 그 자리에서 Toss를 실제로 호출해 캐시를 갱신한다. 이 호출은 반드시 해당 주문의 pessimistic lock을 잡기 전에, `TransactionTemplate` 바깥에서 이뤄진다. 그래서 Toss 응답을 기다리는 동안 주문 row가 잠겨 있지 않다.

이 규칙 덕분에 "주문을 넣기 전에 이미 조회돼 있던, 지나간 호가로 체결되는" 상황을 구조적으로 막는다. 반대로 체결에 필요한 물량이 부족해 주문이 부분 체결로 남는 경우에는 그 자리에서 추가로 재조회하지 않는다. `PENDING`/`PARTIALLY_FILLED` 주문이 있는 종목은 §7.4의 1초 폴링이 계속 돌고 있으므로, 호가가 실제로 바뀌면 `ORDER_BOOK_UPDATED` 이벤트로 다음 스윕이 자연스럽게 트리거된다.

내부 주문끼리의 체결(§13.4)은 Toss 호가를 전혀 참조하지 않으므로 이 신선도 규칙의 적용 대상이 아니다.

### 13.2 주문 row와 계좌 row 잠금

개별 주문 처리 시 다음 row를 pessimistic write lock으로 읽는다.

- 현재 처리할 주문
- 체결에 참여하는 계좌
- 해당 계좌와 종목의 보유 row
- 내부 반대 주문 후보

이미 취소 또는 체결 완료된 주문은 row lock 획득 후 상태를 다시 확인하고 바로 반환한다.

### 13.3 내부 주문과 외부 호가 비교

매수 주문은 다음 후보를 비교한다.

- 내부 DB의 가장 낮은 매도 지정가
- Toss 호가의 가장 낮은 ask

두 값 중 더 낮은 가격을 우선 사용한다. 가격이 같으면 내부 주문을 선택한다.

매도 주문은 다음 후보를 비교한다.

- 내부 DB의 가장 높은 매수 지정가
- Toss 호가의 가장 높은 bid

두 값 중 더 높은 가격을 우선 사용한다. 가격이 같으면 내부 주문을 선택한다.

지정가 주문은 주문가격 조건을 만족하는 내부 주문과 외부 호가만 후보로 사용한다. 시장가 주문의 최초 `orderPrice`는 `null`이므로 가격 제한 없이 후보를 선택한다.

### 13.4 내부 주문 체결

내부 매수자와 내부 매도자가 체결될 때 한 transaction에서 다음을 수행한다.

1. 매수자와 매도자 계좌에 write lock을 건다.
2. 매수자와 매도자의 보유 row를 조회하거나 생성한다.
3. 매도자의 보유 수량을 확인한다.
4. 매수자의 현금 잔고를 확인한다.
5. 매도 보유량에서 원가를 차감한다.
6. 매수자 현금을 차감하고 매도자 현금을 증가시킨다.
7. 매도자의 실현손익을 증가시킨다.
8. 매수자의 보유 수량과 평균단가를 갱신한다.
9. 양쪽 주문의 체결/잔여 수량과 상태를 갱신한다.
10. 매수와 매도 각각의 `Execution`을 저장한다.
11. 양쪽 계좌 각각에 `CashTransaction`을 저장한다.

### 13.5 Toss 외부 호가 체결

외부 ask에 매수 주문이 체결되면 다음을 수행한다.

- 매수자 현금 차감
- 매수 보유량 및 평균단가 갱신
- 매수 주문 수량과 상태 갱신
- `Execution` 저장
- 음수 `BUY` 현금 원장 저장

외부 bid에 매도 주문이 체결되면 다음을 수행한다.

- 보유량 차감 및 원가 계산
- 매도자 현금 증가
- 실현손익 갱신
- 매도 주문 수량과 상태 갱신
- `Execution` 저장
- 양수 `SELL` 현금 원장 저장

### 13.6 보유 평균단가와 실현손익

매수 시:

```text
purchaseAmount = executionPrice * quantity
newTotalPurchaseAmount = oldTotalPurchaseAmount + purchaseAmount
newAveragePrice = newTotalPurchaseAmount / newQuantity
```

매도 시:

```text
costBasis = averagePrice * sellQuantity
realizedProfit += executionAmount - costBasis
```

수량이 0이 되면 평균단가와 총매입금액을 0으로 초기화한다.

금액은 소수점 둘째 자리, 가격은 소수점 넷째 자리 기준으로 반올림한다.

### 13.7 수수료와 세금

`CommissionCalculator` 전략 interface가 있으며 현재 구현체는 `ZeroCommissionCalculator`다.

- 수수료: 0
- 세금: 0

체결 row에는 계산 결과를 기록한다. 실제 수수료 정책이 결정되면 전략 구현체를 교체할 수 있도록 매칭 엔진과 분리되어 있다.

## 14. 현재 영속 데이터 모델

### 14.1 구현된 엔티티

| 엔티티 | 목적 |
| --- | --- |
| `User` | 사용자 인증 정보와 상태 |
| `Account` | 현금 잔고, 초기금, 총자산, 라운드, 실현손익 |
| `AccountFundingRequest` | 투자금 신청과 라운드별 성과 스냅샷 |
| `AccountReset` | 누적 성과 초기화 이벤트 |
| `Stock` | 종목 마스터 |
| `StockKoreanMarketDetail` | 국내 종목 전용 상세 정보 |
| `Order` | 매수/매도 주문과 체결 상태 |
| `Execution` | 주문별 개별 체결 |
| `Holding` | 계좌와 종목별 현재 보유 상태 |
| `CashTransaction` | 현금 잔고 변경 원장 |
| `StockPrice` | 선택적으로 저장할 가격 snapshot |
| `DailyAccountSnapshot` | 일별 계좌 성과 snapshot |
| `LeaderboardRanking` | 배치로 생성하는 materialized 랭킹 |
| `RefreshToken` | 해시된 refresh token과 폐기 상태 |
| `ExchangeRate` | 환율 응답 저장 모델 |

### 14.2 현재 Repository가 있는 엔티티

실제 서비스 흐름에 사용되는 Repository는 다음 8개다.

- `UserRepository`
- `RefreshTokenRepository`
- `AccountRepository`
- `StockRepository`
- `OrderRepository`
- `ExecutionRepository`
- `HoldingRepository`
- `CashTransactionRepository`

Funding request, reset, snapshot, leaderboard, exchange rate 등은 엔티티만 있으며 현재 Repository와 기능 흐름은 구현되어 있지 않다.

### 14.3 현금 원장

체결로 현금이 변경될 때 `CashTransaction`을 append한다.

- 매수: 음수 금액
- 매도: 양수 금액
- `balanceAfter`에 변경 직후 계좌 잔액 기록
- 관련 `Order`와 `Execution` 참조 기록

현재 구현된 주문 체결 경로에서는 `BUY`, `SELL` 유형을 사용한다. 초기 입금, 수수료, 세금, 조정 유형은 enum과 모델에는 있지만 실제 서비스 흐름은 아직 없다.

## 15. 예외 처리

`GlobalExceptionHandler`가 공통 API 오류 형식을 제공한다.

| 예외 | HTTP 응답 |
| --- | --- |
| `IllegalArgumentException` | 400, 예외 message |
| DTO bean validation 실패 | 400, 필드별 오류 message |
| method parameter 제약 위반 | 400, property path별 오류 message |
| `TossOpenApiException` | Toss가 반환한 상태코드와 오류 정보 |
| 그룹 A quota 부족 | 202, pending body와 `Retry-After` |
| 그룹 B quota 부족 | 503, 오류 body와 `Retry-After` |
| 그 밖의 예외 | 500, 일반화된 한국어 message |

예상하지 못한 예외의 stack trace는 서버 log에 남기고 HTTP 응답에는 내부 정보를 노출하지 않는다.

Redis cache, Pub/Sub, WebSocket subscriber 처리의 일부 오류는 실시간 부가 경로의 실패가 주 요청을 깨뜨리지 않도록 의도적으로 무시한다. 반면 Toss API 호출량 제한 상태를 확인하지 못하는 경우에는 외부 API 호출을 막는다.

## 16. 주요 설정과 기본값

| 설정 | 기본값 | 설명 |
| --- | --- | --- |
| `security.jwt.access-token-expiration-minutes` | 30 | Access token 만료 |
| `security.jwt.refresh-token-expiration-days` | 14 | Refresh token 만료 |
| `market-calendar.cache.ttl-hours` | 12 | 장 운영정보 cache TTL |
| `orderbook.cache.ttl-seconds` | 30 | 호가 cache TTL |
| `orderbook.polling.fixed-delay-ms` | 1000 | 미체결 주문 종목 호가 polling 간격 |
| `orderbook.polling.idle-fixed-delay-ms` | 20000 | 구독-only(미체결 주문 없는) 종목 호가 polling 간격 |
| `orderbook.polling.lock-ttl-ms` | 900 | 호가 polling lock TTL |
| `price.cache.ttl-seconds` | 2 | 현재가 cache TTL |
| `price.polling.fixed-delay-ms` | 1000 | 현재가 polling 간격 |
| `price.polling.lock-ttl-ms` | 900 | 현재가 polling lock TTL |
| `daily-price-range.cache.ttl-seconds` | 5 | 일일 고저가 cache TTL |
| `daily-price-range.polling.fixed-delay-ms` | 1000 | 일일 고저가 polling 간격 |
| `daily-price-range.polling.lock-ttl-ms` | 900 | 일일 고저가 polling lock TTL |
| `matching-engine.polling.fixed-delay-ms` | 100 | 신규 Stream 소비 간격 |
| `matching-engine.rematch.fixed-delay-ms` | 30000 | 안전망 이벤트 간격 |
| `matching-engine.lock.ttl-ms` | 15000 | 종목 매칭 락 TTL |
| `matching-engine.stream.batch-size` | 10 | 신규 Stream batch 크기 |
| `matching-engine.stream.pending-batch-size` | 20 | PEL 복구 batch 크기 |
| `matching-engine.stream.pending-min-idle-ms` | 5000 | claim 대상 최소 idle 시간 |
| `matching-engine.stream.max-retry-count` | 5 | DLQ 전 최대 실패 횟수 |
| `matching-engine.stream.pending-recovery-delay-ms` | 1000 | PEL 복구 간격 |
| `matching-engine.stream.max-length` | 1000000 | source Stream 최대 길이 |
| `matching-engine.stream.dlq-max-length` | 100000 | DLQ Stream 최대 길이 |

`application.yaml`에는 Redis, JWT, Toss API, 장 운영정보 cache, 호가 cache 및 기본 매칭 lock/rematch 설정이 선언되어 있다. 현재가, 일일 고저가, Stream 세부 설정은 코드의 placeholder 기본값으로도 동작하며 환경변수 또는 Spring property로 덮어쓸 수 있다.

## 17. 테스트와 현재 빌드 상태

### 17.1 현재 테스트

테스트는 다음 범위만 존재한다.

- Spring application context load 1건
- `SymbolSubscriptionRegistry` 단위 테스트 3건
  - 동일 심볼의 다중 subscription 추적
  - 기존 subscription ID의 심볼 이동
  - session disconnect 시 전체 구독 제거

인증, Toss client, cache, rate limiter, 주문 접수, 취소, 매칭 transaction, Stream 재처리와 DLQ에 대한 자동화 테스트는 아직 없다.

### 17.2 현재 컴파일 상태

2026-08-23 기준 `./gradlew compileJava`는 성공한다. 과거 `OrderBookPollingService.java`에 있던 effectively-final 컴파일 에러는 폴링 메서드를 두 그룹(§7.4)으로 분리하면서 함께 해결됐다.

`./gradlew test`는 4건 중 3건(`SymbolSubscriptionRegistryTests`)이 통과한다. `PaperTradingApplicationTests.contextLoads()` 1건은 로컬 환경에 PostgreSQL datasource가 설정되어 있지 않아 실패하며, 이는 §19.6에 정리된 기존 환경 구성 문제이지 코드 결함은 아니다. datasource와 Redis를 제공하면 4건 모두 통과하는 것을 확인했다.

`OrderRepository.findMatchableOrdersBySymbol()`의 JPQL constructor expression은 중첩 record를 `com.papertrade.paper_trading.Repository.MatchableOrder`로 참조하고 있어 Hibernate가 클래스를 해석하지 못했고, datasource가 연결되면 repository bean 생성 단계에서 application context 기동이 실패했다. 바깥 클래스를 포함한 `...Repository.OrderRepository$MatchableOrder`로 수정했다.

컴파일러는 여전히 `MatchingEngineStreamConsumer`의 unchecked operation을 경고한다.

## 18. 현재 구현 경계

다음 항목은 엔티티나 문서에는 정의되어 있지만 완성된 사용자 기능으로 연결되어 있지 않다.

- 회원가입 시 1:1 계좌 자동 생성
- 초기 모의 투자금 입금과 `INITIAL_DEPOSIT` 원장 생성
- 투자금 추가 신청 API
- 하루 1회 투자금 신청 제한
- 초기화 이후 누적 신청금 5,000,000 제한 계산
- 누적 수익률, 누적 수익금, 누적 신청금 초기화 API
- 계좌 잔고와 보유 자산 조회 API
- `Account.totalAssetValue` 재평가 및 체결 후 갱신
- 일별 계좌 snapshot batch
- 리더보드 계산 batch와 조회 API
- 국내/미국 종목 마스터 적재
- 환율 API client, cache, 저장과 적용
- 주문 목록 및 단건 조회 API
- DLQ 검색, replay, 삭제 관리 API
- 수수료와 세금의 실제 현금 반영 정책
- DB migration 또는 schema provisioning 도구

## 19. 현재 구조에서 주의할 점

### 19.1 신규 사용자의 주문 가능 여부

회원가입이 계좌를 만들지 않지만 주문 접수는 반드시 사용자 계좌를 조회한다. 별도의 seed나 외부 계좌 생성 과정이 없다면 신규 가입자는 `계좌를 찾을 수 없습니다.` 오류로 주문할 수 없다.

### 19.2 총자산 값

체결 transaction은 현금, 보유량, 평균단가, 실현손익을 갱신하지만 `Account.totalAssetValue`는 갱신하지 않는다. 따라서 현재 필드는 실제 자산 상태와 달라질 수 있다.

### 19.3 외부 호가 수량의 종목 스윕 내 재사용

`matchSymbol()`은 주문마다 §13.1 "호가 신선도 보장" 규칙을 만족하는 snapshot을 개별적으로 가져오지만, 캐시가 이미 그 주문의 `submittedAt` 이후 값이면 강제 재조회를 하지 않으므로 같은 스윕 안의 여러 주문이 동일한 snapshot 객체를 공유하는 경우가 흔하다. 이 경우 각 `matchOrder()`는 외부 호가 level의 소비 수량을 0부터 다시 계산하므로, 같은 snapshot을 공유한 여러 주문이 거기 표시된 동일 외부 수량을 각각 체결할 수 있다. 외부 유동성을 종목 스윕 전체에서 한 번만 소비해야 한다는 정책이라면 별도 보완이 필요하다.

### 19.4 수수료 전략 확장 시 현금 반영

현재 수수료와 세금은 항상 0이어서 계좌 잔고 결과에 영향이 없다. 향후 `CommissionCalculator`가 0이 아닌 값을 반환하더라도 현재 코드는 `Execution`에 값만 저장하고 계좌 현금 차감 및 별도 `COMMISSION`, `TAX` 원장을 만들지 않는다.

### 19.5 Redis와 DB 사이의 원자성

주문은 DB commit 후 callback으로 Redis Stream에 발행한다. rollback 주문의 이벤트 발행은 막지만, DB commit 직후 Redis 발행이 실패하는 구간을 완전히 원자적으로 묶지는 않는다. 현재는 30초 안전망 스케줄러가 미체결 주문 종목을 다시 발행해 이 구간을 보완한다. Transactional outbox는 구현되어 있지 않다.

### 19.6 운영 구성

저장소에는 구체적인 PostgreSQL datasource 값, migration, Docker Compose, CI 설정이 없다. 실제 실행 환경에서 PostgreSQL schema, Redis, Toss token, 충분히 강한 JWT secret을 별도로 준비해야 한다.

