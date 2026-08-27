# 토스 OAuth2 인증 전환 + 호출량 그룹 정정 + 호가 WebSocket 수신 전환

## Context

토스증권 Open API 공식 문서(`https://openapi.tossinvest.com/openapi-docs/latest/openapi.json`, `.../overview.md`, `.../asyncapi.json`)를 받아 현재 구현과 대조한 결과, 세 가지가 확인됐다. 앞의 두 개는 **지금 코드가 틀린 것**이고, 세 번째는 새로 얻을 수 있는 것이다.

### 1. 인증 방식이 현행 스펙과 다르다 (지금 API 호출이 전부 실패한다)

`TossOrderBookClient`/`TossPriceClient`/`TossCandleClient`/`TossMarketCalendarClient` 4종은 `TossInvestProperties.secretToken()`을 그대로 `Authorization: Bearer`에 넣는다. 실제로 찔러본 결과 이 방식은 **401 `invalid-token`** 을 받는다.

```
A) Bearer {TOSS_INVEST_SECRET_TOKEN}   ← 현재 코드 방식
   → 401 {"code":"invalid-token","message":"유효하지 않은 토큰입니다."}

B) Bearer {OAuth2 access_token}
   → 200 {"result":{"timestamp":"2026-08-25T04:25:47.000+09:00","currency":"USD",...}}
```

현행 스펙은 OAuth2 client credentials다 — `POST /oauth2/token` (`grant_type=client_credentials`, `client_id`, `client_secret`) → `access_token` (`expires_in` 86399초). `.env`에 `TOSS_INVEST_CLIENT_ID`가 이미 있는데 코드가 이 값을 전혀 쓰지 않고 있었고, 그게 여기에 필요한 값이었다.

또한 토스는 **허용 IP 목록**을 REST와 WebSocket handshake 양쪽에 적용한다. 미등록 IP는 `/oauth2/token`에서 403 `access_denied` "IP address not allowed"를 받는다.

### 2. 호출량 그룹이 토스의 실제 그룹과 어긋나 있다

| | 현재 구현 | 토스 실제 |
|---|---|---|
| 그룹 A | orderbook + prices + **candles** = 5 × 0.8 → **실효 4 TPS** | `MARKET_DATA` (orderbook, prices, trades, price-limits) **15** |
| | | `MARKET_DATA_CHART` (candles) **20** — 별도 그룹 |
| 그룹 B | market-calendar + exchange-rate = 5 × 0.8 → 4 TPS | `MARKET_INFO` (market-calendar, exchange-rate) **3** |

20 TPS짜리 캔들을 15 TPS 그룹에 합쳐놓고 기본값마저 5로 잡아, 셋이 실효 4 TPS를 나눠 쓰고 있다. 반대로 그룹 B는 실제 한도(3)보다 높게 잡아 429를 자초한다. 그룹만 스펙대로 쪼개도 호가 가용량이 4 → 12 TPS가 된다.

### 3. 호가를 WebSocket으로 받으면 폴링 자체가 사라진다

토스가 실시간 호가 WebSocket(`wss://openapi-ws.tossinvest.com/ws/v1`, `orderbook:us`)을 제공한다. 계정당 연결 2개 × 연결당 구독 100건이라, 호가 전용으로 쓰면 **200종목**을 REST 예산 소모 없이 실시간으로 받는다.

우선순위를 **현재가 < 캔들 < 호가**로 두고 배분한다. 호가는 체결 정확도에 직결되므로 WS 200건을 전부 몰아주고, 현재가는 화면용이라 REST 배치(최대 200종목/1회)로 충분하며, 캔들은 전용 20 TPS를 독점한다. `trade:us`(체결 틱)를 구독하면 종목당 2건이 되어 100종목으로 줄어들기 때문에 구독하지 않는다.

**실측으로 확인한 WebSocket 동작** (2026-08-25 미국 정규장):

- **full-replace 재선언은 기존 구독을 끊지 않는다.** AAPL을 고정한 채 2.5초 간격으로 12회 재선언한 결과, 각 선언 시점을 걸친 수신 공백은 173\~2257ms로 흩어졌고 선언이 없던 구간의 자연 공백(p99 1606ms, max 2580ms)과 구분되지 않았다. 선언마다 재구독이 일어난다면 공백에 하한선이 생겨야 하는데 173ms·222ms 같은 값이 나왔다. 서버가 내부적으로 diff를 계산한다.
- 구독 제거는 즉시 반영된다 (제거 선언 직후부터 해당 topic 0건).
- ack의 `subscribed[]`는 매번 **현재 구독 전체**를 돌려준다.
- **구독 직후 초기 스냅샷은 오지 않는다** — 다음 호가 갱신부터 푸시된다.
- 수신 `data`는 REST `GET /api/v1/orderbook` 응답과 **동일한 모양**이다 (`timestamp`, `currency`, `asks`, `bids`). 기존 `OrderBookResult` DTO를 그대로 재사용할 수 있고 호가 신선도 검증도 그대로 작동한다.

