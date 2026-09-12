# Paper Trading 현재 구현 현황

## 1. 문서 목적

이 문서는 현재 저장소에 실제로 구현된 기능과 각 기능의 구현 방식을 코드 기준으로 설명한다.

- 기준일: 2026-08-30
- 기준 브랜치의 현재 작업 트리 기준
- 엔티티가 존재하더라도 Controller, Service, Repository 흐름이 아직 없는 기능은 "현재 구현 경계"에서 별도로 구분한다.
- 도메인 모델의 컬럼과 관계에 대한 상세 명세는 `docs/entity-spec.md`를 참고한다.

## 2. 기술 구성

| 영역 | 사용 기술 | 현재 역할 |
| --- | --- | --- |
| 애플리케이션 | Java 21, Spring Boot 3.5.16 | 단일 Spring Boot 애플리케이션 |
| HTTP API | Spring Web MVC | 인증, 시세 조회, 주문 접수 및 취소 API |
| 인증/인가 | Spring Security, JWT, BCrypt | Stateless 인증과 사용자 상태 확인 |
| 영속성 | Spring Data JPA, PostgreSQL | 사용자, 계좌, 주문, 체결, 보유량, 현금 원장 저장 |
| 실시간 상태 | Spring Data Redis | 시세·Toss access token 캐시, 분산 호출량 제한, 분산 락과 활성 종목 공유 |
| 이벤트 처리 | Redis Stream | 종목 단위 비동기 매칭 요청과 재처리 |
| 서버 간 실시간 전파 | Redis Pub/Sub | 여러 애플리케이션 인스턴스 간 시세 갱신 팬아웃 |
| 클라이언트 실시간 전파 | STOMP WebSocket | 종목별 호가, 현재가, 일일 고저가 전송 |
| 외부 시세 | Toss Securities Open API REST + WebSocket | REST 장 운영정보·호가·현재가·일봉 조회와 WebSocket 호가 수신 |
| 외부 인증 | OAuth 2.0 Client Credentials | Toss access token 발급·공유 캐시·401 재발급 |
| 관측 | Spring Boot Actuator, Micrometer | health endpoint와 dirty-set 매칭 처리량·지연·적체량 metric |
| 직렬화 | Jackson 2 `ObjectMapper` | Toss 응답 및 Redis 캐시/Pub/Sub 메시지 처리 |
| 보조 코드 | Lombok | 생성자, Getter, Builder 생성 |
| 로컬 실행 | Dockerfile, Docker Compose | 애플리케이션 이미지와 PostgreSQL·Redis 포함 로컬 스택 |

애플리케이션 시작 클래스에는 `@SpringBootApplication`과 `@EnableScheduling`이 선언되어 있다. 따라서 컴포넌트 스캔과 함께 시세 폴링, Toss WebSocket 관리, 매칭 Stream 소비, dirty set drain, 미체결 주문 안전망 같은 스케줄 작업이 활성화된다. 블로킹 시세 폴링, dirty drain, Toss WebSocket 상태 전이는 `SchedulingConfig`의 전용 scheduler로 분리하고, 나머지 작업은 기본 scheduler pool을 사용한다.

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
  |     Toss OAuth2 access token 캐시
  |     Toss API 공유 호출량 제한
  |     폴링·종목 매칭·Toss WebSocket 슬롯 분산 락
  |     호가 활성/거절 종목과 dirty 종목 집합
  |     시세 Pub/Sub
  |     매칭 요청 Stream, PEL, 재시도 횟수, DLQ
  |
  `-- Toss Securities Open API
        REST: OAuth2 token, 장 운영정보, 호가, 현재가, 일봉
        WebSocket: 실시간 호가 push
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

### 5.1 OAuth2 access token

Toss REST와 WebSocket은 설정의 client secret을 Bearer 값으로 직접 사용하지 않는다. `TossAccessTokenProvider`가 OAuth 2.0 Client Credentials 방식으로 access token을 발급한다.

```text
POST {toss-invest.openapi.base-url}/oauth2/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
client_id={TOSS_INVEST_CLIENT_ID}
client_secret={TOSS_INVEST_SECRET_TOKEN}
```

발급된 token은 `toss-api:access-token` Redis key에 저장해 여러 인스턴스가 공유한다. 응답의 `expires_in`보다 10분 일찍 만료시키되 cache TTL은 최소 1분이다. Redis cache 읽기·쓰기가 실패해도 새로 발급한 token으로 현재 요청은 계속 진행한다.

REST 호출이 401을 반환하면 cache를 지우고 token을 다시 발급한 뒤 같은 요청을 한 번만 재시도한다. Toss WebSocket handshake도 이 provider의 access token을 사용한다.

### 5.2 공통 REST 클라이언트 방식

외부 API별로 Java `HttpClient` 기반 클라이언트를 사용한다.

- `TossMarketCalendarClient`
- `TossOrderBookClient`
- `TossPriceClient`
- `TossCandleClient`

공통 처리 방식은 다음과 같다.

1. 각 서비스가 해당 API 그룹의 Redis 공유 예산을 획득한다.
2. `TossOpenApiRequestExecutor`가 OAuth2 access token으로 `Authorization: Bearer {accessToken}` 헤더를 구성한다.
3. 연결과 요청 timeout을 5초로 적용한다.
4. 401이면 token 재발급 후 한 번만 다시 호출한다.
5. 각 응답의 `X-RateLimit-Limit`을 공유 레이트 리미터에 기록한다.
6. 2xx 응답은 DTO record로 역직렬화하고 그룹의 429 재시도 횟수를 지운다.
7. 429 응답은 Redis에 재시도 상태와 다음 허용 시각을 기록한다.
8. 오류 응답은 `TossOpenApiException`으로 변환해 상태코드, request ID, 오류 code와 message를 보존한다.

token 발급 API 자체는 아래 호출량 그룹에 포함되지 않는다.

### 5.3 공유 호출량 제한

`TossApiRateLimiter`는 애플리케이션 인스턴스별 메모리가 아닌 Redis로 호출 예산을 공유한다.

호출 그룹은 Toss 제한 기준에 맞춘 세 개다.

| 그룹 | 현재 포함 API | 기본 초당 한도 |
| --- | --- | --- |
| `market-data` | 호가, 현재가 | 15 |
| `market-data-chart` | 일봉 캔들 | 20 |
| `market-info` | 장 운영정보, 향후 환율 API | 3 |

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
- 기본 safety margin은 세 그룹 모두 0.8이다. 따라서 공급자 관측값이 없을 때 기본 실효 한도는 각각 초당 12, 16, 2회다.
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

- `market-data`, `market-data-chart`: HTTP 202, `Retry-After`, `status=pending`
- `market-info`: HTTP 503, `Retry-After`

앞의 두 그룹은 기존 polling → Redis Pub/Sub → STOMP 경로를 통해 나중에 실제 값이 전달될 수 있으므로 pending 응답을 사용한다. WebSocket을 사용하지 않는 클라이언트는 `Retry-After` 이후 REST 요청을 다시 할 수 있다. 장 운영정보에는 push 경로가 없으므로 클라이언트가 직접 재시도해야 한다.

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
4. 캐시가 없으면 `market-info` 예산을 획득한다.
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

REST 응답과 Toss WebSocket push는 모두 `OrderBookService.applyOrderBook()`으로 합류한다. 이 메서드가 cache 저장, 변경 감지, 화면 Pub/Sub, dirty 종목 등록을 공통 처리한다. 응답에는 공급자 timestamp와 별개로 애플리케이션이 실제 수신한 시각인 `receivedAt`이 포함되며, 매칭 엔진과 REST fallback이 호가 신선도를 판단할 때 사용한다.

### 7.3 호가 변경 이벤트

호가가 변경되면 다음 두 동작이 연결된다.

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

### 7.4 Toss 실시간 호가 WebSocket

`TossOrderBookWebSocketManager`와 `TossOrderBookWebSocketConnection`이 공급자 WebSocket `wss://openapi-ws.tossinvest.com/ws/v1`에 연결해 `orderbook:{market}`을 구독한다. 이 연결은 클라이언트용 STOMP `/ws`와 별개의 외부 데이터 수신 경로다.

#### 연결 슬롯

- 기본적으로 WebSocket을 활성화하고, 계정당 2개 슬롯과 연결당 100종목을 사용한다.
- 각 슬롯은 `toss-ws:slot:{index}` Redis lock으로 점유한다. 기본 TTL은 15초, heartbeat는 5초다.
- 획득·갱신·해제는 인스턴스 UUID가 일치할 때만 수행한다. Redis 오류나 소유권 상실로 갱신하지 못하면 해당 연결을 닫는다.
- 슬롯 관리와 구독 갱신은 반드시 단일 thread인 `webSocketScheduler`에서 직렬 실행한다.
- 연결 실패 시 1초에서 최대 30초까지 지수 backoff와 full jitter를 적용한다.
- 기본 60초마다 문자열 `PING`을 보내 공급자의 180초 수신 timeout을 피한다.

