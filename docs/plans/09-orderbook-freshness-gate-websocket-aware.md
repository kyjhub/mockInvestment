# 매칭 호가 신선도 게이트를 WebSocket 구독 여부 기준으로 전환

## Context

`MatchingEngineTransactionService.matchSymbol()`은 주문마다 호가 스냅샷을 가져온다.

```java
OrderBookResponse orderBook = orderBookService.getOrderBookNoOlderThan(symbol, matchableOrder.submittedAt());
```

`getOrderBookNoOlderThan()`은 캐시의 `receivedAt`이 주문의 `submittedAt` 이상이면 캐시를 쓰고, 아니면 그 자리에서 Toss REST를 동기 호출한다. 이 호출이 트랜잭션 바깥에서 이뤄지는 것(계획 02의 "심각 3")은 그대로 유지한다 — 이번 계획은 **호출 위치가 아니라 호출 여부**를 바꾼다.

### 이 게이트는 WebSocket 구독 종목에서 상시로 REST를 부른다

`receivedAt >= submittedAt`은 "주문보다 **나중에** 받은 스냅샷"을 요구한다. 06번 검증 실측으로 푸시 빈도가 AAPL 8.0회/초, TSLA 6.5회/초였으니 프레임 간격이 125~150ms인데, 주문 접수와 매칭 시도 사이에 프레임이 하나도 안 끼면 무조건 REST로 떨어진다. 반대로 30초 안전망이 재발행한 오래된 주문은 `submittedAt`이 과거라 게이트를 그냥 통과한다.

즉 **이 게이트가 무는 건 신규 주문뿐이고, 신규 주문은 상당수가 문다.** 드문 폴백이 아니라 상시 경로에 가깝다.

### 그 REST 호출은 더 나은 값을 주지 않는다

토스 WebSocket은 **호가가 바뀔 때만** 프레임을 보낸다 (AsyncAPI 스펙 문구: "호가가 갱신될 때마다 프레임을 전달", 06번 검증에서 확인). 그러면 푸시 피드에서 `receivedAt`의 의미는 "마지막으로 **확인한** 시각"이 아니라 **"마지막으로 바뀐 시각"**이다.

따라서 구독 중인 종목에서 `receivedAt < submittedAt`은 데이터가 낡았다는 뜻이 아니라 **그 시점 이후로 호가가 변하지 않았다**는 뜻이다. 캐시가 곧 현재 상태다. REST를 불러도 같은 값을 받는다.

오히려 손해다. `applyOrderBook()`은 `receivedAt`을 **로컬 적용 시각**으로 찍는다.

```java
OrderBookResponse response = new OrderBookResponse(result, LocalDateTime.now());
```

왕복 200ms 걸린 REST 응답(=200ms 전 시장 상태)이 50ms 전 WS 프레임을 덮어쓰면서 "방금 받은 것"으로 기록된다. 게이트를 통과시키려는 행동이 게이트가 보려던 값을 오염시킨다.

### 그리고 REST 예산을 잠식한다

REST 실효 한도는 초당 약 12회다(계획 07). WebSocket이 이미 채우고 있는 종목이 이걸 먹으면 정원 밖 종목이 굶는다. 더 나쁜 건 `fetchCacheAndPublish()`가 예산 소진 시 `TossApiQuotaUnavailableException`을 던지고, `SymbolMatchingProcessor`가 이걸 `QUOTA_UNAVAILABLE`로 잡아 **그 종목 매칭 전체를 건너뛴다**는 점이다. 멀쩡한 캐시를 쥐고도 체결하지 않는다.

### 검토했다가 버린 대안 두 가지

**(a) 절대 신선도 임계값 (`receivedAt > now - N ms`이면 캐시 사용)**

버렸다. "N ms까지는 낡아도 봐준다"는 정합성 타협인데, 위 분석대로 애초에 타협할 필요가 없다. 구독 종목의 캐시는 낡은 게 아니라 현재 상태다. 게다가 이 규칙은 구독하지 않는 종목에까지 신선도 보장을 느슨하게 만든다.

