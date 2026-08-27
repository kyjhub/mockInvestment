# mockInvestment

> 🚧 현재 개발 중인 프로젝트로, 기능과 API 명세가 변경될 수 있습니다.

미국주식 모의투자 서비스입니다. 시세는 토스증권 Open API에서 실시간으로 받아오고, 주문 체결은 자체 매칭 엔진이 처리합니다.

실제 돈이 오가지 않는 환경에서 실제 시세로 거래를 경험하는 것이 목표이고, 다음 세 가지가 핵심입니다.

- **실시간 시세 전파** — 토스 WebSocket 수신(호가) 및 REST 폴링 → Redis 캐시 → Pub/Sub 팬아웃 → STOMP WebSocket 푸시
- **종목 단위 비동기 매칭** — Redis Stream 기반 이벤트 처리, 재시도와 DLQ 포함
- **내부·외부 유동성 동시 체결** — 사용자끼리의 주문 체결과 토스 호가를 상대로 한 체결을 함께 지원

## 기술 스택

| 영역 | 사용 기술 |
| --- | --- |
| 애플리케이션 | Java 21, Spring Boot 3.5 |
| HTTP API | Spring Web MVC |
| 인증·인가 | Spring Security, JWT (jjwt), BCrypt |
| 영속성 | Spring Data JPA, PostgreSQL |
| 캐시·분산 상태 | Spring Data Redis |
| 이벤트 처리 | Redis Stream (Consumer Group, PEL, DLQ) |
| 서버 간 전파 | Redis Pub/Sub |
| 클라이언트 전파 | STOMP over WebSocket |
| 외부 시세 | 토스증권 Open API (REST + WebSocket) |
| 외부 인증 | OAuth 2.0 Client Credentials |
| 직렬화 | Jackson 2 |

## 아키텍처

```text
                    HTTP / STOMP WebSocket
                             |
                             v
                Controller / Spring Security
                             |
                             v
                          Service
                             |
     +-----------------------+-----------------------+
     |                       |                       |
     v                       v                       v
 PostgreSQL                Redis            토스증권 Open API
 사용자, 계좌               시세 캐시            REST: 호가, 현재가
 주문, 체결                 호출량 제한                일봉, 장 운영정보
 보유량, 현금 원장          분산 락, WS 슬롯      WS: 실시간 호가
                           access token 캐시
                           활성 종목 집합
                           시세 Pub/Sub
                           매칭 Stream, DLQ
```

관계형 데이터베이스는 주문과 자산 변동의 원본 데이터를 담당하고, Redis는 빠르게 바뀌거나 여러 서버가 공유해야 하는 운영 상태를 담당합니다. 실시간 시세 틱은 DB에 적재하지 않고 Redis 최신값 캐시로만 유지합니다.

## 주요 기능

### 인증

JWT 기반 stateless 인증입니다. Access token 30분, refresh token 14일이 기본값이고, refresh token은 해시해서 저장하며 재발급 시 회전합니다. `/api/v1/auth/**`, `/ws/**`, `/actuator/health`를 제외한 모든 경로는 인증이 필요합니다.

### 실시간 시세

호가, 현재가, 일일 고저가 세 종류가 모두 Redis 캐시와 Pub/Sub를 거쳐 클라이언트에 전달됩니다. 캐시를 채우는 방법만 데이터별로 다릅니다.

```text
[호가]      토스 WebSocket 푸시 ─┐
[호가 폴백]  REST 폴링 ──────────┼→ Redis 캐시 → Redis Pub/Sub → STOMP 푸시
[현재가]    REST 배치 폴링 ──────┤
[고저가]    REST(캔들) + 자체 체결가 ─┘
```

호가는 REST 응답과 WebSocket 푸시가 `OrderBookService.applyOrderBook()` 한 지점으로 합류합니다. 그래서 캐시 저장, 변경 감지, Pub/Sub 발행, 매칭 트리거가 공급원과 무관하게 동일하게 동작합니다.

**호가 — 토스 WebSocket**

- 토스 한도가 계정당 연결 2개, 연결당 구독 100건이라 최대 **200종목**을 실시간으로 받습니다.
- Redis 락으로 연결 슬롯을 점유한 인스턴스만 연결합니다. 그러지 않으면 새 연결이 기존 연결을 밀어내는 flapping이 발생합니다.
- 구독 선언은 full-replace 방식이라 담당 종목이 바뀔 때마다 전체를 다시 선언하며, 선언 빈도 제한(5회/초)에 맞춰 250ms 디바운스를 둡니다.
- 활성 종목이 200개를 넘으면 미체결 주문이 있는 종목이 슬롯을 우선 차지하고, 나머지는 REST 폴링으로 내려갑니다.