재선언이 안전하다는 게 확인됐으므로 디바운스는 오직 **선언 빈도 5회/초** 제한 대응용이며, 크게 잡을 필요가 없다.

검증 스크립트는 별도 보관하지 않았다. 재현이 필요하면 `orderbook:us`로 한 종목을 구독한 뒤 2.5초 간격으로 종목을 토글하며 재선언하고, 고정 종목의 프레임 도착 간격 분포를 선언 시점과 대조하면 된다.

---

## 0. 토스 OAuth2 인증 전환 (선행 — 이게 없으면 나머지가 전부 동작하지 않는다)

WebSocket handshake도 같은 access token을 `Authorization: Bearer` 헤더로 쓴다. 토큰 발급이 없으면 WS 연결 자체가 성립하지 않으므로 반드시 먼저 한다.

### 0.1 `Config/TossInvestProperties.java`에 `clientId` 추가

```java
@Value("${toss-invest.openapi.client-id:}")
private String clientId;

public String clientId() {
    return clientId;
}
```

`secretToken()`은 이름을 유지한다 — 값의 의미가 "client secret"으로 바뀌지만, 4개 클라이언트가 더 이상 이 값을 직접 쓰지 않고 아래 0.2만 쓰게 되므로 이름 변경의 실익이 없다. 대신 필드에 주석으로 OAuth2 `client_secret`임을 명시한다.

### 0.2 `Client/TossAccessTokenProvider.java` 신설

토큰은 24시간짜리라 매 호출마다 발급하면 안 되고, 여러 인스턴스가 각자 발급하는 것도 낭비다. Redis에 캐싱한다.

```java
@Component
@RequiredArgsConstructor
public class TossAccessTokenProvider {

    private static final String ACCESS_TOKEN_KEY = "toss-api:access-token";
    // 만료 직전 갱신 실패로 401이 나지 않도록 실제 만료보다 앞당겨 버린다
    private static final Duration EXPIRY_MARGIN = Duration.ofMinutes(10);

    private final StringRedisTemplate stringRedisTemplate;
    private final TossInvestProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    public String accessToken() {
        String cached = stringRedisTemplate.opsForValue().get(ACCESS_TOKEN_KEY);
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        return issueAndCache();
    }

    /** 401을 받은 호출자가 캐시를 버리고 재발급받을 때 사용한다. */
    public String reissue() {
        stringRedisTemplate.delete(ACCESS_TOKEN_KEY);
        return issueAndCache();
    }

    private synchronized String issueAndCache() { /* POST /oauth2/token, expires_in - EXPIRY_MARGIN 으로 SET */ }
}
```

- `client_id`/`client_secret`이 비어 있으면 `IllegalStateException` — 기존 `validateSecretToken()`과 같은 방식.
- 발급 실패(4xx/5xx)는 `TossOpenApiException`으로 던져 기존 `GlobalExceptionHandler` 경로를 탄다.
- `issueAndCache()`를 `synchronized`로 두면 한 인스턴스 안의 동시 발급은 막힌다. 인스턴스 간 중복 발급은 24시간에 몇 번 수준이라 분산 락까지 두지 않는다.

### 0.3 4개 클라이언트가 `TossAccessTokenProvider`를 쓰도록 변경

`TossOrderBookClient`/`TossPriceClient`/`TossCandleClient`/`TossMarketCalendarClient`에서 공통으로:

```java
- .header("Authorization", "Bearer " + properties.secretToken())
+ .header("Authorization", "Bearer " + tossAccessTokenProvider.accessToken())
```

그리고 각 클라이언트의 `validateSecretToken()` 호출은 제거한다 — 검증 책임이 `TossAccessTokenProvider`로 옮겨간다.

응답이 **401**이면 `tossAccessTokenProvider.reissue()` 후 **1회만** 재시도한다. 토큰이 서버 측에서 조기 폐기된 경우를 위한 것이고, 무한 재시도를 막기 위해 재시도는 1회로 제한한다. 4개 클라이언트에 같은 코드를 복붙하지 말고, 이미 4곳에 중복돼 있는 `handleResponse`/`recordResponseHeaders` 처리와 함께 공통 부모나 헬퍼로 뽑는 것을 권한다 (이 중복 정리 자체는 선택 사항이며, 최소 변경으로 가려면 각 클라이언트에 그대로 넣어도 된다).

### 0.4 설정

```yaml
toss-invest:
  openapi:
    base-url: https://openapi.tossinvest.com
    client-id: ${TOSS_INVEST_CLIENT_ID:}
    secret-token: ${TOSS_INVEST_SECRET_TOKEN:}
```