**(b) 신선하지 않으면 이번 라운드를 건너뛰고 다음 푸시를 기다리기**

버렸다. **영구 굶음이 생긴다.** `markDirty()`는 `applyOrderBook()`에서 `orderBookChanged()`가 참일 때만 호출되므로, 호가가 조용한 종목은 푸시도 dirty 마킹도 재트리거도 없다. 30초 안전망이 재발행해도 `findMatchableOrdersBySymbol`이 돌려주는 `submittedAt`은 원래 주문 시각 그대로라 같은 조건에서 또 걸러진다. 그 주문은 영원히 미체결로 남는다. 현재 이 교착을 깨고 있는 것이 바로 REST 폴백(`receivedAt`을 now로 갱신)이다.

## 0. 캐시 TTL을 폴링 신선도 임계값보다 크게

이 절이 먼저 와야 한다. 나머지 절이 "구독 종목의 캐시는 항상 존재한다"를 전제하기 때문이다.

현재 두 값이 정확히 같다.

| 값 | 설정 | 기본값 |
| --- | --- | --- |
| 캐시 TTL | `orderbook.cache.ttl-seconds` | 30초 |
| 폴링 신선도 임계값 | `orderbook.polling.staleness-threshold-ms` | 30초 |

호가가 조용하면 프레임이 오지 않고, 프레임이 오지 않으면 Redis 키가 30초 뒤 만료된다. **이 계획의 논리가 가장 필요한 종목(조용한 구독 종목)이 바로 캐시가 증발하는 종목이다.** 만료되면 3절의 게이트가 `null`을 돌려주고 그 종목은 외부 유동성 없이 내부 체결만 하게 된다.

폴링이 되살리긴 한다 — `isFresherThan(30초)`가 false가 되면 1초 주기 대상이 되므로. 하지만 키 만료 시각과 폴링 대상 편입 시각이 같아서, 폴링 주기(1초) + REST 왕복 + 폴링 락(900ms)만큼 캐시가 null인 구간이 생긴다. 조용한 구독 종목은 30초마다 1~2초씩 외부 호가를 잃는다.

그 구간에 걸린 주문은 즉시 재시도되지도 않는다. 폴링이 되살릴 때 호가 내용이 이전과 같으면 `orderBookChanged()`가 false라 `markDirty()`가 찍히지 않기 때문이다. 굶지는 않지만 30초 안전망까지 기다린다.

**지켜야 할 불변식은 `캐시 TTL > 폴링 신선도 임계값`이다.** 폴링이 30초마다 되살리는 동안 키가 만료되면 안 된다. TTL이 임계값보다 크면 키는 폴링이 그 종목을 아예 포기했을 때만(미체결 주문도 없고 구독자도 없을 때) 만료되어, TTL 본래 목적인 가비지 컬렉션에만 쓰인다.

`Config/OrderBookCacheProperties.java`와 `application.yaml`의 기본값을 30초 → **120초**로 올린다. 폴링 주기(1초), 신선도 임계값(30초), 재연결 백오프 최댓값(30초)을 모두 덮는다.

```yaml
orderbook:
  cache:
    # polling.staleness-threshold-ms보다 반드시 커야 한다.
    # 폴링이 되살리는 동안 키가 만료되면 구독 종목의 호가가 주기적으로 사라진다.
    ttl-seconds: ${ORDERBOOK_CACHE_TTL_SECONDS:120}
```

**받아들이는 부작용**: REST 조회 엔드포인트(`getOrderBook()`)는 나이를 보지 않고 캐시를 그대로 돌려주므로, 아무도 보지 않는 종목의 오래된 호가가 최대 120초까지 응답될 수 있다. 사용자가 실제로 보고 있는 종목은 `subscribedSymbols()`에 들어가 20초 주기 idle 폴링을 받으므로 실질 위험은 낮다고 봤다.

## 1. `DeclaredWebSocketSymbolRegistry` 신설