#### 활성 종목과 우선순위

공급자 WebSocket이 받을 후보는 다음 두 집합의 합집합이다.

1. DB의 `PENDING`, `PARTIALLY_FILLED` 주문 종목
2. 모든 애플리케이션 인스턴스에서 STOMP 호가를 구독 중인 종목

각 인스턴스의 로컬 STOMP 구독 종목은 기본 5초마다 `orderbook:active-symbols` Redis sorted set에 현재 시각을 score로 기록한다. 30초 동안 갱신되지 않은 종목은 제거한다. 전역 목록 읽기가 실패하면 인스턴스별 로컬 목록으로 갈라지는 대신 마지막으로 성공한 전역 snapshot을 유지한다.

정원이 부족할 때는 미체결 주문 종목이 화면 구독 전용 종목보다 우선한다. 미체결 종목끼리는 종목별 가장 이른 `submittedAt` 순서로 정렬해 먼저 접수된 주문이 먼저 실시간 슬롯을 얻는다. 그 뒤에 전역 화면 구독 종목을 심볼 오름차순으로 중복 없이 붙인다.

슬롯 배정은 상태 기반으로 유지된다. 이미 배정된 종목은 가능한 한 같은 슬롯에 남기고, 새 종목만 현재 여유가 가장 큰 슬롯에 넣는다. 이는 full-replace 재선언 뒤 공급자가 초기 snapshot을 바로 보내지 않아 생길 수 있는 데이터 공백을 줄인다. 정원을 넘긴 종목은 §7.5 REST polling으로 내려간다.

구독 ACK에서 `stock-not-found` 등으로 거절된 종목은 로컬 연결과 `orderbook:ws-rejected` Redis sorted set에 기록한다. 기본 10분 동안 WebSocket 후보에서 빼고 REST polling이 담당하게 하며, TTL 뒤에는 다시 시도한다.

#### 선언과 수신

- 담당 종목이 달라졌을 때만 전체 구독 목록을 full-replace 선언한다.
- 선언 계산은 기본 250ms fixed delay로 실행하며, 공급자 `rate-limit-exceeded` 오류 뒤에는 1초 cooldown을 둔다.
- ACK의 확인 종목이 의도한 목록과 다르면 다음 tick에서 전체를 다시 선언한다.
- push의 `topic`에서 심볼을 추출하고 `data`를 REST와 같은 `OrderBookResult`로 변환해 `OrderBookService.applyOrderBook()`에 전달한다.

현재의 끈끈한 `slotBySymbol` 배정 상태는 manager 인스턴스 메모리에만 있다. 하나의 애플리케이션 인스턴스가 두 슬롯을 모두 소유할 때는 중복 없는 최대 200종목 배정을 보장하지만, 서로 다른 인스턴스가 슬롯을 하나씩 소유하면 전역 배정 map이 없어 두 manager가 같은 우선순위 상위 종목을 선택할 수 있다. 이 다중 인스턴스 경계는 §19.6에 별도로 정리한다.

### 7.5 REST 호가 폴백

`OrderBookPollingService`는 두 종목 그룹을 서로 다른 주기의 `@Scheduled` 메서드로 폴링한다.

1. `pollPendingOrderSymbols()` — `PENDING`, `PARTIALLY_FILLED` 주문이 존재하는 종목. 기본 1초 fixed delay(`orderbook.polling.fixed-delay-ms`).
2. `pollIdleSubscriptionSymbols()` — WebSocket에서 호가를 구독 중이지만 미체결 주문 종목에는 포함되지 않은 종목. 기본 20초 fixed delay(`orderbook.polling.idle-fixed-delay-ms`).

미체결 주문 종목의 호가는 매칭 엔진이 신선한 체결 기준을 확보하는 데 직접 쓰이므로 짧은 주기를 유지한다. 화면 구독 전용 종목은 지연 시세로 취급해 주기를 늘리고, 그만큼 Toss API 공유 예산을 아낀다. 각 그룹 안에서는 시작 offset을 회전해 특정 종목이 항상 마지막에 처리되지 않도록 한다.

WebSocket이 배정한 종목은 `receivedAt`이 기본 30초 신선도 임계값 안에 있는 동안 REST polling에서 제외한다. 배정됐더라도 cache가 임계값보다 오래되면 연결 장애로 보고 자동으로 REST fallback한다. WebSocket 정원에 들지 않은 종목에는 이 신선도 gate를 적용하지 않고 매 주기 polling한다.

다중 서버의 중복 폴링을 줄이기 위해 `orderbook:poll-lock:{symbol}` key에 기본 900ms TTL의 짧은 락을 사용한다. 이 polling lock용 Redis 접근이 실패하면 현재 호가 구현은 fail-open으로 REST 호출을 계속 시도한다. 단, 실제 Toss 호출 직전의 공유 호출량 제한 획득은 계속 fail-close다.

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

`PendingOrderRematchScheduler`는 기본 30초마다 미체결 주문이 있는 종목을 종목별 가장 이른 주문 접수 시각 순으로 조회하고 다음 이벤트를 발행한다.

```text
symbol={stock.symbol}
reason=SAFETY_NET
```

호가 변경 이벤트나 주문 접수 후 이벤트 발행이 유실된 경우를 보완하는 낮은 빈도의 복구 경로다.

### 12.9 Dirty 종목 drain과 metric

`DirtyOrderBookSymbolDrainScheduler`는 전용 단일-thread scheduler에서 기본 150ms fixed delay로 `orderbook:dirty`를 drain한다.

1. Redis `SPOP count`로 최대 20종목을 원자적으로 꺼낸다.
2. DB에서 현재 미체결 주문 종목 집합을 한 번 조회한다.
3. 이미 미체결 주문이 없는 종목은 버린다.
4. 나머지는 종목별로 `SymbolMatchingProcessor`를 호출한다.
5. 락 경합이면 마지막 변경 신호가 사라지지 않게 set에 다시 넣는다.
6. API 예산 부족이면 hot loop를 피하려고 다시 넣지 않는다.

미체결 종목 조회 자체가 실패하면 이미 꺼낸 batch 전체를 재등록한다. 한 종목의 예외는 나머지 batch 처리를 막지 않도록 종목별로 격리한다. 결정적 처리 실패 종목은 즉시 재등록하지 않고 log와 30초 Stream 안전망에 맡긴다.

Micrometer에는 다음 meter를 등록한다.

- `matching.dirty_drain.outcome{outcome=success|lock_busy|quota_unavailable|failure|requeued}` counter
- `matching.dirty_drain.duration` timer
- `matching.dirty_symbols.size` gauge

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

잔고·보유 수량 부족은 예외로 다루지 않는다. 체결 수량을 정하기 **전에** 그 제약으로 캡을 씌우고, 1주도 체결할 수 없으면 주문을 거절 상태로 종료한다(§13.11). 예외를 던지면 같은 transaction에서 이미 성사된 체결까지 롤백되고, 재시도해도 같은 자리에서 또 막히므로 체결 가능한 물량마저 영영 체결되지 않기 때문이다.

그래서 `matchSymbol()`까지 올라오는 `IllegalArgumentException`은 정상적인 "조건 미달"이 아니라 데이터 불일치 신호다. 그래도 루프는 중단하지 않고 해당 주문만 오류 log를 남긴 뒤 다음 주문을 계속 처리한다 — 중단하면 뒤에 줄 선 정상 주문까지 막힌다. DB 연결 장애 같은 다른 예외는 상위로 전파해 Stream 재시도와 DLQ 경로를 유지한다.

#### 호가 신선도 보장

각 주문을 실제로 매칭하기 전에 체결에 쓸 호가 snapshot을 가져온다.

```text
for (id, submittedAt) in 매칭대상목록:
    orderBook = orderBookService.getOrderBookForMatching(symbol, submittedAt)
    matchOrder(id, orderBook, dailyPriceRange)   # pessimistic lock은 이 안에서 걸린다
```

이 호출은 반드시 해당 주문의 pessimistic lock을 잡기 전, `TransactionTemplate` 바깥에서 이뤄진다. 그래서 응답을 기다리는 동안 주문 row가 잠겨 있지 않다.

`getOrderBookForMatching()`의 규칙은 종목이 WebSocket으로 구독 중인지에 따라 갈린다.

**WebSocket 구독 종목: 캐시를 그대로 사용한다. Toss를 호출하지 않는다.**