`.env`에는 이미 두 값이 다 있다. **운영/개발 환경의 공인 IP를 WTS 설정 > Open API > 허용 IP 관리에 등록**해야 한다 — 이건 코드가 아니라 운영 작업이다.

---

## 1. 호출량 제한 그룹을 토스 스펙대로 3분할

### 1.1 `Client/TossApiRateLimiter.java` 상수 교체

```java
- public static final String ORDERBOOK_PRICE_CANDLE_GROUP = "orderbook-price-candle";
- public static final String MARKET_CALENDAR_EXCHANGE_RATE_GROUP = "market-calendar-exchange-rate";
+ public static final String MARKET_DATA_GROUP = "market-data";              // 호가, 현재가, 체결, 상하한가
+ public static final String MARKET_DATA_CHART_GROUP = "market-data-chart";  // 캔들
+ public static final String MARKET_INFO_GROUP = "market-info";              // 장 운영정보, 환율
```

획득·학습·백오프 로직은 **전혀 건드리지 않는다.** 이미 그룹 문자열을 키로 받아 동작하도록 짜여 있어서 그룹이 2개에서 3개로 늘어나도 그대로 동작한다.

### 1.2 `Config/TossApiRateLimitProperties.java`

`switch` 분기를 3개로 늘리고 기본값을 토스 실제 한도에 맞춘다.

| 그룹 | default-limit | safety-margin | 실효 |
|---|---|---|---|
| `market-data` | 15 | 0.8 | 12 TPS |
| `market-data-chart` | 20 | 0.8 | 16 TPS |
| `market-info` | 3 | 0.8 | 2 TPS |

`market-info`는 실효 2 TPS로 낮아지지만, 장 운영정보는 캐시 TTL이 12시간이라 문제되지 않는다. 지금 5로 잡혀 있는 게 오히려 429를 부르는 설정이다.

### 1.3 호출 지점의 그룹 지정 변경

| 파일 | 변경 |
|---|---|
| `Service/OrderBookService.java`, `Client/TossOrderBookClient.java` | → `MARKET_DATA_GROUP` |
| `Service/PriceService.java`, `Client/TossPriceClient.java` | → `MARKET_DATA_GROUP` |
| `Service/DailyPriceRangeService.java`, `Client/TossCandleClient.java` | → `MARKET_DATA_CHART_GROUP` |
| `Service/MarketCalendarService.java`, `Client/TossMarketCalendarClient.java` | → `MARKET_INFO_GROUP` |

### 1.4 `Controller/GlobalExceptionHandler.java`의 202/503 판정 변경

지금은 `ORDERBOOK_PRICE_CANDLE_GROUP`인지로 갈랐다. 판정 기준은 그룹 이름이 아니라 **"WebSocket 푸시 채널이 있는가"** 이므로, 그룹이 3개로 늘어난 지금은 두 그룹을 묶어야 한다.

```java
private static final Set<String> PUSH_CHANNEL_GROUPS = Set.of(
    TossApiRateLimiter.MARKET_DATA_GROUP,
    TossApiRateLimiter.MARKET_DATA_CHART_GROUP
);

boolean pendingResponse = PUSH_CHANNEL_GROUPS.contains(exception.group());
```

`market-info`만 503 + `Retry-After`를 유지한다. 05번 계획에서 정한 의미(푸시 채널 없는 그룹만 클라이언트가 직접 재시도)가 그대로 보존된다.

---

## 2. `OrderBookService`에 수신 경로 합류 지점 만들기

WS 수신을 붙이기 전에, REST와 WS가 **같은 후처리**를 타도록 지점을 먼저 뽑는다. 지금 `fetchCacheAndPublish`의 꼬리 부분이 그 후처리다 — 캐시 저장, 변경 감지, version 증가, Pub/Sub 발행, 매칭 스트림 발행.

```java
// OrderBookService
/** REST 응답과 WebSocket 푸시가 공통으로 합류하는 지점. */
public OrderBookResponse applyOrderBook(String symbol, OrderBookResult result) {
    OrderBookResponse previousResponse = getCachedOrderBook(symbol);
    OrderBookResponse response = new OrderBookResponse(result, LocalDateTime.now());
    cacheOrderBook(symbol, response);
    if (orderBookChanged(previousResponse, response)) {
        incrementOrderBookVersion(symbol);
        publishOrderBook(symbol, response);
        symbolMatchRequestedStreamPublisher.publish(
            new SymbolMatchRequestedEvent(symbol, "ORDER_BOOK_UPDATED")
        );
    }
    return response;
}

private OrderBookResponse fetchCacheAndPublish(String symbol) {
    if (!tossApiRateLimiter.tryAcquire(TossApiRateLimiter.MARKET_DATA_GROUP)) {
        throw new TossApiQuotaUnavailableException(...);
    }
    return applyOrderBook(symbol, tossOrderBookClient.getOrderBook(symbol).result());
}
```