"프레임 없음 = 변동 없음"이라는 등식은 **피드가 살아 있을 때만** 성립한다. 죽은 피드에서는 프레임의 부재가 아무것도 증명하지 않는다. 그래서 판정 신호가 이 설계의 전부다.

**`coveredSymbols()`를 쓰면 안 된다.** 그 javadoc이 직접 경고한다.

> 주의: 이건 "구독하기로 한 종목"이지 "지금 프레임을 받고 있는 종목"이 아니다. **연결이 끊긴 동안에도 covered로 남는 것은 의도한 동작**으로, 캐시가 신선도 임계값을 넘기면 폴링이 자동으로 폴백한다.

폴링에는 임계값 백스톱이 있어서 안전하지만 매칭에는 없다. covered 기준으로 가면 연결이 죽어도 낡은 호가로 계속 체결한다.

**`TossOrderBookWebSocketConnection.declaredSymbols`가 필요한 성질을 갖고 있다.** 모든 실패 경로에서 이벤트 기반으로 자동으로 비워진다 — 시간 판정이 하나도 없다.

| 경로 | 동작 |
| --- | --- |
| `declareIfChanged()` | `sendText` 성공 후에만 채워짐 |
| `scheduleReconnect()` | `List.of()`로 비움 (연결 실패, `onClose`, `onError`) |
| `handleError()` 전 코드 | `List.of()`로 비움 |
| `close()` (server-shutdown, 슬롯 상실) | `List.of()`로 비움 |
| `handleSubscriptionsAck()` 불일치 | `List.of()`로 비움 |

토스가 거절한 종목은 `declareIfChanged()`의 `target.removeAll(rejectedSymbols)`가 걸러내므로 애초에 들어오지 않는다. 자동으로 REST 경로로 간다.

### 왜 새 빈이 필요한가

`OrderBookService`가 `TossOrderBookWebSocketManager`를 직접 주입받을 수 없다. 매니저(`:40`)와 커넥션(`:38`)이 이미 `OrderBookService`를 주입받고 있어서 생성자 주입 순환이 된다.

`RejectedWebSocketSymbolRegistry`가 같은 문제를 이미 푼 관용구다 — WebSocket 쪽이 쓰고 다른 쪽이 읽는 의존성 없는 빈. 같은 패턴을 쓴다.

```java
package com.papertrade.paper_trading.WebSocket;

/**
 * WebSocket이 지금 구독을 선언해 둔 종목.
 *
 * <p>{@link TossOrderBookWebSocketManager}가 쓰고 {@code OrderBookService}가 읽는다.
 * 서비스가 매니저를 직접 참조하면 순환 참조가 되므로 상태를 이 빈으로 뒤집는다 —
 * {@link RejectedWebSocketSymbolRegistry}와 같은 구조다.
 *
 * <p>{@code coveredSymbols()}와 다르다. 저쪽은 "구독하기로 한 종목"이라 연결이 끊겨도 남지만,
 * 이쪽은 선언이 실제로 살아 있는 동안만 남는다. 매칭은 이 구분에 의존한다.
 */
@Component
public class DeclaredWebSocketSymbolRegistry {

    private volatile Set<String> declaredSymbols = Set.of();

    public void replaceAll(Collection<String> symbols) {
        this.declaredSymbols = Set.copyOf(symbols);
    }

    public boolean isDeclared(String symbol) {
        return declaredSymbols.contains(symbol);
    }

    /** 관측용. */
    public Set<String> declaredSymbols() {
        return declaredSymbols;
    }
}
```

## 2. 매니저가 레지스트리를 게시

`refreshSubscriptions()`는 이미 `ensureConnected()` → `declareIfChanged()` → `pingIfDue()`를 부르는 상태 수렴 지점이다. 게시도 여기서 한다.

```java
public void refreshSubscriptions() {
    if (!properties.enabled() || connections.isEmpty()) {
        coveredSnapshot = List.of();
        declaredSymbolRegistry.replaceAll(Set.of());   // ← 반드시 함께 비운다
        return;
    }

    // ... 기존 로직 ...

    coveredSnapshot = List.copyOf(covered);
    declaredSymbolRegistry.replaceAll(declaredSymbols());
}
```