토스는 호가가 **바뀔 때만** 프레임을 보낸다. 그러므로 push 피드에서 `receivedAt`은 "마지막으로 확인한 시각"이 아니라 **"마지막으로 바뀐 시각"**이다. `receivedAt`이 주문의 `submittedAt`보다 앞선다는 것은 데이터가 낡았다는 뜻이 아니라 그 이후로 호가가 변하지 않았다는 뜻이고, 캐시가 곧 현재 상태다. REST를 호출해도 같은 값을 받는다.

오히려 호출하면 손해다. `applyOrderBook()`은 `receivedAt`을 로컬 적용 시각으로 찍으므로, 왕복 지연만큼 낡은 REST 응답이 더 새로운 WebSocket snapshot을 덮어쓰면서 "방금 받은 것"으로 기록된다.

**비구독 종목: 기존 규칙을 유지한다.** 캐시의 `receivedAt`이 `submittedAt` 이상이면 캐시를 쓰고, 아니면 그 자리에서 Toss를 호출해 캐시를 갱신한다.

**판정은 `DeclaredWebSocketSymbolRegistry`가 한다.** "프레임 없음 = 변동 없음"이라는 등식은 피드가 살아 있을 때만 성립하므로, 판정 신호는 `TossOrderBookWebSocketManager.coveredSymbols()`가 아니라 실제 구독 선언 목록이어야 한다. `coveredSymbols()`는 "구독하기로 한 종목"이라 연결이 끊긴 동안에도 남는데(폴링에는 신선도 임계값이라는 백스톱이 있어 의도된 동작이다), 매칭에는 그 백스톱이 없다.

선언 목록은 연결 실패, error frame, 슬롯 상실, 구독 ACK 불일치 모두에서 비워진다. 시간 기반 판정이 없고 이벤트로만 해제되므로, 피드가 죽으면 그 순간부터 매칭이 REST 경로로 돌아온다. `TossOrderBookWebSocketManager.refreshSubscriptions()`가 배정 틱마다 이 목록을 registry에 게시하며, WebSocket이 비활성이거나 슬롯이 없으면 registry를 비운다.

**구독 종목인데 캐시가 없으면 `null`을 반환한다.** 토스는 구독 직후 초기 snapshot을 주지 않으므로 갓 배정된 종목은 첫 호가 변동까지 캐시가 비어 있다. 이 구간에서는 외부 유동성 없이 내부 체결만 진행하고, `OrderBookPollingService`가 1초 주기로 씨딩한다 — `isFresherThan()`이 캐시 부재를 "신선하지 않음"으로 보기 때문에 구독 종목이어도 폴링 대상이 된다. 씨딩되면 `applyOrderBook()`이 변경을 감지해 `markDirty()`를 찍으므로 다음 drain에서 다시 매칭된다.

**캐시 TTL은 폴링 신선도 임계값보다 커야 한다.** `orderbook.cache.ttl-seconds`(기본 120초)가 `orderbook.polling.staleness-threshold-ms`(기본 30초)보다 작거나 같으면, 호가가 조용한 구독 종목은 폴링이 되살리기 전에 키가 만료되어 주기적으로 호가를 잃는다. 두 값은 설정 항목이 달라 한쪽만 바뀌기 쉬우므로 함께 확인해야 한다.

체결에 필요한 물량이 부족해 주문이 부분 체결로 남는 경우에는 그 자리에서 추가로 재조회하지 않는다. WebSocket 담당 종목은 push가 호가 변경 신호를 만들고, 그렇지 않은 종목은 §7.5의 1초 REST polling이 계속 돈다.

내부 주문끼리의 체결(§13.5)은 Toss 호가를 전혀 참조하지 않으므로 이 신선도 규칙의 적용 대상이 아니다.

### 13.2 트랜잭션 경계와 락 획득 순서

**체결 한 건이 트랜잭션 한 건이다.** 주문 하나를 끝까지 체결하는 loop는 트랜잭션 <b>밖</b>에 있고, 체결할 때마다 짧은 트랜잭션을 연다.

```text
matchOrder(주문)                       ← 트랜잭션 밖. loop를 돌린다
  └ matchOnce()  × N                  ← 각각이 트랜잭션 하나 = 체결 하나
```

#### 왜 주문 단위가 아니라 체결 단위인가

가격-시간 우선순위 때문이다.

한 트랜잭션이 주문 전체를 처리하려면 잠글 계좌를 **시작 시점에 다 알아야** 한다(데드락을 막으려면 순서가 고정돼야 하고, 순서를 정하려면 대상을 알아야 한다). 그러면 잠글 수에 상한을 둘 수밖에 없고, 상한을 두는 순간 **앞선 주문이 체결 가능한 물량을 남겨 둔 채 멈춘다.** 그 물량을 뒤에 선 주문이 가져가면 우선순위가 깨진다.

우선순위는 타협 대상이 아니므로 상한을 없애야 하고, 그러려면 한 번에 잠그는 범위를 줄여야 한다. 체결 한 건은 참가자가 둘뿐이라 미리 알아낼 필요가 없다.

부수 효과로 계좌 락 보유 시간이 주문 전체가 아니라 체결 하나 수준으로 줄어든다. 잠긴 계좌는 그동안 주문도 낼 수 없으므로(§13.11) 이것도 중요하다.

#### 락 획득 순서

모든 트랜잭션이 **종류 순서**를 지킨다.

```text
① 주문 row     자기 주문 → 상대 주문
② 계좌 row     id 오름차순, 최대 2개
③ 보유 row     ②에서 잠근 계좌의 것만
```

| 조합 | 순환 | 근거 |
| --- | --- | --- |
| 주문 ↔ 주문 | 없음 | 매칭은 종목 단위이고 종목 락이 같은 종목의 동시 매칭을 막는다. 다른 종목의 주문은 상대가 될 수 없다 |
| 계좌 ↔ 계좌 | 없음 | 트랜잭션당 최대 2개를 id 오름차순으로 잡는다. 순환하면 `r1 < r2 < ... < r1`이 되어 모순이다 |
| 주문 ↔ 계좌 | 없음 | 계좌를 쥔 채 주문을 기다리는 트랜잭션이 없다. 늘 주문이 먼저다 |
| 계좌 ↔ 보유 | 없음 | 보유는 이미 잠근 계좌의 것만 잡는다 |
| 매칭 ↔ `placeOrder` | 없음 | 접수도 계좌 → 보유 순서라 방향이 같다 |
| 매칭 ↔ `cancelOrder` | 없음 | 취소는 주문 락 하나만 잡고 다른 것을 기다리지 않는다 |

이미 취소 또는 체결 완료된 주문은 row lock 획득 후 상태를 다시 확인하고 바로 반환한다.

#### 트랜잭션이 끊기는 사이

종목 락이 같은 종목의 동시 매칭을 막으므로, 체결과 체결 사이에 다른 매칭이 끼어들지 않는다. 외부 호가 소비 위치와 체결 불가로 판명된 상대는 트랜잭션 밖 cursor가 값으로 들고 다닌다 — rollback돼도 그대로여야 하기 때문이다.

측정값(로컬 Docker PostgreSQL): 10계좌·100주 체결에 약 62ms, 계좌당 약 6ms. 락은 이 시간 내내가 아니라 체결 한 건 동안만 잡힌다.

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

외부 호가 수량을 어디까지 소비하는지는 §19.3의 정책을 따른다. 한 주문 안에서는 소비하고, 주문과 주문 사이에서는 소비하지 않는다.

### 13.4 원장 기록

체결·입금 같은 자산 이동은 모두 복식부기 원장에 남는다. `cash_transactions`(단식부기)는 삭제했다.

```text
ledger_transactions   하나의 경제적 사건. idempotency_key가 unique
ledger_entries        분개. 한 거래에 2줄 이상, 금액 합계는 항상 0
```

**불변식**: 거래별 `SUM(amount) = 0`, 전역 `SUM(amount) = 0`. 두 번째가 복식부기를 쓰는 이유다 — 버그로 돈이 생기거나 사라진 것을 탐지하는 유일한 수단이며, 단식부기로는 원리적으로 불가능하다.

부호는 표준 회계를 따른다. 자산 계정(`CASH`, `SECURITIES`)은 증가가 `+`, 수익·자본 계정(`REALIZED_PNL`, `EQUITY_FUNDING`)은 증가가 `−`, 비용 계정(`FEE`, `TAX`)은 발생이 `+`다.

| 사건 | 분개 |
| --- | --- |
| 계좌 개설 | `CASH +금액`, `EQUITY_FUNDING −금액` |
| 외부 호가 매수 | `CASH −대금`, `SECURITIES +대금 (수량 +)` |
| 외부 호가 매도 | `CASH +대금`, `SECURITIES −취득원가 (수량 −)`, `REALIZED_PNL −(대금−취득원가)` |
| 내부 체결 | 위 둘을 한 거래에 묶는다. 분개 5줄 |
| 수수료·세금 | `CASH −금액`, `FEE`/`TAX +금액`. 0이면 분개를 만들지 않는다 |