기존 동작은 그대로고, WS 수신자는 `applyOrderBook(symbol, result)`만 호출하면 캐시·Pub/Sub·매칭 트리거가 전부 따라온다. 매칭 엔진, `OrderBookRedisSubscriber`, STOMP 푸시 경로는 **한 줄도 바꾸지 않는다.**

이 설계 덕분에 WS는 "새로운 데이터 공급원"으로만 추가되고, 소비 측은 공급원이 REST인지 WS인지 알 필요가 없다.

---

## 3. 전역 활성 종목 레지스트리

지금 `OrderBookSubscriptionRegistry`는 **인스턴스 로컬 `HashMap`** 이다. 폴링 방식에서는 각 인스턴스가 자기 구독자의 종목만 폴링하고 결과를 Redis Pub/Sub로 전체에 뿌리므로 이 구조로 충분했다.

WS는 다르다. 연결을 잡은 인스턴스가 **전체 인스턴스의 구독 종목**을 알아야 한다. 그래서 전역 레지스트리가 필요하다.

`Service/ActiveOrderBookSymbolRegistry.java` 신설:

- 각 인스턴스가 주기적으로(5초) 자기 `OrderBookSubscriptionRegistry.activeSymbols()`를 Redis sorted set `orderbook:active-symbols`에 `ZADD score=now(epochMillis)`로 갱신한다.
- 읽을 때는 `ZRANGEBYSCORE now-30000 +inf` — 인스턴스가 죽어도 30초 뒤 자연 소멸한다. 별도 정리 로직이 필요 없다.
- `ZREMRANGEBYSCORE -inf now-60000`로 주기적 정리.

전역 활성 종목은 다음 둘의 합집합이다.

| 출처 | 획득 방법 | 슬롯 배정 순위 |
|---|---|---|
| 미체결 주문 종목 | DB (`OrderRepository.findDistinctStockIdsByStatusIn`) — 이미 전역 | **1순위** |
| 구독 종목 (화면용) | 위 Redis ZSET | 2순위 |

### 이 순위는 "종류 구분"이 아니라 "200을 넘을 때의 밀어내기 순서"다

**활성 종목이 200개 이하면 미체결이든 화면용이든 전부 WebSocket으로 들어가고, 호가 REST 폴링은 0회다.** 순위는 201번째부터 누구를 폴백으로 밀어낼지 정하는 규칙일 뿐이다.

"미체결 종목만 WS로 받고 화면용은 무조건 폴링으로 고정"하는 방식도 생각할 수 있지만 채택하지 않는다. 미체결 종목이 10개뿐이어도 190슬롯을 놀리면서 화면용 종목 50개가 REST를 두들기게 되는데, 호가 REST는 종목당 1회라 `market-data` 예산의 주 소비자가 된다. 고정 분리는 자원을 낭비하면서 확장 상한만 낮춘다. 반대로 우선순위 방식은 미체결 종목이 200을 넘어가는 순간 자동으로 "미체결만 WS" 상태가 되므로, 고정 분리가 하려던 것을 특수 케이스로 이미 포함한다.

우선순위 방식의 유일한 비용은 구독 집합이 자주 바뀌어 재선언이 잦아지는 것인데, **재선언이 기존 구독을 끊지 않는다는 게 실측으로 확인**됐고 250ms 디바운스로 선언 빈도 제한도 안전하므로 실질 비용이 없다.

미체결 주문 종목을 위에 두는 건 체결 지연이 사용자 자산에 직접 영향을 주기 때문이다. 화면용 종목이 폴백으로 밀리면 갱신이 느려질 뿐이고, 이미 "현재가는 수 초 지연될 수 있다"고 공시하는 방향이라 정책과도 일관된다. 이 순위는 `OrderBookPollingService`가 이미 미체결 종목(1초)과 구독 종목(20초)을 나눠 도는 것과 같은 기준이라, 새 개념을 들이는 게 아니라 기존 구분을 재사용하는 것이다.

미체결 종목 수가 200 경계에서 출렁이면 화면용 종목이 WS와 폴링을 오가며 갱신 주기가 튈 수 있다. 다만 이건 동시 미체결 종목이 200개에 달하는 상황이라 초기 서비스에서는 발생하지 않는다. 실제로 관측되면 그때 밀려난 종목을 최소 N초간 폴백에 묶는 히스테리시스를 넣는다 — **지금은 넣지 않는다.**

---

## 4. 호가 WebSocket 수신 도입

### 4.1 슬롯 점유 (계정당 연결 2개 제약)

연결 한도는 **인스턴스당이 아니라 계정당 2개**다. 각 인스턴스가 순진하게 연결하면 3번째 인스턴스가 뜨는 순간 가장 오래된 연결이 서버에 의해 종료되고, 그게 재연결하면 또 다른 걸 밀어내는 flapping이 된다. 에러도 없이 조용히 서로 죽인다.