**함정**: early return 경로에서 레지스트리를 비우지 않으면, `TOSS_WS_ENABLED=false`로 내리거나 슬롯을 전부 잃은 뒤에도 마지막 목록이 남아 **매칭이 REST를 영원히 건너뛴다.** 캐시는 TTL(30초)로 사라지므로 그 뒤로는 매칭이 계속 `null` 호가를 받아 외부 체결이 조용히 멈춘다. Compose 기본값이 `TOSS_WS_ENABLED=false`라 로컬에서 바로 밟는 경로다.

`@PreDestroy shutdown()`에서도 명시적으로 비운다. `releaseAll()` 이후에는 `refreshSubscriptions()`가 다시 돌지 않을 수 있다.

### 갱신 주체 — 매니저 250ms 주기로 간다

| | 매니저 게시 (선택) | 커넥션 즉시 게시 |
| --- | --- | --- |
| 변경 범위 | 매니저 3줄 | 커넥션 5개 지점 + 슬롯별 부분 갱신 API |
| 지연 | 최대 250ms | 없음 |
| 동시성 | 단일 스케줄러 스레드 | WS 리스너 스레드 다수 |

매니저 쪽을 고른다. 250ms 창의 실질 위험이 작기 때문이다 — 피드가 방금 죽었다면 (1) 캐시는 최대 250ms 낡은 것뿐이고, (2) 푸시가 끊겼으니 `markDirty()`도 없어서 그 창에서 매칭이 트리거될 일 자체가 드물다. 반면 커넥션 쪽 게시는 `declaredSymbols`를 건드리는 5개 지점을 모두 고쳐야 하고, 커넥션은 자기 슬롯 종목만 아는데 레지스트리는 전역이라 `replaceSlot(slotIndex, symbols)` 형태의 부분 갱신 API가 추가로 필요하다.

## 3. 매칭 전용 게이트로 교체

```java
/**
 * 체결에 쓸 호가.
 *
 * <p>WebSocket 구독 종목은 캐시를 그대로 쓴다. 토스는 호가가 <b>바뀔 때만</b> 프레임을 보내므로,
 * receivedAt이 주문 접수보다 앞선다는 건 낡았다는 뜻이 아니라 그 이후로 변동이 없었다는 뜻이다.
 * REST를 불러도 같은 값을 받고, 왕복 지연만큼 낡은 응답이 receivedAt=now로 덮어써서
 * 신선도 메타데이터만 망가진다.
 *
 * <p>이 등식은 피드가 살아 있을 때만 성립한다. 판정에 declaredSymbols를 쓰는 이유가 그것이다 —
 * 연결 실패·error frame·슬롯 상실·구독 ACK 불일치 모두에서 목록이 비워지므로,
 * 피드가 죽으면 그 순간부터 REST 경로로 돌아온다.
 *
 * <p>구독 종목인데 캐시가 없으면 {@code null}을 돌려준다. 아직 첫 프레임도 폴링 씨딩도
 * 오지 않은 짧은 구간이고, 이번 라운드는 외부 유동성 없이(내부 체결만) 넘어간다.
 * 씨딩은 {@code OrderBookPollingService}가 1초 주기로 이미 하고 있다(4절).
 */
public OrderBookResponse getOrderBookForMatching(String symbol, LocalDateTime notBefore) {
    OrderBookResponse cachedResponse = getCachedOrderBook(symbol);

    if (declaredSymbolRegistry.isDeclared(symbol)) {
        return cachedResponse;
    }
    if (cachedResponse != null
        && cachedResponse.receivedAt() != null
        && !cachedResponse.receivedAt().isBefore(notBefore)) {
        return cachedResponse;
    }
    return fetchCacheAndPublish(symbol);
}
```