**실현손익이 원장에서 나온다.** `accounts.realized_profit`은 이제 `−Σ(REALIZED_PNL entries)`로 재계산해 검증할 수 있는 파생 캐시다.

`LedgerPostingService`가 원장에 기록하는 유일한 통로이며 거래 단위 균형을 검증한다. 우회해서 `LedgerEntry`를 직접 저장하면 안 된다.

`balance_after`는 유지되는 잔고 캐시가 있는 계정과목(`CASH`, `SECURITIES`)에만 채운다. 분개마다 전체 이력을 `SUM`하면 원장이 길어질수록 체결이 느려지기 때문이다. 대사는 이 값이 아니라 `SUM(amount)`으로 한다.

**멱등키**: 체결은 `FILL:{tradeId}`, 계좌 개설은 `OPEN:{accountId}`. `executions.trade_id`와 `executions.ledger_transaction_id`가 체결 사실과 원장 거래를 잇는다. 내부 체결은 `Execution` 두 건이 같은 거래를 가리킨다.

`executions.commission` / `tax`는 **표시용 사본이며 계산에 쓰지 않는다.** 잔고·손익의 근거는 언제나 `FEE`/`TAX` 분개다.

`AccountOpeningService`가 계좌 생성과 개시 분개를 한 transaction으로 묶는다. 갈라지면 `cash_balance = Σ(CASH entries)`가 처음부터 성립하지 않는다.

### 13.5 내부 주문 체결

내부 매수자와 내부 매도자가 체결될 때 한 transaction에서 다음을 수행한다.

1. 매수자와 매도자 계좌에 write lock을 건다.
2. 매도자의 보유 row를 조회한다. 없으면 **0을 반환하고 끝낸다** — 예외를 던지지 않는다.
3. 요청 수량에 매도자 보유 수량과 매수자 구매 가능 수량으로 캡을 씌운다. 결과가 0이면 0을 반환한다.
4. 매수자의 보유 row를 조회하거나 생성한다. 체결이 0이면 빈 보유 row를 만들지 않도록 캡 계산 뒤에 한다.
5. 매도 보유량에서 원가를 차감한다.
6. 매수자 현금을 차감하고 매도자 현금을 증가시킨다.
7. 매도자의 실현손익을 증가시킨다.
8. 매수자의 보유 수량과 평균단가를 갱신한다.
9. 양쪽 주문의 체결/잔여 수량과 상태를 갱신한다.
10. 매수와 매도 각각의 `Execution`을 저장한다.
11. 양쪽 계좌 각각에 `CashTransaction`을 저장한다.
12. **실제 체결한 수량을 반환한다.**

반환값 0은 "이 상대방과는 체결할 수 없다"는 뜻이다. 호출자는 그 상대를 후보 제외 목록에 넣고 다음 후보로 넘어간다. 제외하지 않으면 같은 후보를 계속 다시 뽑아 무한 loop가 된다. 제외 목록은 `OrderRepository.findMatchableSellOrders()` / `findMatchableBuyOrders()`의 `excludedOrderIds` 파라미터로 전달되며, 호출자가 항상 자기 주문 ID를 넣고 시작하므로 비는 경우가 없다.

### 13.6 Toss 외부 호가 체결

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

### 13.7 보유 평균단가와 실현손익

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

### 13.8 수수료와 세금

`CommissionCalculator` 전략 interface가 있으며 현재 구현체는 `ZeroCommissionCalculator`다.

- 수수료: 0
- 세금: 0

체결 row에는 계산 결과를 기록한다. 실제 수수료 정책이 결정되면 전략 구현체를 교체할 수 있도록 매칭 엔진과 분리되어 있다.

### 13.9 체결 불가 주문의 거절

계좌 조건으로 더 이상 체결될 수 없는 주문은 `PENDING`으로 두지 않고 종료한다. 두지 않는 이유는 30초 안전망 scheduler가 그 주문을 영원히 재발행하는데 결과가 매번 같기 때문이다.

거절 지점은 네 곳이다.

| 위치 | 조건 | 사유 |
| --- | --- | --- |
| `matchSellOrder()` 진입부 | 보유 row 없음 또는 보유 0 | `보유 수량이 부족합니다.` |
| `matchSellOrder()` loop | 체결 상대가 있는데 보유가 0으로 소진됨 | `보유 수량이 부족합니다.` |
| `matchBuyOrder()` 내부 분기 | 체결 상대가 있는데 1주도 살 잔고가 없음 | `주문 가능 금액이 부족합니다.` |
| `matchBuyOrder()` 외부 분기 | 외부 ask가 있는데 1주도 살 잔고가 없음 | `주문 가능 금액이 부족합니다.` |

**체결 상대가 없어서 체결되지 않은 주문은 거절하지 않는다.** 그건 계좌 문제가 아니라 유동성 문제이므로 `PENDING`으로 대기시킨다. 그래서 loop 안에서 "상대 없음"을 "잔고·보유 부족"보다 먼저 판정한다. 순서가 바뀌면 호가가 잠깐 빈 종목의 정상 주문까지 거절된다.

`Order.reject(reason)`은 체결 이력이 있으면 `REJECTED`가 아니라 `CANCELED`로 보낸다. `REJECTED`는 접수 자체가 무효였다는 뜻이라 부분 체결과 같이 쓸 수 없다. 어느 쪽이든 `rejectReason`과 `filledQuantity`는 보존되고, 사유는 `OrderResponse.rejectReason`으로 노출된다.

거절 판정이 "체결 상대가 있었는가"에 의존하므로, 외부 호가 snapshot이 비어 있으면 잔고가 부족한 주문도 거절되지 않고 대기한다. 원장이 깨지지는 않지만 거절 시점이 호가 품질에 좌우된다.

투자금 충전 이후 자동 재체결은 없다. 잔고가 부족했던 주문은 이미 종료되어 있으므로 사용자가 다시 주문해야 한다.

### 13.10 자전거래 차단과 주문가격 밴드

**같은 계좌는 매칭 후보에서 제외한다.** `findMatchableSellOrders`/`findMatchableBuyOrders`에 `o.account.id <> :accountId` 조건이 있다.

막는 것은 이것이다 — 자기 자신과 체결하면 현금이 나갔다 들어와 순변동이 0인데 `REALIZED_PNL` 분개는 그대로 적립되고 평균단가도 바뀐다. 양쪽 가격을 스스로 정할 수 있으므로 원하는 만큼 손익을 만들어낼 수 있었다. 원장 균형은 깨지지 않지만 실현손익이 오염된다.

**지정가 주문가격은 당일 거래 범위 ±`order.price-band.margin`(기본 0.3) 안이어야 한다.**

```text
허용 범위 = [dailyLowPrice × (1 − margin), dailyHighPrice × (1 + margin)]
```

미국 시장에는 일일 가격제한폭 제도가 없으므로 이건 규제 한도가 아니라 **오입력 방지 장치**다. 저가 매수·고가 매도를 걸어 두는 정상 주문을 막지 않도록 넉넉하게 잡는다. 국내 종목이 들어오면 전일 종가 ±30%라는 진짜 제한폭을 쓸 수 있다.

같은 계좌 차단만으로는 **서로 다른 계좌가 터무니없는 가격에 맞붙는 것**을 막지 못한다. 한쪽이 잃고 한쪽이 얻는 구조라 공짜는 아니지만, 계정을 여러 개 만들면 한 계정을 희생시켜 다른 계정의 손익을 부풀릴 수 있다. 가격 밴드가 그 폭을 좁힌다.

시세를 구하지 못하면 이 검증을 건너뛴다. 정합성 요건이 아니라 방어 장치이고, 외부 시세가 잠깐 막혔다고 정상 주문까지 거절하면 손해가 더 크다.

### 13.11 가용잔고(주문가능금액)와 접수 시점 검증

예수금을 넘는 주문은 **접수 단계에서 거절한다.** 미체결 주문이 묶어 둔 금액을 예수금에서 뺀 것이 주문가능금액이다.

```text
예수금(accounts.cash_balance)   ← 체결로만 변한다. 미체결 주문은 깎지 않는다
− 미체결 매수 구속액
= 주문가능금액                   ← 사용자가 쓸 수 있는 금액. 저장하지 않는 계산값
```

매도는 같은 구조를 수량으로 적용한다. `매도가능수량 = holdings.quantity − 미체결 매도 구속 수량`.

#### 구속액은 저장하지 않고 주문에서 파생한다