Redis 락으로 슬롯 0/1을 점유한다.

- 키 `toss-ws:slot:0`, `toss-ws:slot:1` — `SET NX PX 15000`, 5초마다 TTL 갱신(하트비트).
- 인스턴스는 startup 후 주기적으로 비어 있는 슬롯을 잡으려 시도한다. **한 인스턴스가 두 슬롯을 다 잡아도 된다** — 인스턴스가 하나뿐일 때 200종목을 전부 커버하려면 그래야 한다.
- 슬롯을 잃으면(TTL 갱신 실패) 해당 WS 연결을 즉시 닫는다. 스펙 경고대로 **재연결 전에 쓰던 연결을 먼저 닫아야** 밀어내기가 반복되지 않는다.
- 인스턴스가 죽으면 15초 안에 TTL이 만료되고 다른 인스턴스가 슬롯을 인수한다. 그 구간 동안 해당 샤드 종목은 폴백(6절)으로 처리된다.

### 4.2 종목 → 슬롯 배정

전역 활성 종목을 (우선순위, symbol) 순으로 정렬해 상위 200개를 취하고, 인덱스 홀짝으로 슬롯을 나눈다.

```java
List<String> top = globalActiveSymbols(); // 우선순위 정렬 후 상위 200
// i % 2 == slotIndex 인 것만 이 슬롯이 담당 → 각 슬롯 최대 100건 보장
```

해시 샤딩이 아니라 인덱스 홀짝을 쓰는 이유는 **각 슬롯이 100건을 넘지 않음을 보장**하기 위해서다. 해시로 나누면 한쪽에 몰려 `too-many-topics`가 날 수 있다. 종목이 드나들 때 배정이 섞이는 단점이 있지만, 재선언이 기존 구독을 끊지 않는다는 게 실측으로 확인됐으므로 실질 비용이 없다.

### 4.3 `WebSocket/TossOrderBookWebSocketClient.java` 신설

슬롯 하나당 인스턴스 하나. Java 21 표준 `java.net.http.WebSocket`을 쓰면 의존성 추가가 필요 없다.

- **연결** — `wss://openapi-ws.tossinvest.com/ws/v1`, 헤더 `Authorization: Bearer {accessToken}`. 인증은 handshake 1회뿐이라 연결 유지 중 토큰이 만료돼도 끊기지 않는다. 토큰 갱신 때문에 재연결할 필요가 없다.
- **선언** — 250ms 주기로 담당 종목 집합을 다시 계산하고, 직전 선언과 다를 때만 `[{"id":"...","type":"orderbook:us","codes":[...]}]`를 보낸다. 이 주기가 곧 디바운스이며 최대 4회/초로 **선언 빈도 5회/초** 아래에 머문다.
- **수신** — `type`으로 분기한다.
  - `message` → `data`를 `OrderBookResult`로 역직렬화해 `orderBookService.applyOrderBook(topic의 symbol, result)` 호출. 이 한 줄로 캐시·Pub/Sub·매칭 트리거가 전부 이어진다.
  - `subscriptions` → `subscribed[]`를 의도한 집합과 대조해 어긋나면 `log.warn`. **full-replace 구조에서 상태 divergence를 잡아낼 수 있는 유일한 지점이다.** `rejected[]` 항목은 원인을 고치기 전엔 재선언해도 계속 거부되므로 담당 집합에서 제외한다 (`stock-not-found`인 종목을 계속 넣으면 매 선언마다 거부가 반복된다).
  - `error` → `rate-limit-exceeded`면 1초 대기 후 재선언, `server-shutdown`이면 연결을 닫고 재연결 절차로.
  - `pong` → 무시.
- **PING** — 서버는 **클라이언트로부터의 수신이 180초 없으면** 끊는다. 서버가 보내주는 데이터는 이 타이머를 리셋하지 않으므로, 데이터를 받고 있어도 60초마다 순수 텍스트 `PING`(대문자 4글자)을 보낸다.
- **재연결** — 끊김을 감지하면 지수 백오프(1s → 2s → 4s … 최대 30s, jitter 포함)로 재시도하고 구독을 다시 선언한다. `TossApiRateLimiter`에 이미 같은 형태의 백오프 계산이 있으니 그 방식을 따른다.

### 4.4 REST 예산은 쓰지 않는다

WS 수신은 `TossApiRateLimiter.tryAcquire()`를 호출하지 않는다. 토스의 REST rate limit과 WebSocket 한도는 별개이며, WS 프레임 수신은 REST 호출이 아니다.

---

## 5. 폴링 축소와 200종목 초과분 처리

`Service/OrderBookPollingService.java`의 폴링 제외 기준을 **"WS 담당 종목인가"가 아니라 "호가 캐시가 신선한가"** 로 둔다.