**호가 — REST 폴백**

- 폴링 대상 판정 기준은 "WebSocket 담당 종목인가"가 아니라 **"캐시가 신선한가"** 입니다. WebSocket이 멎으면 자동으로 폴백되고, 푸시가 재개되면 자동으로 빠집니다.
- 임계값(기본 30초)이 WebSocket 재연결 시간(2~3초)보다 넉넉해서, 짧은 단절에는 폴백이 발동하지 않습니다.
- 분산 락으로 여러 인스턴스가 같은 종목을 중복 호출하지 않게 합니다.

**현재가와 일일 고저가**

- 현재가는 한 번에 최대 200종목을 조회하는 배치 API를 써서 종목 수와 무관하게 초당 1~2회로 끝납니다. 실시간 대비 수 초 지연될 수 있습니다.
- 일일 고저가는 캔들 API로 채우고, 이후에는 자체 체결가로 갱신하므로 API 호출은 캐시 미스 때만 발생합니다.

### 토스 API 인증

OAuth 2.0 client credentials로 발급받은 access token을 사용합니다. 토큰 수명이 24시간이라 Redis에 캐싱해 인스턴스 간에 공유하고, 만료 10분 전에 캐시를 버려 재발급합니다. 401을 받으면 재발급 후 1회만 재시도합니다. WebSocket handshake도 같은 토큰을 쓰지만 인증이 연결 시점 1회뿐이라, 연결 유지 중 토큰이 만료돼도 끊기지 않습니다.

> 토스는 **허용 IP 목록**을 REST와 WebSocket 양쪽에 적용합니다. 등록되지 않은 IP는 토큰 발급 단계에서 `403`으로 거부되므로, 실행 환경의 공인 IP를 WTS 설정 > Open API > 허용 IP 관리에 등록해야 합니다.

### 토스 API 호출량 제한

토스 API는 그룹 단위 초당 호출 한도가 있어서, Redis 기반 분산 rate limiter가 예산을 관리합니다. 그룹 구성과 한도는 토스 스펙을 그대로 따릅니다.

| 그룹 | 대상 API | 초당 한도 |
| --- | --- | --- |
| `market-data` | 호가, 현재가, 최근 체결, 상/하한가 | 15 |
| `market-data-chart` | 캔들 | 20 |
| `market-info` | 장 운영정보, 환율 | 3 |

여기에 안전마진(기본 0.8)을 곱한 값을 실효 한도로 씁니다. 정기 폴링, REST 캐시 미스, 매칭 엔진 캐시 미스 세 경로가 모두 같은 지점을 거치므로 우회가 없습니다. 429 응답 헤더로 실제 한도를 학습하고, 예산이 소진되면 WebSocket 푸시 채널이 있는 그룹(`market-data`, `market-data-chart`)은 `202 Accepted`(pending 응답, 실제 데이터는 WebSocket으로 전달)를, 푸시 채널이 없는 `market-info`는 `503`을 `Retry-After`와 함께 반환합니다.

호가를 WebSocket으로 받으면서 `market-data` 예산 대부분이 남으므로, 200종목 초과분과 신규 종목 조회에 그 여유가 쓰입니다.

### 주문과 매칭

```text
POST /api/v1/orders
      |
      v
  DB commit  --(afterCommit)-->  Redis Stream: symbols:match-requested
                                              |
                                              v
                            MatchingEngineStreamConsumer
                            (100ms 소비 · PEL 복구 · 재시도 5회 → DLQ)
                                              |
                                              v
                              종목 분산 락 → matchSymbol()
                              (호가 version 안정화 루프)
```

- **멱등성** — `clientOrderId`를 보내면 계좌 안에서 유일성이 보장되고, 같은 값으로 재요청하면 기존 주문과 체결 내역을 그대로 반환합니다.
- **동시성** — 주문·계좌·보유 row에 비관적 락을 걸고, 외부 API 호출은 트랜잭션 밖에서 수행합니다.
- **호가 신선도** — 주문의 접수 시각보다 오래된 호가로는 체결하지 않습니다.
- **안전망** — Redis 발행이 유실되어도 30초 주기 스케줄러가 미체결 주문 종목을 다시 발행합니다.