`accounts`에 `reserved_cash` 같은 컬럼을 두지 않는다. 컬럼으로 두면 체결·취소·거절마다 해제 코드가 필요하고, 하나라도 빠지면 그 금액이 영구히 묶인다.

컬럼이 맞는지 검증하는 것 자체는 가능하다 — 미체결 주문을 집계해서 비교하면 된다. 다만 **그 대사의 기준값이 곧 파생값**이므로, 그럴 거면 컬럼을 둘 이유가 읽기 속도밖에 남지 않는다. 그 읽기 속도를 위해 해제 코드, 드리프트 대사 batch, 어긋났을 때의 복구 절차를 떠안게 된다.

대신 `OrderRepository.sumReservedCash()` / `sumReservedQuantity()`가 미체결 주문에서 집계한다. **해제 경로라는 것이 존재하지 않는다** — 체결되면 `remaining_quantity`가 줄고, 취소·거절되면 `status`가 빠지면서 합계에서 자동으로 사라진다. 부분 체결도 자동 반영된다. 드리프트가 개념적으로 발생할 수 없다.

집계 비용을 위해 `orders(account_id, order_side, status)` index를 둔다.

#### `orders.reserved_unit_price`

매수 주문이 1주당 구속하는 금액을 접수 시점에 정해 저장한다. 지정가는 주문가격, 시장가는 당일 고가다. 매도 주문은 `null`이다.

파생값을 저장하는 것처럼 보이지만 성격이 다르다. **주문의 불변 속성**이라 드리프트가 생길 수 없고, 덕분에 구속액 집계가 외부 시세 조회 없이 `orders` 한 테이블에서 순수 SQL로 끝난다. 계좌 row lock을 쥔 채 Toss를 기다리는 일이 없어야 하므로 이 점이 중요하다.

#### 시장가 매수의 구속 단가

`DailyPriceRangeResponse.dailyHighPrice`(당일 고가)를 쓴다. §13.7의 `applyMarketOrderRemainingPrice()`가 시장가 잔여 물량의 대기 가격으로 심는 값과 같아서, 구속 기준이 주문 생애 내내 한 가지로 이어진다.

당일 고가를 구할 수 없으면 **접수를 거절한다.** 구속 금액을 계산할 수 없는 주문을 받아들이면 규칙에 구멍이 생긴다.

당일 고가는 "지금까지 거래된 최고가"지 "오늘 도달 가능한 최고가"가 아니다. 급등 구간에서는 구속이 실제 체결금액보다 작을 수 있는데, 그때는 §13.9의 체결 시점 캡이 방어선이 된다 — 살 수 있는 만큼만 체결되고 잔량은 거절된다. **접수 검증과 체결 검증의 2중 구조**이며, 접수 검증이 생긴 뒤에도 §13.8을 남겨 두는 이유가 이것이다.

국내 주식은 전일 종가 × 1.3(KONEX × 1.15)이라는 진짜 상한가가 있어 당일 고가보다 안전한 기준이지만, 전일 종가를 구할 경로가 없고 국내 종목 거래 자체가 미구현이라 국내 종목 지원과 함께 다룬다.

#### 접수 순서

```text
1. 요청 검증 (지정가/시장가 가격 유무)
2. 계좌 조회 (락 없음)
3. 멱등 재요청이면 기존 주문 반환 — 검증보다 먼저. 이미 접수된 주문을 재검증하면
   그 사이 잔고가 줄었을 때 같은 요청이 성공했다가 실패한다
4. 종목 조회
5. 구속 단가 계산 — 시세 조회가 필요할 수 있으므로 락을 잡기 전에 끝낸다
6. 계좌 row 잠금 (findByIdForUpdate)
7. 구속액 집계 + 검증
8. 주문 저장
```

6번이 없으면 동시에 들어온 두 요청이 같은 주문가능금액을 읽고 둘 다 통과해 예수금을 넘긴다. 계좌 단위로 직렬화하는 것이 이 검증의 전제다.

#### 남아 있는 것

수수료·세금 예상액을 구속액에 더한다. 현재 `ZeroCommissionCalculator`라 0이지만 규약은 세워 두었다.

### 13.12 원장 대사

로직이 아무리 정교해도 버그는 난다. 그래서 원장 시스템의 진짜 안전망은 올바르게 쓰는 코드가 아니라 **틀렸다는 것을 반드시 발견하는 장치**다. `LedgerReconciliationService`가 그 장치이며 `ledger.reconciliation.cron`(기본 매일 05:30)으로 돈다.

| # | 검사 | 기준 |
| --- | --- | --- |
| 1 | 전역 균형 | `SUM(ledger_entries.amount) = 0` |
| 2 | 거래 균형 | 거래별 `SUM(amount) = 0` |
| 3 | 현금 잔고 | `accounts.cash_balance = Σ(CASH entries)` |
| 4 | 실현손익 | `accounts.realized_profit = −Σ(REALIZED_PNL entries)` |
| 5 | 보유 수량 | `holdings.quantity = Σ(SECURITIES entries.quantity)` |
| 6 | 보유 원가 | `holdings.total_purchase_amount = Σ(SECURITIES entries.amount)` |
| 7 | 수수료 사본 | `Σ(executions.commission) = Σ(FEE entries)` |
| 8 | 세금 사본 | `Σ(executions.tax) = Σ(TAX entries)` |

1번이 복식부기를 쓰는 이유 그 자체다. 돈이 생기거나 사라진 것을 탐지하는 유일한 수단이며 단식부기로는 원리적으로 불가능하다. 2번이 걸리면 `LedgerPostingService`를 우회해 분개를 저장한 코드가 있다는 뜻이다.

가용잔고는 저장하지 않고 `orders`에서 파생하므로 대사 대상이 아니다(§13.11).

**불일치를 자동으로 덮어쓰지 않는다.** 조용히 맞춰 버리면 버그를 숨기게 된다. `ERROR` 로그와 `ledger.reconciliation.mismatch` metric으로 올리고 사람이 판단한다. 정정이 필요하면 원본을 남긴 채 반대분개를 추가한다.

모든 질의가 **불일치 항목만** 돌려준다. 계좌마다 집계해 비교하면 계좌 수에 비례해 느려지지만, 이 방식은 질의 수가 고정이라 데이터가 늘어도 비용이 검사 항목 수만큼만 늘어난다.

7·8번은 거래 단위가 아니라 **전체 합계**로 비교하므로 서로 상쇄되는 오차는 잡지 못한다. 수수료가 0인 현재는 실질적 제약이 아니다.

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
| `LedgerTransaction` / `LedgerEntry` | 복식부기 원장 |
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
| `market-data`, `market-data-chart` quota 부족 | 202, pending body와 `Retry-After` |
| `market-info` quota 부족 | 503, 오류 body와 `Retry-After` |
| 그 밖의 예외 | 500, 일반화된 한국어 message |

예상하지 못한 예외의 stack trace는 서버 log에 남기고 HTTP 응답에는 내부 정보를 노출하지 않는다.

Redis cache, Pub/Sub, STOMP subscriber 처리의 일부 오류는 실시간 부가 경로의 실패가 주 요청을 깨뜨리지 않도록 의도적으로 무시한다. 반면 Toss API 호출량 제한 상태를 확인하지 못하는 경우에는 외부 API 호출을 막는다. 공급자 WebSocket의 연결·frame·선언 오류는 log를 남기고 재연결 또는 재선언 경로로 처리한다.

## 16. 주요 설정과 기본값