```java
// 담당 목록을 참조하지 않고 캐시 신선도만 본다
if (orderBookService.isFresherThan(symbol, stalenessThreshold)) {
    continue; // 누군가(WS든 REST든) 이미 채워두고 있으니 폴링 불필요
}
```

`applyOrderBook()`이 이미 `OrderBookResponse.receivedAt`을 캐시에 넣으므로 별도 상태가 필요 없다. 이 방식의 이점은 세 가지다.

- **자기조정** — 정상 운영 중에는 WS 푸시로 캐시가 항상 신선해 폴링이 0회다. WS가 멎으면 캐시가 낡으면서 자동으로 폴링 대상이 되고, 푸시가 재개되면 자동으로 빠진다. "재연결됐으니 무엇을 정리하자" 같은 이벤트 핸들링이 필요 없다.
- **결합도 감소** — `OrderBookPollingService`가 WS 담당 종목 목록을 알 필요가 없다.
- **공급원 무관** — WS든 REST든 "호가가 신선한가"만 본다.

### 임계값은 재연결 시간보다 넉넉하게 잡는다 (기본 30초)

여기서 임계값을 짧게(예: 5초) 잡으면 **짧은 재연결마다 폴백이 발동해 오히려 손해**다. 계산해보면 명확하다.

| | 소요 시간 |
|---|---|
| WS 재연결 (백오프 + 연결 + 선언 + 첫 푸시) | **2~3초** |
| REST 폴링으로 200종목 1회전 (실효 12 TPS) | **약 17초** |

폴백으로 전환해도 WS가 돌아올 때까지 200종목 중 30\~40개밖에 못 훑는다. **끝내지도 못할 작업에 예산을 태우고 정작 급한 종목은 순서가 안 와서 굶는다.** 그래서 기본값을 30초로 두어 **짧은 단절에는 폴백이 아예 발동하지 않게** 한다. 폴백은 토스 쪽 장기 장애처럼 재연결이 반복 실패하는 상황에만 의미가 있다.

이렇게 해도 미체결 주문의 체결이 30초씩 지연되지는 않는다 — 6절에서 설명하듯 매칭 경로가 수요 기반으로 필요한 종목만 따로 REST를 호출하기 때문이다.

### 200종목 초과분

| 처리 경로 | 대상 | 주기 |
|---|---|---|
| WebSocket | 우선순위 상위 200종목 | 실시간 |
| REST 폴링 (`fixed-delay-ms`, 1초) | 초과분 중 미체결 주문 종목 | 1초 |
| REST 폴링 (`idle-fixed-delay-ms`, 20초) | 초과분 중 단순 구독 종목 | 20초 |

`market-data` 실효 12 TPS 중 현재가 배치가 1 TPS를 쓰므로 약 11 TPS가 남는다. 1초 주기로 11종목, 20초 주기로는 220종목을 추가로 감당한다. 실질 상한이 200 → 400종목대가 된다.

**초과분이 상한마저 넘으면** `TossApiQuotaUnavailableException`이 나고 폴링은 이미 그걸 조용히 삼킨다(`refreshAndPublish`의 `catch ... ignored`). 별도 처리를 추가하지 않는다.

다만 예산이 모자랄 때 미체결 종목이 먼저 소진하도록 순서를 보장해야 한다. 지금 `rotate()`는 공평하게 돌리기만 하므로, 미체결 종목 루프를 먼저 완주시킨 뒤 구독 종목 루프를 도는 순서를 명시한다.

---

## 6. WS 단절 구간 처리 — 대기가 기본이고, 새 로직은 거의 필요 없다

WS 연결이 끊기는 구간은 실재한다. 특히 토스 배포 시 `server-shutdown` 직후 종료되며, 이때 **담당 200종목의 호가가 동시에** 사라진다. 게다가 구독 직후 초기 스냅샷이 없으므로 재연결·재선언 후에도 각 종목의 첫 갱신이 올 때까지 공백이 이어진다.

### 미체결 주문의 REST 수요는 생각보다 작다

단절 구간에 "미체결 종목 전부가 REST로 몰린다"고 보기 쉬운데, 실제로는 그렇지 않다. 매칭 경로의 신선도 기준선이 **그 주문이 접수된 시각**이기 때문이다.

```java
// MatchingEngineTransactionService:59
OrderBookResponse orderBook = orderBookService.getOrderBookNoOlderThan(symbol, matchableOrder.submittedAt());
```

`notBefore`가 "지금부터 N초 이내"가 아니라 `submittedAt`이므로, **5분 전에 접수된 주문은 1분 전 캐시도 그대로 통과한다.** REST가 실제로 나가는 건 "마지막 호가 갱신 이후에 새로 접수된 주문"이 있는 종목뿐이고, 그건 활성 종목 수가 아니라 신규 주문 수에 비례한다. 수요 기반이라 폭을 스스로 좁힌다.