## API

모든 응답은 JSON이고, 인증이 필요한 경로는 `Authorization: Bearer {accessToken}` 헤더를 사용합니다.

### 인증

| Method | Endpoint | 설명 |
| --- | --- | --- |
| `POST` | `/api/v1/auth/signup` | 회원가입 후 토큰 발급 (`email`, `password`, `nickname`) |
| `POST` | `/api/v1/auth/login` | 로그인 후 토큰 발급 (`email`, `password`) |
| `POST` | `/api/v1/auth/refresh` | Refresh token으로 재발급 (회전) |
| `POST` | `/api/v1/auth/logout` | Refresh token 폐기 |

### 시세

| Method | Endpoint | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/orderbook?symbol=` | 매수·매도 호가 |
| `GET` | `/api/v1/prices?symbols=` | 현재가 (쉼표로 여러 종목) |
| `GET` | `/api/v1/daily-price-range?symbol=` | 일일 고가·저가 |
| `GET` | `/api/v1/market-calendar/US?date=` | 미국 장 운영정보 (`date` 생략 시 오늘) |

### 주문

| Method | Endpoint | 설명 |
| --- | --- | --- |
| `POST` | `/api/v1/orders` | 주문 접수 후 비동기 매칭 요청 |
| `DELETE` | `/api/v1/orders/{orderId}` | 미체결·부분 체결 주문 취소 |

```jsonc
// POST /api/v1/orders
{
  "clientOrderId": "my-order-001",  // 선택, 계좌 내 유일
  "symbol": "005930",
  "side": "BUY",                    // BUY | SELL
  "orderType": "LIMIT",             // LIMIT | MARKET
  "price": 70000,                   // LIMIT은 필수, MARKET은 불가
  "quantity": 10
}
```

### WebSocket

STOMP 엔드포인트는 `/ws`이고, 브로커 prefix는 `/topic`입니다.

| 토픽 | 내용 |
| --- | --- |
| `/topic/orderbook/{symbol}` | 호가 갱신 |
| `/topic/prices/{symbol}` | 현재가 갱신 |
| `/topic/daily-price-range/{symbol}` | 일일 고저가 갱신 |

구독하면 해당 종목이 폴링 대상에 자동으로 추가되고, 구독이 모두 끊기면 제외됩니다.

## 실행 방법

### 요구 사항

- JDK 21
- PostgreSQL
- Redis
- 토스증권 Open API secret token

### 환경 변수

저장소에는 datasource 설정이 들어 있지 않으므로 실행 환경에서 직접 지정해야 합니다.

```bash
# 필수
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/papertrading
export SPRING_DATASOURCE_USERNAME=...
export SPRING_DATASOURCE_PASSWORD=...
export TOSS_INVEST_CLIENT_ID=...       # OAuth2 client_id
export TOSS_INVEST_SECRET_TOKEN=...    # OAuth2 client_secret
export JWT_SECRET=...   # 기본값은 로컬 개발용이므로 운영에서는 반드시 교체