- `getOrderBookNoOlderThan`을 이 메서드로 **대체**한다. 호출자는 `MatchingEngineTransactionService:61` 하나뿐이다.
- 이름을 바꾸는 이유: 구독 종목에는 `NoOlderThan` 보장을 더 이상 하지 않으므로 기존 이름이 계약을 잘못 설명하게 된다.
- REST 조회 엔드포인트가 쓰는 `getOrderBook(symbol)`은 건드리지 않는다.

**`null` 반환이 안전한 근거**: `MatchingEngineTransactionService`의 `askLevels()`/`bidLevels()`가 이미 `orderBook == null`을 `List.of()`로 처리한다. 그러면 그 라운드는 내부 체결만 진행되는데, 문서 §13.1이 "내부 주문끼리의 체결은 Toss 호가를 전혀 참조하지 않으므로 이 신선도 규칙의 적용 대상이 아니다"라고 명시하고 있어 우회가 아니라 문서화된 의미 그대로다. `applyMarketOrderRemainingPrice()`는 `dailyPriceRange`를 쓰므로 영향받지 않는다.

## 4. 폴링은 손대지 않는다

`OrderBookPollingService.pollTargets()`가 이미 씨딩과 백스톱을 하고 있다.

```java
.filter(symbol -> !covered.contains(symbol) || !orderBookService.isFresherThan(symbol, threshold))
```

`isFresherThan()`은 **캐시가 null이면 false를 반환**한다. 그래서 갓 배정된 캐시 없는 구독 종목은 `covered`에 있어도 필터를 통과해 1초 폴링 대상이 되고, 첫 응답으로 캐시가 생기는 순간 빠진다. 대상 집합이 `pendingOrderSymbols()`라 매칭이 신경 쓰는 종목과 정확히 일치한다.

그리고 씨딩되면 루프가 스스로 닫힌다: `refreshAndPublish()` → `applyOrderBook()` → `orderBookChanged(null → 값)`이 참 → `markDirty()` → 드레이너가 150ms 안에 재매칭.

**폴링 게이트를 `isDeclared` 기준으로 바꾸면 안 된다.** `coveredSymbols()`의 javadoc이 그 이유를 적어놨다.

> ACK 확인을 기준으로 삼으면 재연결 때마다 전 종목이 한꺼번에 폴링 대상이 되어 REST 예산이 터진다.

`scheduleReconnect()`가 백오프(기본 1초, 최대 30초, 지터)를 거는 동안 그 슬롯의 100종목이 전부 미선언 상태가 된다. 폴링이 `isDeclared` 기준이면 100종목 × 초당 1회가 실효 한도 ~12회로 몰린다. `rotate()`는 순서만 섞을 뿐 대상 개수를 줄이지 않는다.

**두 소비자가 서로 다른 신호를 쓰는 것이 의도다.**

| | 매칭 게이트 | 폴링 게이트 |
| --- | --- | --- |
| 신호 | `declaredSymbols` (이벤트 기반) | `coveredSymbols` + 신선도 임계값 |
| 폴백 단위 | 주문 1건당 REST 1회 | 종목 전체가 한꺼번에 |
| 오판의 대가 | 낡은 호가로 체결 (정합성) | 예산 폭발 (가용성) |
| 그래서 | 엄격하게 | 관대하게 |

같은 질문에 두 답이 필요한 이유는 틀렸을 때 잃는 것이 다르기 때문이다.

## 5. 문서 갱신

`current-implementation-overview.md` §13.1 "호가 신선도 보장"이 이 변경과 정면으로 어긋나므로 다시 쓴다. 담을 내용:

- 구독 종목: 캐시를 그대로 사용. 근거는 "변동 시에만 프레임 전송" → `receivedAt`은 마지막 변동 시각.
- 비구독 종목: 기존 `receivedAt >= submittedAt` 규칙 유지, 미달 시 REST.
- 판정 신호가 `coveredSymbols`가 아니라 `declaredSymbols`인 이유.
- 구독 종목인데 캐시가 없는 구간의 동작(내부 체결만, 폴링이 씨딩).