그래서 짧은 단절 구간의 올바른 동작은 **폴백 전환이 아니라 대기**다.

| 대상 | 단절 구간 동작 |
|---|---|
| 미체결 종목 | 매칭 경로가 필요한 종목만 REST 호출 — 별도 조치 불필요 |
| 구독 종목(화면) | 몇 초간 갱신 안 됨 — 지연 공시 정책 범위 안이라 그대로 둔다 |

5절의 임계값 30초가 이 방침을 구현한다. 짧은 단절에는 폴백이 발동하지 않고 WS가 스스로 복구한다.

### 예산이 소진되면 체결을 미루는 동작은 이미 있다

장기 장애로 예산까지 소진되는 경우에도 **필요한 동작이 이미 구현돼 있다.**

`MatchingEngineStreamConsumer.processRecord()`는 `TossApiQuotaUnavailableException`을 잡아 **DLQ로 보내지 않고 ack 후 스킵**한다.

```java
} catch (TossApiQuotaUnavailableException e) {
    log.debug("Skip matching because Toss API quota is unavailable. symbol={}", symbol);
    acknowledge(record);
    clearRetryCount(record);
}
```

그리고 `PendingOrderRematchScheduler`가 30초마다 미체결 주문 종목을 다시 발행한다. 즉 **예산이 없으면 체결을 미루고, 예산이 풀리면 안전망이 다시 태우는** 구조가 이미 완성돼 있다. 단절 구간에 부정확한 데이터로 체결되는 일도, DLQ가 오염되는 일도 없다.

`TossApiQuotaUnavailableException`이 `handleFailure()`를 타지 않는다는 점이 중요하다. 재시도 카운트가 올라가지 않으므로 **예산 소진으로는 DLQ에 도달할 수 없다.** DLQ에 쌓이는 건 5회 재시도까지 실패한 실제 오류(NPE, DB 장애 등)뿐이고, 그건 WS 재연결로 해소되는 종류가 아니다. 따라서 "WS가 복구됐으니 DLQ를 정리한다"는 처리는 넣지 않는다 — 진짜 버그로 죽은 건까지 지워 원인 추적 수단만 잃는다.

이번 계획에서 추가로 할 일은 하나뿐이다 — **단절 구간에 REST 폴백이 예산을 태우는 순서를 우선순위대로 만드는 것**이고, 그건 5절의 "미체결 주문 종목 먼저"가 이미 처리한다.

`matchUntilOrderBookVersionIsStable`의 호가 version 안정화 루프도 그대로 둔다. WS 푸시가 `applyOrderBook`을 통해 version을 올리므로 REST 폴링일 때와 동일하게 동작한다.

---

## 7. 현재가와 캔들은 구조 변경 없음

- **현재가** — REST 배치 조회를 유지한다. `/api/v1/prices`는 `symbols`로 **최대 200종목을 1회 호출**로 받고, `PriceService`가 이미 `MAX_SYMBOL_COUNT=200` 청크로 자르고 있다. 종목이 늘어도 1\~2 TPS다. 폴링 1초 + 캐시 TTL 2초라 실제 지연은 최대 3초 수준이며, 다른 증권사와 같이 **"현재가는 실시간 대비 수 초 지연될 수 있다"고 공시**한다. `trade:us` WS를 쓰면 실시간이 되지만 종목당 구독이 1건 더 들어 호가 상한이 200 → 100종목으로 반토막 나므로 채택하지 않는다.
- **캔들** — 시장가 체결용 고가/저가 산출에 계속 쓴다. `MARKET_DATA_CHART` 20 TPS를 독점하고, `DailyPriceRangeService.updateWithExecutionPrice()`가 자체 체결가로 캐시를 갱신하므로 API 호출은 캐시 미스 때만 발생한다.

---

## 설정 추가