# 선택 (기본값 있음)
export REDIS_HOST=localhost
export REDIS_PORT=6379
export TOSS_WS_ENABLED=true            # false면 호가도 REST 폴링으로만 처리
```

`TOSS_INVEST_SECRET_TOKEN`은 Bearer 토큰이 아니라 OAuth2 `client_secret`입니다. 이 값을 그대로 `Authorization` 헤더에 넣으면 `401 invalid-token`이 납니다.

### 빌드와 실행

```bash
./gradlew build
./gradlew bootRun
```

DB 마이그레이션 도구가 아직 없어서 스키마는 별도로 준비해야 합니다. 로컬에서는 `spring.jpa.hibernate.ddl-auto`로 대신할 수 있습니다.

### 테스트

```bash
./gradlew test
```

PostgreSQL datasource가 없으면 `contextLoads()`가 실패합니다. 나머지 단위 테스트는 외부 의존성 없이 동작합니다.

## 설정

주요 값과 기본값입니다. 전체 목록은 [`docs/current-implementation-overview.md`](docs/current-implementation-overview.md) §16을 참고하세요.

| 설정 | 기본값 | 설명 |
| --- | --- | --- |
| `security.jwt.access-token-expiration-minutes` | 30 | Access token 만료 |
| `security.jwt.refresh-token-expiration-days` | 14 | Refresh token 만료 |
| `toss-invest.rate-limit.market-data.default-limit` | 15 | 호가·현재가 그룹 초당 한도 |
| `toss-invest.rate-limit.market-data-chart.default-limit` | 20 | 캔들 그룹 초당 한도 |
| `toss-invest.rate-limit.market-info.default-limit` | 3 | 장 운영정보 그룹 초당 한도 |
| `toss-invest.websocket.enabled` | true | 호가 WebSocket 수신 사용 여부 |
| `toss-invest.websocket.connection-slots` | 2 | 계정당 동시 연결 수 (토스 한도) |
| `toss-invest.websocket.max-symbols-per-connection` | 100 | 연결당 구독 종목 수 (토스 한도) |
| `toss-invest.websocket.declare-debounce-ms` | 250 | 구독 재선언 디바운스 |
| `toss-invest.websocket.ping-interval-seconds` | 60 | keepalive PING 주기 |
| `orderbook.cache.ttl-seconds` | 30 | 호가 캐시 TTL |
| `orderbook.polling.fixed-delay-ms` | 1000 | 미체결 주문 종목 폴링 주기 |
| `orderbook.polling.idle-fixed-delay-ms` | 20000 | 구독만 있는 종목 폴링 주기 |
| `orderbook.polling.staleness-threshold-ms` | 30000 | 이보다 캐시가 낡아야 폴링 대상 |
| `price.cache.ttl-seconds` | 2 | 현재가 캐시 TTL |
| `daily-price-range.cache.ttl-seconds` | 5 | 일일 고저가 캐시 TTL |
| `market-calendar.cache.ttl-hours` | 12 | 장 운영정보 캐시 TTL |
| `matching-engine.lock.ttl-ms` | 15000 | 종목 매칭 락 TTL |
| `matching-engine.rematch.fixed-delay-ms` | 30000 | 안전망 재매칭 주기 |
| `matching-engine.stream.max-retry-count` | 5 | DLQ 이동 전 최대 재시도 |

`toss-invest.websocket.enabled=false`로 두면 WebSocket 수신을 끄고 기존 REST 폴링만으로 동작합니다.

## 프로젝트 구조

```text
src/main/java/com/papertrade/paper_trading/
├── Controller/   인증, 시세, 주문 REST API + 공통 예외 처리
├── Service/      매칭 엔진, 시세 폴링·캐시, 활성 종목 레지스트리, 인증, 수수료 계산
├── Client/       토스증권 API 클라이언트, OAuth2 토큰 발급, 호출량 제한
├── WebSocket/    STOMP 설정, 구독 레지스트리, Redis 구독자, 토스 WebSocket 수신
├── Repository/   Spring Data JPA 리포지토리
├── Entity/       JPA 엔티티
├── Dto/          요청·응답, Pub/Sub 메시지 (record)
├── Enum/         도메인 열거형
├── Config/       Redis, Security, WebSocket, 설정 프로퍼티
└── Security/     JWT 필터, 토큰 발급·검증
```

## 구현 범위

현재 동작하는 기능입니다.

- 회원가입, 로그인, 토큰 재발급·폐기
- 토스 OAuth2 토큰 발급·캐싱·갱신
- 호가·현재가·일일 고저가 REST 조회와 WebSocket 실시간 푸시
- 토스 WebSocket 호가 수신 (연결 슬롯 분산 점유, 구독 디바운스, 재연결 백오프)
- 미국 장 운영정보 조회
- 토스 API 호출량 제한(3개 그룹)과 예산 소진 대응
- 주문 접수·취소, 종목 단위 비동기 매칭, 체결과 현금 원장 기록

아직 구현되지 않은 기능입니다.

- 회원가입 시 계좌 자동 생성과 초기 모의 투자금 입금
- 투자금 추가 신청, 계좌 초기화 API
- 잔고·보유 자산·주문 내역 조회 API
- 일별 계좌 스냅샷, 리더보드
- 종목 마스터 적재, 환율 연동
- DLQ 관리 API, DB 마이그레이션 도구

> 회원가입이 계좌를 만들지 않기 때문에, 계좌를 별도로 생성하지 않은 신규 사용자는 주문 API에서 오류가 납니다.

## 문서

- [`docs/current-implementation-overview.md`](docs/current-implementation-overview.md) — 코드 기준 구현 현황, 데이터 흐름, 설정, 구현 경계와 주의점
- [`docs/plans/`](docs/plans) — 기능별 설계 계획. 각 문서에 결정의 배경과 대안 검토가 함께 기록되어 있습니다.