| 설정 | 기본값 | 설명 |
| --- | --- | --- |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/papertrading` | PostgreSQL JDBC URL |
| `spring.datasource.username` | `papertrading` | PostgreSQL 사용자 |
| `spring.datasource.password` | `papertrading` | PostgreSQL 비밀번호 |
| `spring.jpa.hibernate.ddl-auto` | `none` | 기본 schema 자동 변경 비활성화 |
| `spring.jpa.open-in-view` | `false` | 요청 종료까지 persistence context 유지 안 함 |
| `spring.task.scheduling.pool.size` | 4 | 기본 scheduler pool |
| `market-data.polling.scheduler.pool-size` | 3 | REST 시세 polling 전용 pool |
| `matching-engine.dirty-drain.pool-size` | 1 | dirty drain 격리용 pool |
| `security.jwt.access-token-expiration-minutes` | 30 | Access token 만료 |
| `security.jwt.refresh-token-expiration-days` | 14 | Refresh token 만료 |
| `toss-invest.openapi.base-url` | `https://openapi.tossinvest.com` | Toss REST 및 OAuth2 base URL |
| `toss-invest.rate-limit.market-data.default-limit` | 15 | 호가·현재가 초당 기본 한도 |
| `toss-invest.rate-limit.market-data-chart.default-limit` | 20 | 캔들 초당 기본 한도 |
| `toss-invest.rate-limit.market-info.default-limit` | 3 | 장 운영정보 초당 기본 한도 |
| `toss-invest.rate-limit.*.safety-margin` | 0.8 | 관측/기본 한도에 적용할 여유율 |
| `toss-invest.websocket.enabled` | `true` | 공급자 호가 WebSocket 사용 여부 |
| `toss-invest.websocket.url` | `wss://openapi-ws.tossinvest.com/ws/v1` | 공급자 WebSocket URL |
| `toss-invest.websocket.market` | `us` | 구독 type에 붙는 시장 |
| `toss-invest.websocket.connection-slots` | 2 | 계정 전체 연결 슬롯 수 |
| `toss-invest.websocket.max-symbols-per-connection` | 100 | 연결별 최대 구독 종목 수 |
| `toss-invest.websocket.declare-debounce-ms` | 250 | 구독 재계산·선언 간격 |
| `toss-invest.websocket.ping-interval-seconds` | 60 | keepalive `PING` 간격 |
| `toss-invest.websocket.slot-lock-ttl-ms` | 15000 | 연결 슬롯 Redis lock TTL |
| `toss-invest.websocket.slot-heartbeat-ms` | 5000 | 슬롯 획득·갱신 간격 |
| `toss-invest.websocket.rejected-symbol-ttl-ms` | 600000 | 거절 종목 재시도 대기 시간 |
| `market-calendar.cache.ttl-hours` | 12 | 장 운영정보 cache TTL |
| `orderbook.cache.ttl-seconds` | 120 | 호가 cache TTL. `orderbook.polling.staleness-threshold-ms`보다 커야 한다 (§13.1) |
| `orderbook.polling.fixed-delay-ms` | 1000 | 미체결 주문 종목 호가 polling 간격 |
| `orderbook.polling.idle-fixed-delay-ms` | 20000 | 구독-only(미체결 주문 없는) 종목 호가 polling 간격 |
| `orderbook.polling.lock-ttl-ms` | 900 | 호가 polling lock TTL |
| `orderbook.polling.staleness-threshold-ms` | 30000 | WebSocket 담당 cache의 REST fallback 기준 |
| `orderbook.active-symbols.refresh-ms` | 5000 | 로컬 STOMP 호가 구독 heartbeat |
| `orderbook.active-symbols.ttl-ms` | 30000 | 전역 활성 구독 종목 만료 기준 |
| `price.cache.ttl-seconds` | 2 | 현재가 cache TTL |
| `price.polling.fixed-delay-ms` | 1000 | 현재가 polling 간격 |
| `price.polling.lock-ttl-ms` | 900 | 현재가 polling lock TTL |
| `daily-price-range.cache.ttl-seconds` | 5 | 일일 고저가 cache TTL |
| `daily-price-range.polling.fixed-delay-ms` | 1000 | 일일 고저가 polling 간격 |
| `daily-price-range.polling.lock-ttl-ms` | 900 | 일일 고저가 polling lock TTL |
| `matching-engine.polling.fixed-delay-ms` | 100 | 신규 Stream 소비 간격 |
| `matching-engine.dirty-drain.fixed-delay-ms` | 150 | dirty set drain 간격 |
| `matching-engine.dirty-drain.batch-size` | 20 | drain cycle당 최대 종목 수 |
| `matching-engine.rematch.fixed-delay-ms` | 30000 | 안전망 이벤트 간격 |
| `matching-engine.lock.ttl-ms` | 15000 | 종목 매칭 락 TTL |
| `matching-engine.stream.batch-size` | 10 | 신규 Stream batch 크기 |
| `matching-engine.stream.pending-batch-size` | 20 | PEL 복구 batch 크기 |
| `matching-engine.stream.pending-min-idle-ms` | 5000 | claim 대상 최소 idle 시간 |
| `matching-engine.stream.max-retry-count` | 5 | DLQ 전 최대 실패 횟수 |
| `matching-engine.stream.pending-recovery-delay-ms` | 1000 | PEL 복구 간격 |
| `matching-engine.stream.max-length` | 1000000 | source Stream 최대 길이 |
| `matching-engine.stream.dlq-max-length` | 100000 | DLQ Stream 최대 길이 |

`application.yaml`에는 datasource, Redis, JWT, Toss OAuth2·호출량·WebSocket, 장 운영정보, 호가, scheduler pool, dirty drain, 기본 매칭 lock/rematch 설정이 선언되어 있다. `TOSS_INVEST_CLIENT_ID`와 `TOSS_INVEST_SECRET_TOKEN`의 기본값은 빈 문자열이어서 실제 외부 호출에는 환경 설정이 필요하다. 현재가, 일일 고저가, Stream 세부 설정은 코드의 placeholder 기본값으로 동작하며 환경변수 또는 Spring property로 덮어쓸 수 있다.

스케줄 작업은 다음처럼 격리한다.

| Scheduler bean | Thread 수 | 담당 작업 |
| --- | ---: | --- |
| `taskScheduler` | 4 | 활성 호가 종목 heartbeat, Stream 신규/PEL 소비, 30초 안전망 등 기본 작업 |
| `marketDataPollingScheduler` | 3 | 호가·현재가·일일 고저가 REST polling |
| `dirtyDrainScheduler` | 1 | dirty set drain과 종목 매칭 |
| `webSocketScheduler` | 1 고정 | Toss 슬롯 생애주기, 구독 배정·선언, ping |

특히 `webSocketScheduler`는 연결 종료와 재연결이 동시에 실행되지 않도록 단일 thread를 사용한다.

## 17. 테스트와 현재 빌드 상태

### 17.1 테스트 구성

테스트는 두 층이다.

| 층 | 실행 방식 | 대상 |
| --- | --- | --- |
| 단위 테스트 | Mockito. 외부 의존 없음 | 분기 로직, 상태 전이, 스케줄 판단 |
| 통합 테스트 | Testcontainers PostgreSQL·Redis | JPQL·DB 제약·row lock·context load |

통합 테스트에 H2나 embedded DB를 쓰지 않는다. 검증해야 할 것이 대부분 PostgreSQL 고유 동작이기 때문이다 — `PESSIMISTIC_WRITE`의 실제 차단, `@Check`·unique 제약의 위반 시점, 데드락 감지. 운영과 다른 엔진에서는 "동시에 들어와도 예수금을 넘지 못한다" 같은 명제를 증명할 수 없다.

### 17.2 통합 테스트 기반

`src/test/java/.../support/`에 세 가지가 있다.

| 구성요소 | 역할 |
| --- | --- |
| `IntegrationTestContainers` | PostgreSQL·Redis 컨테이너를 `static` 초기화로 JVM당 한 번 띄우고 `@DynamicPropertySource`로 접속 정보를 등록한다. Spring이 관리하는 bean으로 두면 test context마다 뜨고 지므로 기동 비용이 곱절이 된다 |
| `@JpaIntegrationTest` | `@DataJpaTest` + `AutoConfigureTestDatabase(replace = NONE)` + `integration` profile. `@DataJpaTest`가 DataSource를 embedded로 바꿔치기하는 것을 막는다 |
| `@ApplicationIntegrationTest` | `@SpringBootTest` + `integration` profile. context 전체가 뜨므로 Redis도 필요하다 — `MatchingEngineStreamConsumer`가 `@PostConstruct`에서 consumer group을 만든다 |

통합 테스트는 `IntegrationTestContainers`를 상속하고 둘 중 하나를 붙인다.

`src/test/resources/application-integration.yaml`이 schema 생성(`ddl-auto: update`)과 background 작업 차단을 맡는다. `create-drop`이 아닌 이유는 컨테이너를 여러 test context가 공유하는데 한 context가 닫히며 schema를 지우면 cache된 다른 context가 테이블을 잃기 때문이다. 컨테이너가 JVM마다 새로 뜨므로 stale schema는 생기지 않는다.

### 17.3 현재 테스트

21개 test class에 97개 test가 있다.