## 구현 순서

1. 캐시 TTL 기본값을 120초로 상향 (0절). 나머지 절이 이걸 전제한다.
2. `DeclaredWebSocketSymbolRegistry` 신설 (1절).
3. `TossOrderBookWebSocketManager`에 주입 + `refreshSubscriptions()` 게시 + early return·`shutdown()` 비우기 (2절).
4. `OrderBookService`에 레지스트리 주입, `getOrderBookNoOlderThan` → `getOrderBookForMatching` 교체 (3절).
5. `MatchingEngineTransactionService:61` 호출 변경.
6. 문서 §13.1 갱신 (5절).

3번의 early return 처리를 4번보다 먼저 끝내야 한다. 순서가 바뀌면 `TOSS_WS_ENABLED=false`인 로컬 환경에서 외부 체결이 조용히 멈춘 채로 테스트하게 된다.

## 검증 방법

**REST 호출이 실제로 사라졌는가**

- WebSocket을 켜고 구독 중인 종목에 신규 주문을 반복 접수하면서, `TossApiRateLimiter`의 `MARKET_DATA_GROUP` 소비량이 주문 수에 비례해 증가하지 **않는지** 확인한다. 이게 이번 변경의 핵심 지표다.
- 정원 밖(비구독) 종목에 같은 주문을 내면 기존대로 REST가 나가는지 확인한다.

**낡은 캐시로 체결되는가 (의도된 동작)**

- 구독 종목의 호가를 인위적으로 조용하게 두고(변동 없음), 캐시 `receivedAt`보다 나중에 주문을 접수한다.
- 기대: REST 호출 없이 캐시의 ask/bid로 체결된다.

**피드가 죽으면 즉시 폴백하는가**

- 연결을 강제로 끊고(또는 `server-shutdown` error frame 주입) 250ms 뒤 주문을 접수한다.
- 기대: `isDeclared`가 false가 되어 REST가 나간다. `declaredSymbols()` 관측값이 비었는지 함께 본다.

**WebSocket을 끈 상태 (가장 중요한 회귀)**

- `TOSS_WS_ENABLED=false`로 기동하고 주문을 접수한다.
- 기대: 레지스트리가 비어 있어 모든 종목이 REST 경로로 간다. 외부 체결이 정상 동작한다.
- 이 검증이 2절 early return 함정을 잡는다.

**갓 배정된 종목**

- 캐시가 없는 종목을 새로 구독 대상에 올리고 즉시 주문을 접수한다.
- 기대: 첫 라운드는 외부 체결 없이 내부 체결만, 1초 내 폴링 씨딩, `markDirty` → 드레인 → 다음 라운드에 외부 체결. 주문이 영구 미체결로 남지 않는지가 요점이다.

## 추가할 자동화 테스트

- 구독 선언된 종목이면 `receivedAt < notBefore`여도 `fetchCacheAndPublish`가 호출되지 않는지
- 구독 선언된 종목의 캐시가 null이면 `null`을 반환하고 REST를 부르지 않는지
- 비구독 종목은 기존 `receivedAt >= notBefore` 규칙이 그대로 적용되는지
- 레지스트리가 비어 있으면(WS 비활성) 모든 종목이 REST 경로로 가는지
- `refreshSubscriptions()`의 early return에서 레지스트리가 비워지는지
- 커넥션이 `close()`된 뒤 매니저의 `declaredSymbols()`에서 그 슬롯 종목이 빠지는지
- 매칭이 `null` 호가를 받아도 내부 체결이 정상 진행되는지
- 토스가 거절한 종목(`rejectedSymbols`)이 `declaredSymbols`에 들어가지 않는지

## 이번 범위에서 제외