```yaml
toss-invest:
  openapi:
    client-id: ${TOSS_INVEST_CLIENT_ID:}
  rate-limit:
    market-data:
      default-limit: ${TOSS_RATE_LIMIT_MARKET_DATA:15}
      safety-margin: ${TOSS_RATE_LIMIT_MARKET_DATA_MARGIN:0.8}
    market-data-chart:
      default-limit: ${TOSS_RATE_LIMIT_MARKET_DATA_CHART:20}
      safety-margin: ${TOSS_RATE_LIMIT_MARKET_DATA_CHART_MARGIN:0.8}
    market-info:
      default-limit: ${TOSS_RATE_LIMIT_MARKET_INFO:3}
      safety-margin: ${TOSS_RATE_LIMIT_MARKET_INFO_MARGIN:0.8}
  websocket:
    url: ${TOSS_WS_URL:wss://openapi-ws.tossinvest.com/ws/v1}
    enabled: ${TOSS_WS_ENABLED:true}
    market: ${TOSS_WS_MARKET:us}
    max-symbols-per-connection: ${TOSS_WS_MAX_SYMBOLS_PER_CONNECTION:100}
    connection-slots: ${TOSS_WS_CONNECTION_SLOTS:2}
    declare-debounce-ms: ${TOSS_WS_DECLARE_DEBOUNCE_MS:250}
    ping-interval-seconds: ${TOSS_WS_PING_INTERVAL_SECONDS:60}
    slot-lock-ttl-ms: ${TOSS_WS_SLOT_LOCK_TTL_MS:15000}
    slot-heartbeat-ms: ${TOSS_WS_SLOT_HEARTBEAT_MS:5000}

orderbook:
  active-symbols:
    refresh-ms: ${ORDERBOOK_ACTIVE_SYMBOLS_REFRESH_MS:5000}
    ttl-ms: ${ORDERBOOK_ACTIVE_SYMBOLS_TTL_MS:30000}
  polling:
    # 이 시간보다 캐시가 낡았을 때만 REST 폴링 대상이 된다.
    # WS 재연결(2~3초)보다 넉넉해야 짧은 단절에 폴백이 헛돌지 않는다. 5절 참고.
    staleness-threshold-ms: ${ORDERBOOK_STALENESS_THRESHOLD_MS:30000}
```

`enabled: false`로 두면 WS를 끄고 기존 폴링만으로 동작한다. 전환 초기에 문제가 생기면 이 스위치 하나로 되돌릴 수 있게 하는 게 목적이다.

## 구현 순서

0절(인증)은 나머지 전부의 전제다. 1절(그룹 분리)은 0절만 끝나면 독립적으로 배포 가능하고, **그것만으로 호가 가용량이 4 → 12 TPS가 되므로 먼저 배포해 효과를 확인하는 것을 권한다.** 2\~6절(WS)은 그 뒤에 별도로 진행한다.

## 검증 방법

- **0절** — `TOSS_WS_ENABLED=false` 상태로 기존 REST 조회 4종(호가/현재가/캔들/장운영정보)이 200을 받는지 확인. 배포 환경 IP가 허용 목록에 있어야 한다.
- **1절** — 활성 종목을 20개 이상으로 늘렸을 때 429가 나지 않고 폴링이 도는지 확인.
- **4절** — `subscriptions` ack의 `subscribed[]`가 의도한 집합과 일치하는지 로그로 확인. 인스턴스를 3개로 늘려도 슬롯이 2개만 점유되고 연결 flapping이 없는지 확인.
- **5절** — WS 담당 종목이 REST 폴링에서 제외되어 `market-data` 사용량이 실제로 줄어드는지 확인.
- **6절** — WS를 강제로 끊었을 때 미체결 주문이 DLQ로 가지 않고, 재연결 후 30초 안에 재매칭되는지 확인.

## 이번 범위에서 제외

- `trade:us`(실시간 체결) 구독 — 호가 상한이 반토막 나므로 채택하지 않는다.
- `personal:order` 구독 — 자체 매칭 엔진을 쓰므로 토스 계좌에 실제 주문이 들어가지 않는다.
- `/api/v1/trades`(최근 체결 내역, `MARKET_DATA` 그룹) 기반 시장가 체결 — 캔들의 고가/저가는 "오늘 장중 최고/최저"이지 "지금 체결 가능한 가격"이 아니라서 장 후반으로 갈수록 현재가와 벌어지고, 시장가 매수를 오늘 고가로 체결시키면 사용자에게 불리한 쪽으로 왜곡된다. `trades`가 이 목적에 더 정확하고 예산도 남지만, 기존 캔들 기반 구현을 바꾸는 일이라 별도 라운드로 분리한다.
- 국내(`orderbook:kr`) 지원 — 현재 서비스는 미국주식 전용이다.

## 확인이 필요한 가정

- **허용 IP 등록은 코드가 아니라 운영 작업이다.** 배포 환경의 공인 IP가 고정되지 않는 구성(오토스케일링, 동적 IP)이라면 NAT 게이트웨이 등으로 아웃바운드 IP를 고정해야 한다. 이건 이 계획의 범위 밖이며 인프라 쪽 결정이 필요하다.
- 재선언이 기존 구독을 끊지 않는다는 것은 **실측 결과이며 문서에 명시적 보장 문구는 없다.** 토스가 동작을 바꾸면 종목이 드나들 때마다 담당 종목 전체가 잠시 공백이 된다. 4.3의 ack 대조 로그가 이 변화를 감지하는 지점이 되며, 만약 바뀌면 인덱스 홀짝 배정을 해시 기반으로 바꿔 종목 이동 자체를 줄이는 쪽으로 대응한다.
- 프론트엔드가 현재가 지연을 표시하는 부분은 백엔드 범위 밖이다.