| Test class | 건수 | 층 | 검증 범위 |
| --- | ---: | --- | --- |
| `PaperTradingApplicationTests` | 1 | 통합 | Spring application context load |
| `OrderMatchingQueryIntegrationTest` | 5 | 통합 | 매칭 후보 제외 목록, 가격 우선순위, 스윕 순서, 주문 수량 `@Check` |
| `ActiveOrderBookSymbolRegistryTests` | 3 | 단위 | 미체결 종목의 최초 접수 순서 보존, 누락 stock 처리 |
| `OrderBookPollingServiceTests` | 3 | 단위 | WebSocket 담당 여부와 cache 신선도에 따른 REST fallback |
| `OrderBookMatchingGateTests` | 5 | 단위 | 구독 종목의 cache 사용, 미구독 종목의 신선도 규칙, 빈 registry |
| `DirtyOrderBookSymbolDrainSchedulerTests` | 5 | 단위 | 락 경합 재등록, quota hot loop 방지, batch 실패 격리·복원 |
| `SymbolMatchingProcessorTests` | 4 | 단위 | 종목 lock, 결과 분류, 예외 전파, 단일 sweep |
| `MatchingEngineStreamConsumerTests` | 3 | 단위 | 구형 호가 이벤트 이관, lock busy PEL 유지, quota ACK |
| `MatchingEngineTransactionServiceTests` | 2 | 단위 | 주문별 비즈니스 예외 격리와 시스템 예외 전파 |
| `OrderFillLedgerIntegrityTests` | 6 | 단위 | 부분 체결 생존, 보유·잔고 캡, 체결 불가 상대 건너뛰기, 거절 판정 |
| `OrderPlacementReservationIntegrationTest` | 12 | 통합 | 예수금 초과 주문 거절, 동시 접수 경합, 취소 후 회복, 시장가 구속 단가, 매도가능수량, 주문가격 밴드 |
| `LedgerReconciliationIntegrationTest` | 5 | 통합 | 대사 정상 판정, 잔고 조작 탐지, 분개 삭제 탐지, 자동 복구하지 않음 |
| `SelfTradeAndConstraintIntegrationTest` | 7 | 통합 | 자전거래 차단, 정상 내부 체결 유지, 교차 종목 동시 매칭 데드락 부재, 큰 주문의 완전 체결, 음수 잔고·보유 DB 거부 |
| `PriceTimePriorityIntegrationTest` | 3 | 통합 | 먼저 접수된 주문이 공급 전량 선점, 비싼 매수 우선, 매도 방향 대칭 |
| `ValuationServiceTests` | 6 | 단위 | 총자산·평가손익 계산, 예수금 제외 수익률, 시세 미확보·다중 통화 시 생략 |
| `TotalAssetValuationIntegrationTest` | 5 | 통합 | total_asset_value 갱신, 예수금 미훼손, 종목당 1회 조회, 잔고 화면, 시세 장애 시 부분 응답 |
| `LedgerIntegrityIntegrationTest` | 7 | 통합 | 개시 분개, 잔고의 원장 재구성, 거래 단위 균형, 전역 균형, 멱등키, 체결·원장 연결 |
| `OrderRejectionTests` | 3 | 단위 | `REJECTED`/`CANCELED` 전이와 사유·체결 수량 보존 |
| `SymbolSubscriptionRegistryTests` | 3 | 단위 | 다중 구독, subscription 이동, disconnect 정리 |
| `TossOrderBookWebSocketManagerTests` | 7 | 단위 | 정원, 우선순위, 끈끈한 슬롯, 해제, 거절 종목 제외, 선언 registry 비우기 |
| `TossOrderBookWebSocketPayloadTests` | 2 | 단위 | 실제 push 형태의 DTO 변환과 null timestamp |

인증, Toss OAuth2/REST client, rate limiter Lua, cache·Pub/Sub, 실제 체결 transaction과 row lock, Redis Stream PEL claim·재시도·DLQ는 아직 자동화된 테스트가 없다.

### 17.4 벤치마크

`src/test/java/.../benchmark/`의 트랜잭션 경계 처리량 측정은 통과/실패를 가리는 test가 아니고 `docker compose`를 55432/56379로 직접 띄운 환경을 전제한다. 그래서 `test` task에서 제외하고 전용 task로 분리했다.

```bash
./gradlew benchmarkTest
```

### 17.5 현재 빌드 상태

2026-09-12 기준 `./gradlew test --rerun-tasks`는 **97건 전부 통과**한다. Testcontainers를 쓰므로 실행 환경에 Docker가 필요하다.

컴파일러는 `MatchingEngineStreamConsumer`의 unchecked/unsafe operation을 계속 경고한다. `OrderBookMatchingGateTests`도 `ValueOperations` mock의 generic 때문에 같은 경고를 낸다.

## 18. 현재 구현 경계

다음 항목은 엔티티나 문서에는 정의되어 있지만 완성된 사용자 기능으로 연결되어 있지 않다.

- 회원가입 시 1:1 계좌 자동 생성. `AccountOpeningService.open()`은 있으나 회원가입에 연결되어 있지 않다
- 투자금 추가 신청 API
- 하루 1회 투자금 신청 제한
- 초기화 이후 누적 신청금 5,000,000 제한 계산
- 누적 수익률, 누적 수익금, 누적 신청금 초기화 API
- 보유 자산(종목별 평가) 조회 API — 잔고·주문가능금액 조회는 `GET /api/v1/accounts/me/balance`로 구현됨
- 일별 계좌 snapshot batch. `DailyAccountSnapshot`의 `stock_evaluation`·`unrealized_profit`·`return_rate`가 시가 평가를 요구하는데 평가 batch가 아직 없다 (§13.12 대사는 원장 파생만으로 동작하므로 이것과 무관하게 이미 돈다)
- 리더보드 계산 batch와 조회 API
- 국내/미국 종목 마스터 적재
- 환율 API client, cache, 저장과 적용
- 주문 목록 및 단건 조회 API
- DLQ 검색, replay, 삭제 관리 API
- dirty-set metric의 외부 scrape endpoint 노출과 dashboard/alert 구성
- 수수료와 세금의 실제 현금 반영 정책. 원장 모델(FEE/TAX 분개)과 차감 경로는 있으나 계산기가 0을 반환한다. 0이 아닌 값으로 바꾸려면 체결 시점 잔고 캡이 수수료를 고려하도록 함께 고쳐야 한다
- 자기 계좌 간 자전거래 차단과 주문가격 제한폭 검증
- DB migration 또는 schema provisioning 도구

## 19. 현재 구조에서 주의할 점

### 19.1 신규 사용자의 주문 가능 여부

회원가입이 계좌를 만들지 않지만 주문 접수는 반드시 사용자 계좌를 조회한다. 별도의 seed나 외부 계좌 생성 과정이 없다면 신규 가입자는 `계좌를 찾을 수 없습니다.` 오류로 주문할 수 없다.

### 19.2 총자산 평가와 잔고 화면

`Account.totalAssetValue`는 `TotalAssetValuationScheduler`가 `valuation.total-asset.fixed-delay-ms`(기본 60초) 주기로 갱신한다.

```text
종목 평가액 = holdings.quantity × 현재가
총자산      = accounts.cash_balance + Σ(종목 평가액)
평가손익    = 평가액 − Σ(holdings.total_purchase_amount)
```

**평가액은 원장 파생이 아니다.** 시가는 외부에서 온 값이고 같은 보유라도 어제와 오늘이 다르므로 대사로 검증할 수 없다(§13.12). 입력인 보유 수량과 예수금은 원장 파생이라 그쪽은 검증된다.

#### 계산하지 않는 경우

평가를 만들지 않고 `null`로 두는 경우가 둘이다. 일부 종목만 반영한 총자산은 틀린 값이고, 틀린 값을 보여주는 것보다 "계산 중"이 낫다.

- **시세를 구하지 못한 종목이 하나라도 있을 때**
- **보유 종목의 통화가 둘 이상일 때** — `Account`에 통화 개념이 없어 USD 평가액과 KRW 예수금을 그냥 더하게 된다. 지금은 거래 가능한 종목이 전부 USD라 성립하지만 국내 종목이 들어오는 순간 조용히 틀린 값이 나온다. 그래서 주석이 아니라 코드로 막는다. 환율 client가 들어오면 이 가드를 환산으로 바꾼다

#### 시세는 종목당 한 번만 조회한다

`PriceService.getPrices()`가 캐시에 없는 종목만 묶어 한 번의 Toss 호출로 가져온다. 계좌마다 부르면 호출 수가 계좌 수에 비례하는데, 같은 종목을 여러 계좌가 들고 있어도 시세는 하나다. 그래서 전체 보유의 distinct 종목을 한 번에 넘긴다.

#### 갱신은 단일 컬럼 update로 한다

엔티티를 읽어 필드를 바꾸는 대신 `update accounts set total_asset_value = ?`를 쓴다. 평가는 **락을 잡지 않으므로**, 엔티티를 통째로 flush하면 그 사이 체결이 바꾼 `cash_balance`를 오래된 값으로 되돌릴 수 있다.

락을 잡지 않는 이유는 이 값이 표시용이기 때문이다. 잠그면 평가가 체결과 주문 접수를 막는다. 잠깐 어긋나도 다음 주기에 맞는다.

#### 잔고 화면은 batch 값을 쓰지 않는다