- **폴링 게이트 변경** — 4절의 이유로 `coveredSymbols` + 신선도 임계값을 유지한다.
- **신선도 임계값(30초) 상향** — `declaredSymbols`라는 이벤트 신호가 생기면 폴링 임계값을 훨씬 크게 잡을 여지가 생긴다(피드 사망을 이제 이벤트로 잡으므로, 임계값은 "소켓은 살아 있는데 특정 종목만 조용히 안 오는" 경우만 담당하면 된다). 조용한 구독 종목 200개면 30초당 1회씩 초당 약 6.7회를 계속 쓰고 있어 실익도 있다. 다만 재연결 스탬피드와 얽혀 있고, 올릴 때 0절의 `TTL > 임계값` 불변식도 함께 지켜야 해서 별도로 다룬다.
- **`DailyPriceRangeService.getDailyPriceRange()` 호출** — `SymbolMatchingProcessor`가 매칭 전에 종목당 한 번 부르는 별도 Toss 경로다. 캐시 정책이 호가와 다르므로 이번 변경과 분리한다.
- **다중 인스턴스(§19.6)** — `declaredSymbols`는 인스턴스 메모리다. 슬롯을 갖지 않은 인스턴스에서 매칭이 돌면 목록이 비어 REST로 간다. 정합성은 깨지지 않고 호출만 낭비된다. 현재 코드가 이미 "한 인스턴스가 두 슬롯 모두 소유"를 전제하므로 그 전제와 함께 풀어야 한다.
- **§19.3 외부 호가 소비 범위** — 재조회가 줄어 같은 snapshot을 공유하는 주문이 늘어난다. 다만 이건 결함이 아니라 정책으로 정리되었다(§19.3): 외부 호가는 각 주문이 마주하는 시장 깊이이고, 한 주문 안에서만 소비한다. 이번 변경은 그 정책과 어긋나지 않는다.
- **원장·체결 로직** — 08번 계획의 범위다. 겹치지 않는다.

## 확인이 필요한 가정

- **가장 중요: "프레임 없음 = 변동 없음"이 이 계획 전체의 토대다.** 토스 AsyncAPI 스펙의 "호가가 갱신될 때마다 프레임을 전달"과 06번 검증 실측에 근거한다. 공급자가 조용히 conflation·throttling을 도입하거나, 변동이 있는데도 프레임을 누락하는 구간이 있다면 이 등식이 깨지고 낡은 호가로 체결하게 된다. 스펙 변경 공지를 이 문서와 함께 확인해야 한다.
- **구독 ACK가 프레임 수신을 보장하지는 않는다.** 소켓·TCP 수준 단절은 `onClose`/`onError` → `scheduleReconnect()`로, 180초 침묵은 `pingIfDue()`로, 종목별 어긋남은 `handleSubscriptionsAck()` 불일치 검사로 각각 잡힌다. 그래도 "소켓은 살아 있고 ACK도 받았는데 특정 종목 프레임만 안 오는" 경우는 남는다. 이 잔여 위험은 폴링의 30초 임계값이 백스톱으로 덮는다 — 그래서 4절에서 폴링을 손대지 않는 것이 이 계획의 안전성에 기여한다.
- **250ms 게시 지연**(2절) 동안 죽은 피드가 살아 있는 것으로 보인다. 이 창에서 매칭이 트리거될 확률이 낮다는 판단에 근거해 감수했다. 운영에서 이 창에 걸린 체결이 관측되면 커넥션 즉시 게시로 바꾼다.
- **`캐시 TTL > 폴링 신선도 임계값` 불변식**(0절)이 깨지면 이 계획 전체가 조용히 무너진다. 두 값은 서로 다른 설정 파일 항목이라 한쪽만 바뀌기 쉽다. `orderbook.polling.staleness-threshold-ms`를 올리는 변경에는 반드시 `orderbook.cache.ttl-seconds`를 함께 확인해야 한다.
- **구독 종목의 캐시 부재 구간이 짧다**고 가정했다. 폴링이 1초 주기로 씨딩한다는 전제인데, REST 예산이 소진된 상태에서는 `refreshAndPublish()`가 예외를 삼키고 넘어가므로 이 구간이 길어질 수 있다. 그동안 그 종목은 내부 체결만 된다.