`GET /api/v1/accounts/me/balance`는 `total_asset_value` 컬럼이 아니라 **조회 시점의 시세로 다시 계산한다.** 그 컬럼은 분 단위 캐시라 사용자가 보는 순간의 값과 어긋날 수 있다.

**평가손익률의 분모는 총매입금액이다 — 예수금을 포함하지 않는다.** 현업 증권사 잔고 화면과 같은 기준이고, 리더보드의 "수익률"(예수금 포함, 12번 계획)과는 다른 값이다.

| 위치 | 이름 | 분모 |
| --- | --- | --- |
| 잔고 화면 | 평가손익률 | 총매입금액 |
| 리더보드 | 수익률 | 시즌 시작 자본 |

두 값에 같은 이름을 붙이면 사용자가 버그로 신고한다.

시세를 구하지 못해도 잔고 조회는 실패하지 않는다. 예수금·주문가능금액·총매입금액은 시세와 무관하므로 그대로 응답하고 평가 관련 필드만 `null`이 된다.

### 19.3 외부 호가 소비 정책

**이건 결함이 아니라 결정이다.** 외부 호가와 내부 주문을 다르게 다루는 것은 구현이 어려워서가 아니라 성질이 다르기 때문이다.

| 범위 | 소비 | 근거 |
| --- | --- | --- |
| **한 주문 안** | 소비한다 | 주문 크기에 따른 슬리피지를 재현한다. 시장가 100주인데 1단계에 10주뿐이면 나머지는 위 단계에서 체결돼야 한다 |
| **주문과 주문 사이** | 소비하지 않는다 | 시뮬레이션 사용자의 주문은 실제 시장에 나가지 않는다 |
| **내부 주문** | 엄격히 소비한다 | 다른 사용자의 주문은 이 서비스 안에서 진짜 유동성이다 |

#### 주문 사이에 소비하지 않는 이유

우리 사용자가 모의로 100주를 사도 **토스 호가창의 물량은 줄지 않는다.** 주문이 실제 거래소로 나가지 않기 때문이다.

그런데 사용자 B에게 "A가 먼저 먹었으니 너는 비싸게 사라"고 하면, **실제로 일어나지 않은 일로 B를 벌주는 것**이 된다. 외부 호가는 "우리가 나눠 먹는 풀"이 아니라 **각 주문이 마주하는 그 시점의 시장 깊이**다.

사용자 수가 늘어도 이 성질은 변하지 않는다. 아무도 남 때문에 불리해지지 않으므로 불공정이 생기지 않고, 모두가 같은 규칙을 적용받는다.

#### 한 주문 안에서는 반드시 소비해야 하는 이유

슬리피지는 모의투자가 가르쳐야 할 핵심 중 하나다. 큰 주문이 불리한 평균단가로 체결되는 경험이 빠지면 시뮬레이터의 교육적 가치가 크게 줄어든다. `MatchCursor`가 호가 level 소비 위치를 들고 다니는 이유가 이것이다.

주문 수량이 호가 전체 깊이를 넘으면 나머지는 미체결로 남고, 다음 매칭 트리거에서 **새 snapshot**을 받아 다시 소비한다. 큰 주문이 시간에 걸쳐 체결되는 것과 같은 모양이다.

#### 원장은 깨지지 않는다

외부 호가 체결의 분개는 `CASH −금액` / `SECURITIES +금액`으로 합계가 0이고 대사를 통과한다. 사용자는 실제로 현금을 냈고 실제로 보유가 늘었다. "존재하지 않는 유동성"이라는 표현은 원장 관점에서 맞지 않는다 — 이건 정합성 문제가 아니라 **체결 현실성**의 문제이고, 위 정책이 그 답이다.

#### 실시간 체결 정보를 구독하지 않는 이유

토스는 WebSocket으로 개별 종목의 실시간 체결 정보를 제공한다. 그걸 받으면 외부 유동성 소비를 추적할 수 있을 것 같지만, 그렇지 않다.

- 구독 슬롯은 종목 정원(연결당 100 × 슬롯 수)을 호가와 **나눠 쓴다.** 체결 정보에 쓰면 실시간 호가를 받을 종목 수가 줄어든다.
- 그리고 체결 정보가 알려주는 것은 **실제 시장**의 소비량인데, 그건 이미 다음 호가 push에 반영돼 있다. 실제로 물량이 나가면 수량이 줄어든 호가가 온다.
- 우리가 추가로 차감하고 싶었던 것은 **우리 사용자의 가짜 소비**인데, 위 정책에 따르면 그건 차감하면 안 되는 값이다.

즉 데이터를 더 받아도 얻을 것이 없고 커버리지만 잃는다.

#### 이 정책이 의존하는 것

**snapshot 신선도다.** 낡은 호가로 체결하면 지금은 없는 가격에 사고팔 수 있다. 이건 소비 정책과 무관하게 성립해야 하는 요건이고, §13.1의 신선도 규칙과 WebSocket push가 담당한다. 신선도가 무너지면 이 정책의 "각 주문이 마주하는 그 시점의 시장 깊이"에서 **"그 시점"이 거짓이 된다.**

### 19.4 수수료 전략 확장 시 현금 반영

현재 수수료와 세금은 항상 0이어서 계좌 잔고 결과에 영향이 없다. 향후 `CommissionCalculator`가 0이 아닌 값을 반환하더라도 현재 코드는 `Execution`에 값만 저장하고 계좌 현금 차감 및 별도 `COMMISSION`, `TAX` 원장을 만들지 않는다.

### 19.5 Redis와 DB 사이의 원자성

주문은 DB commit 후 callback으로 Redis Stream에 발행한다. rollback 주문의 이벤트 발행은 막지만, DB commit 직후 Redis 발행이 실패하는 구간을 완전히 원자적으로 묶지는 않는다. 현재는 30초 안전망 스케줄러가 미체결 주문 종목을 다시 발행해 이 구간을 보완한다. Transactional outbox는 구현되어 있지 않다.

### 19.6 공급자 WebSocket의 다중 인스턴스 배정

Redis 슬롯 lock은 계정의 동시 WebSocket 연결 수를 2개로 제한하지만, `slotBySymbol` 배정 map은 각 `TossOrderBookWebSocketManager`의 메모리에만 있다. 서로 다른 애플리케이션 인스턴스가 슬롯을 하나씩 소유하면 두 manager가 전역 우선순위 목록의 같은 상위 종목을 각각 선택할 수 있어 연결 간 구독이 중복되고 최대 200종목 정원을 모두 활용하지 못한다.

현재 배정 알고리즘은 한 애플리케이션 인스턴스가 두 슬롯을 모두 가진 구성을 사실상 전제로 한다. 다중 인스턴스에서 2개 슬롯을 나눠 소유하려면 전역 배정 map 또는 단일 배정자를 Redis 등으로 공유해야 한다.

### 19.7 공급자 WebSocket `server-shutdown` 처리

공급자 error frame의 code가 `server-shutdown`이면 현재 connection 객체의 `close()`가 `closed=true`로 영구 폐기한다. 그러나 manager의 `connections` map과 슬롯 lock은 그대로 유지되므로, lock heartbeat가 계속 성공하는 동안 `ensureConnected()`는 이 객체를 재연결하지 않는다. 해당 슬롯은 애플리케이션 재시작이나 슬롯 소유권 상실 전까지 REST fallback에 의존할 수 있다.

### 19.8 실행과 운영 구성

`application.yaml`은 localhost PostgreSQL/Redis 접속 기본값을 제공하고, `docker-compose.yml`은 PostgreSQL 17, Redis 7, 애플리케이션 컨테이너를 함께 실행할 수 있게 구성되어 있다. Compose의 app은 로컬 검증을 위해 `SPRING_JPA_DDL_AUTO=update`와 `TOSS_WS_ENABLED=false`를 기본 사용한다. `Dockerfile`은 Java 21 multi-stage build로 test를 제외하고 boot JAR를 만든 뒤 non-root 사용자로 실행한다.

다만 migration 도구와 CI 설정은 없다. 통합 테스트가 Docker를 요구하므로 CI를 붙일 때 Docker 사용 가능 여부를 먼저 확인해야 한다. 애플리케이션 자체의 `ddl-auto` 기본값은 `none`이므로 Compose 밖의 실제 환경에서는 schema를 별도로 준비해야 한다. `orders.rejected_at`, `orders.reject_reason`(§13.8), `orders.reserved_unit_price`와 index `idx_orders_account_side_status`(§13.11)가 최근 추가되었으므로 기존 schema에는 별도로 적용해야 한다. 운영에서는 PostgreSQL·Redis, Toss client ID/secret, 허용 IP, 충분히 강한 JWT secret도 별도로 구성해야 한다.
