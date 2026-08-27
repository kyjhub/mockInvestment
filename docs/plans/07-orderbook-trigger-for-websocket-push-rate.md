# WebSocket 푸시 빈도에 맞춘 매칭 트리거 재설계

## Context

06번 계획으로 호가 수신을 REST 폴링에서 WebSocket으로 전환했다. 그때 "REST 응답과 WebSocket 푸시가 `OrderBookService.applyOrderBook()` 한 지점으로 합류하므로 매칭 엔진은 수정하지 않는다"를 장점으로 삼았는데, **합류 지점을 통과하는 트래픽 양이 10배 이상 늘어난다는 것을 계산하지 않았다.** 합류 설계 자체는 유효하지만 하류의 흡수 능력을 다시 봐야 한다.

이번 계획은 그 후속이며, 06번 구현 과정에서 만든 **배포된 결함 두 건도 함께 고친다.**

### 실측: 푸시 빈도가 폴링의 7배다

06번 검증 때 실제 연결해서 측정한 값이다 (미국 정규장).

| 종목 | 푸시 빈도 |
|---|---|
| AAPL | 초당 **8.0**회 |
| TSLA | 초당 **6.5**회 |

폴링일 때는 `orderbook.polling.fixed-delay-ms`(1초)가 상한이었다. WebSocket에는 그 상한이 없다 — 토스 AsyncAPI 전체에 `throttle`·`interval`·conflation 관련 파라미터가 하나도 없고, 스펙 문구도 "호가가 갱신될 때마다 프레임을 전달"이다. **유입 속도는 시장이 정하고 우리가 정할 수 없다.**

### 매칭 트리거가 넘친다

`applyOrderBook()`은 호가가 바뀌면 화면 전파와 매칭 트리거를 **같은 조건으로 함께** 발행한다.

```java
if (orderBookChanged(previousResponse, response)) {
    incrementOrderBookVersion(symbol);
    publishOrderBook(symbol, response);                // 화면용 — 매 변경마다 필요
    symbolMatchRequestedStreamPublisher.publish(...);  // 매칭용 — 매 변경마다 불필요
}
```

두 요구의 성격이 다르다. **화면은 모든 틱이 필요하고 매칭은 그렇지 않다.** 폴링 시절에는 1초 주기가 자연스러운 상한 역할을 해서 이 구분이 필요 없었는데, WebSocket이 그 상한을 걷어내면서 드러났다.

| | 값 |
|---|---|
| 스트림 유입 | 200종목 × 약 7회 = **초당 ~1,400건** (`orderBookChanged` 필터 후에도 1,000건 수준) |
| 컨슈머 소비 | `count(batch-size:10)` × `fixed-delay 100ms` = **초당 ~60~100건** |
| 격차 | **10~20배 초과 공급** |

컨슈머가 영구히 밀린다. Consumer Group이 인스턴스에 분산하더라도 따라잡으려면 14대 이상이 필요해 답이 되지 않는다.

### 근본 원인은 자료구조 선택이다

스트림은 append-only 로그라 같은 종목의 트리거 1,400건이 전부 별개 레코드로 쌓인다. 그런데 우리에게 필요한 정보는 "이 종목을 매칭해야 한다"는 **사실 하나**지 몇 번 요청됐는지가 아니다. 중복 제거가 필요한 자리에 중복을 보존하는 자료구조를 쓴 것이고, 발행 쿨다운을 두는 식의 대응은 그걸 시간으로 눌러 덮을 뿐 "유입 > 소비"가 되는 순간 다시 밀린다.

**집합(Set)을 쓰면 중복 제거가 구조적으로 해결된다.** 집합에는 같은 종목이 한 번만 남으므로 **저장 크기가 푸시 횟수에 비례해 증가하지 않는다.** 스트림에는 이 성질이 없다.

주의할 점 — 집합 크기의 상한은 "아직 drain되지 않은 서로 다른 종목 수"이지 WebSocket 구독 한도(200)가 아니다. `markDirty()`는 REST 경로에서도 호출되므로 WebSocket 담당 종목, 200종목을 넘겨 REST 폴백으로 내려간 종목, 사용자가 REST로 직접 조회한 종목이 모두 들어온다. 그리고 **Redis `SADD` 호출 자체는 여전히 초당 1,400회 발생한다.** 줄어드는 것은 Redis 작업 횟수가 아니라 **저장 cardinality와 실제 매칭 실행 횟수**다.

### 배포된 결함 두 건

06번 구현에서 만든 문제다. dirty set 전환과 무관하게 지금 동작에 영향을 준다.

**결함 A — `@Scheduled` 스레드 풀이 1개다.**

프로젝트에 `TaskScheduler` 설정이 없어 Spring Boot 기본값(`spring.task.scheduling.pool.size` = 1)이 적용된다. 현재 `@Scheduled` 메서드가 10개인데 **전부 스레드 하나를 공유한다.**

| 스케줄러 | 주기 |
|---|---|
| `MatchingEngineStreamConsumer.consumeNewRecords` | 100ms |
| `TossOrderBookWebSocketManager.refreshSubscriptions` | **250ms** |
| `MatchingEngineStreamConsumer.recoverPendingMessages` | 1s |
| 호가·현재가·고저가 폴링 3종 | 1s / 20s |
| `manageSlots`, `publishLocalSubscriptions` | 5s |
| `PendingOrderRematchScheduler` | 30s |

호가 폴링 한 번이 12종목 × 150ms = 약 2초 걸리면 그동안 **WebSocket 구독 재선언과 스트림 소비가 둘 다 멈춘다.** 재선언이 밀리면 신규 구독 종목이 데이터를 받지 못하고, 심하면 60초 PING까지 밀려 연결이 끊긴다. 06번에서 250ms 재선언 스케줄러를 추가하면서 스레드 풀을 확인하지 않은 결과다.

**결함 B — 폴링 신선도 게이트가 비-WebSocket 종목까지 막는다.**

06번에서 폴링 제외 기준을 "WebSocket 담당 종목인가"에서 "캐시가 신선한가"로 바꾸면서, WebSocket이 담당하지 않는 종목까지 30초 게이트에 걸리게 만들었다.

| 시각 | 캐시 나이 | 판정 |
|---|---|---|
| t=0 | 0초 | 폴링 |
| t=1 ~ t=30 | 1~30초 | 30초 미만 → **계속 건너뜀** |
| t=31 | 31초 | 폴링 |

**1초 주기여야 할 종목이 실질 31초 주기가 된다.** 30초라는 값은 "WebSocket이 대신 채워주고 있는가"를 판정하려고 고른 것인데 아무도 채워주지 않는 종목에까지 적용됐다. "담당 목록을 몰라도 되니 결합도가 낮아진다"는 06번의 판단이 과했다 — "WS가 채우는 중"과 "아무도 안 채움"을 구분하려면 담당 여부가 필요하다.

---

## 0. `@Scheduled` 스레드 풀 분리 (결함 A)

### 0.1 기본 풀 확대

```yaml
spring:
  task:
    scheduling:
      pool:
        size: ${SCHEDULING_POOL_SIZE:4}
      thread-name-prefix: scheduled-
```

### 0.2 무거운 작업은 전용 스케줄러로 분리

풀만 키우면 느린 작업이 여전히 빠른 작업을 밀어낼 수 있다. **지연에 민감한 작업**과 **오래 걸리는 작업**을 나눈다.

| 성격 | 대상 | 배치 |
|---|---|---|
| 지연 민감 | WS 구독 재선언(250ms), 스트림 소비(100ms), PEL 복구 | 기본 풀 |
| 블로킹 I/O | 호가·현재가·고저가 REST 폴링 | 전용 풀 |
| DB + 매칭 | dirty drain (3절) | 전용 풀 |

Spring Framework 6.1부터 `@Scheduled(scheduler = "beanName")`으로 스케줄러를 지정할 수 있다. Spring Boot 3.5는 Spring 6.2이므로 사용 가능하다.

```java
@Bean
public TaskScheduler dirtyDrainScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(dirtyDrainPoolSize);
    scheduler.setThreadNamePrefix("dirty-drain-");
    return scheduler;
}
```

**worker 수 상한은 DB 커넥션 풀 크기를 기준으로 잡는다.** 매칭이 커넥션을 점유하므로, HikariCP 기본 최대(10)를 감안해 dirty drain worker는 2~3개를 넘기지 않는다. 이 값은 커넥션 풀 설정과 함께 조정해야 한다.

## 1. 폴링 신선도 게이트를 WebSocket 담당 종목에만 적용 (결함 B)

### 1.1 담당 여부로 게이트를 건다

```java
Set<String> webSocketCovered = Set.copyOf(webSocketManager.coveredSymbols());
Duration threshold = Duration.ofMillis(properties.stalenessThresholdMs());

// WebSocket이 채우는 중일 때만 건너뛴다. 아무도 안 채우는 종목은 원래 주기대로 돈다.
if (webSocketCovered.contains(symbol) && orderBookService.isFresherThan(symbol, threshold)) {
    continue;
}
```

06번에서 얻으려던 성질은 유지된다. WebSocket이 정상이면 담당 종목 폴링 0회, 멎으면 30초 뒤 자동 폴백, 푸시 재개 시 자동 복귀. 달라지는 건 **WS 담당이 아닌 종목이 게이트를 우회한다**는 것뿐이다.

### 1.2 캐시된 담당 목록 조회 메서드가 필요하다

06번 계획에는 "`webSocketSymbols()`는 이미 public이고 1초 캐싱이 걸려 있다"고 적었는데 **사실이 아니다.** 캐시는 private `cachedWebSocketSymbols()`에만 있고, public `webSocketSymbols()`는 호출할 때마다 `orderedActiveSymbols()`를 거쳐 **DB를 다시 조회한다.** 그대로 쓰면 폴링 주기마다 불필요한 조회가 발생한다.

캐시된 목록을 반환하는 public 메서드(`coveredSymbols()`)를 신설하고, 기존 `webSocketSymbols()`는 private으로 내린다.

### 1.3 "담당"은 배정이지 수신 확인이 아니다

`coveredSymbols()`가 돌려주는 것은 **WebSocket에 배정하려는 종목**이지 실제로 프레임을 받고 있는 종목이 아니다. 연결이 끊겼거나 구독이 거절된 종목도 covered로 판정된다.

- **연결 단절** — 의도한 동작이다. 캐시가 30초를 넘기면 폴백되고, 짧은 재연결에는 발동하지 않는다.
- **구독 거절**(`stock-not-found` 등) — 의도하지 않은 동작이다. WebSocket이 영원히 채우지 않을 종목인데 매번 30초를 기다린다. `TossOrderBookWebSocketConnection`이 이미 `rejectedSymbols`를 들고 있으므로, 이를 매니저가 취합해 `coveredSymbols()`에서 제외한다.

## 2. 공통 매칭 실행기 추출 + 호가 version 안정화 루프 제거

### 2.1 왜 먼저 해야 하는가

3절의 dirty drainer는 종목 락을 잡고 매칭해야 하는데, **그 로직이 `MatchingEngineStreamConsumer.processRecord()` 안에 private으로 묶여 있다.** `MatchingEngineTransactionService.matchSymbol()`을 직접 호출하면 **종목 락이 걸리지 않아** 다중 인스턴스에서 중복 체결이 발생한다.

그리고 제거 대상인 `matchUntilOrderBookVersionIsStable()`도 락 획득과 해제 사이에 끼어 있어, 어차피 같은 자리를 건드려야 한다. 두 작업을 함께 한다.

### 2.2 `Service/SymbolMatchingProcessor.java` 신설

```java
public enum SymbolMatchingResult { SUCCESS, LOCK_BUSY, QUOTA_UNAVAILABLE }

public SymbolMatchingResult process(String symbol) {
    String lockValue;
    try {
        lockValue = symbolOrderLockService.acquire(symbol);
    } catch (IllegalArgumentException e) {
        return SymbolMatchingResult.LOCK_BUSY;
    }

    try {
        DailyPriceRangeResponse dailyPriceRange = dailyPriceRangeService.getDailyPriceRange(symbol);
        matchingEngineTransactionService.matchSymbol(symbol, dailyPriceRange);  // 1회만 실행
        return SymbolMatchingResult.SUCCESS;
    } catch (TossApiQuotaUnavailableException e) {
        return SymbolMatchingResult.QUOTA_UNAVAILABLE;
    } finally {
        symbolOrderLockService.release(symbol, lockValue);
    }
}
```

**일반 예외는 잡지 않고 전파한다.** 호출자마다 대응이 다르기 때문이다.

| 결과 | Stream consumer | Dirty drainer |
|---|---|---|
| `SUCCESS` | ACK | 종료 |
| `QUOTA_UNAVAILABLE` | ACK + 재시도 카운트 초기화 | 재등록하지 않음 (3.4 참고) |
| `LOCK_BUSY` | **ACK하지 않음(PEL 유지) + dirty set 등록** | dirty set 재등록 |
| 예외 전파 | `handleFailure` → 재시도 → DLQ | 로그 + 카운터, 재등록하지 않음 |

Stream consumer의 ACK·PEL·재시도·DLQ 정책은 그대로 유지된다. `LOCK_BUSY`에 dirty set 등록이 추가되는데, 이유는 3.5절에 적었다.

### 2.3 안정화 루프 제거

`matchUntilOrderBookVersionIsStable()`을 없애고 `matchSymbol()`을 한 번만 호출한다. `OrderBookService.getOrderBookVersion()`·`incrementOrderBookVersion()`도 다른 사용처가 없으면 함께 제거하고, **기존 `orderbook:version:*` Redis 키 정리를 배포 절차에 포함한다.**

**① 트리거 메커니즘과 중복이다.** 호가가 바뀌면 그 변경 자체가 다음 트리거를 만든다. dirty set으로 가면 매칭 중 들어온 갱신이 집합에 다시 쌓여 다음 drain이 처리한다.

**② 실제 매칭 엔진에 없는 개념이다.** 거래소는 이벤트 구동이다. 호가창이 바뀌면 그 변경이 다음 매칭 이벤트를 만들 뿐, "처리 도중 바뀌었으니 지금 다시 돌자"는 루프는 없다.

**③ 처리 시간이 예측 불가능해진다.** 이쪽이 실무적으로 더 중요하다. 지금은 한 종목의 처리 시간이 시장 변동성에 비례해 늘어나고, 종목 락(TTL 15초)을 더 오래 붙잡는다. **가장 바쁜 순간에 가장 느려지는** 구조다. 06번 실측에서 같은 밀리초에 10개 프레임이 몰리는 것을 확인했으므로 가정이 아니다.

**잃는 것** — 즉시 재매칭이 drain 주기만큼 늦어진다. 폴링 시절 1초였던 것과 비교하면 여전히 개선이다. **부분 체결 동작과 가격-시간 우선순위는 달라지지 않는다.** 잔량은 `PARTIALLY_FILLED`로 남아 다음 트리거에 이어서 체결되고, 우선순위는 `findMatchableOrdersBySymbol()`의 `order by`가 보장하므로 트리거 자료구조와 무관하다.

## 3. 호가 트리거를 Redis Stream에서 dirty set으로 이동

### 3.1 트리거 종류별로 자료구조를 나눈다

| 트리거 | 자료구조 | 근거 |
|---|---|---|
| `ORDER_BOOK_UPDATED` | **dirty set** (신규) | 초당 수천 건, 중복이 무의미, 유실돼도 다음 틱이 다시 넣음 |
| `ORDER_SUBMITTED` | 스트림 유지 | 드물고 중요함. 유실되면 사용자 주문이 지연되므로 at-least-once와 PEL 복구가 필요 |
| `SAFETY_NET` | 스트림 유지 | 30초 주기, 최종 방어선 |

### 3.2 `OrderBookService.applyOrderBook()` 변경

```java
if (orderBookChanged(previousResponse, response)) {
    publishOrderBook(symbol, response);               // 화면 전파 — 변경 없음
    dirtyOrderBookSymbolRegistry.markDirty(symbol);   // 스트림 발행을 대체
}
```

화면 전파는 손대지 않는다. 화면은 모든 틱이 필요하고, Pub/Sub는 애초에 백로그가 쌓이지 않는다.

`markDirty()`가 Redis 장애로 실패하면 삼키고 로그만 남긴다 — 30초 안전망이 최종 복구를 담당한다.

### 3.3 `Service/DirtyOrderBookSymbolDrainScheduler.java` 신설

```java
@Scheduled(scheduler = "dirtyDrainScheduler",
           fixedDelayString = "${matching-engine.dirty-drain.fixed-delay-ms:150}")
public void drain() {
    // Spring Data Redis에서 pop(K, long)의 반환 타입은 List다.
    List<String> symbols = stringRedisTemplate.opsForSet().pop(DIRTY_KEY, drainBatchSize);
    if (symbols == null || symbols.isEmpty()) {
        return;
    }

    Set<String> pendingSymbols = Set.copyOf(activeSymbolRegistry.pendingOrderSymbols());
    for (String symbol : symbols) {
        // 한 종목의 실패가 이미 pop된 나머지 종목을 유실시키지 않도록 종목별로 격리한다.
        try {
            if (!pendingSymbols.contains(symbol)) {
                continue;  // 미체결 주문이 없으면 매칭할 대상이 없다
            }
            handle(symbol, symbolMatchingProcessor.process(symbol));
        } catch (Exception e) {
            log.error("Dirty drain failed. symbol={}", symbol, e);
            drainFailureCounter.increment();
        }
    }
}
```

`SPOP key count`를 쓰는 이유는 세 가지다.

- **원자적** — 여러 인스턴스가 동시에 호출해도 같은 시점에 같은 member를 중복으로 가져가지 않는다.
- **자기 정리** — 꺼내면서 제거하므로 미체결 주문이 없는 종목도 집합에 쌓이지 않는다.
- **먼저 꺼내고 나중에 매칭** — 매칭 도중 도착한 갱신은 집합에 다시 쌓여 다음 drain이 처리한다. 매칭 후에 제거하면 그 갱신까지 지워진다.

미체결 종목 필터를 발행 시점이 아니라 drain 시점에 두는 이유는 비용이다. 발행 시점에 걸면 푸시마다 `SISMEMBER`가 초당 1,400번 나가지만, drain 시점이면 주기당 한 번으로 끝난다.

### 3.4 `SPOP`만으로는 신호 유실을 막지 못한다

**`SPOP`의 원자성은 "같은 시점에 같은 member를 두 consumer가 꺼내지 못한다"만 보장한다.** 처리 중 다시 `SADD`된 member는 보호하지 않는다.

```text
A: SPOP AAPL
A: AAPL 락 획득, 매칭 시작
   호가 갱신 → SADD AAPL
B: SPOP AAPL          ← 다시 들어온 것을 꺼냄
B: AAPL 락 획득 실패
B: 그냥 버리면 → 마지막 호가 변경 신호 소실
```

이 경우 다음 푸시가 올 때까지, 거래가 뜸한 종목이면 **30초 안전망까지** 기다리게 된다. 그래서 결과별 재등록 정책을 명시한다.

| 결과 | 처리 | 근거 |
|---|---|---|
| `LOCK_BUSY` | **즉시 `SADD` 재등록** | 락 소유자가 곧 끝나므로 자기 제한적. 다음 drain(150ms)에 처리된다 |
| `QUOTA_UNAVAILABLE` | 재등록하지 않음 | 예산이 없는 동안 재등록하면 150ms 간격 hot loop가 된다. 예산 회복 후 다음 푸시·폴링이 신호를 재생성하고, 30초 안전망이 최종 보장 |
| 일반 예외 | 재등록하지 않음, ERROR 로그 + 카운터 | 결정적 실패는 재등록해도 반복된다. 30초 안전망이 스트림으로 재발행하므로 복구는 보장된다 |

**재등록을 최소화하는 이유**는 dirty 신호가 자기 복구되는 성질을 갖기 때문이다. 활성 종목은 다음 푸시가 곧 다시 넣고, 그마저 없으면 안전망이 있다. 무한 재시도 storm을 만드는 것보다 낫다.

`LOCK_BUSY` 재등록만 예외인 이유는, 락 경합이 **정상 동작이면서도 자연 재생성을 기대할 수 없는 유일한 경우**이기 때문이다 (거래가 뜸한 종목은 다음 푸시가 언제 올지 모른다).

### 3.5 Stream consumer의 락 경합도 dirty set으로 보조한다

`ORDER_SUBMITTED`가 스트림에 남으므로, dirty drainer가 락을 잡은 동안 신규 주문 이벤트가 도착하면 컨슈머는 락 획득에 실패한다. 현재는 ACK 없이 `return`만 하므로 **PEL 복구(`pending-min-idle-ms` 기본 5초)까지 기다린다.** drain 주기가 150ms라 락 경합 빈도는 오히려 늘어날 수 있다.

컨슈머의 `LOCK_BUSY` 처리에 `markDirty(symbol)`를 추가한다.

- **빠른 경로** — dirty set에 등록되어 150ms 안에 재시도된다.
- **내구 경로** — ACK하지 않으므로 PEL에 그대로 남고, dirty 신호가 유실돼도 5초 뒤 `XCLAIM`으로 복구된다.

스트림은 주문 신호의 내구성을 유지하고, dirty set은 보조 신호로 지연만 줄인다.

### 3.6 예상 효과

| | 현재 | 변경 후 |
|---|---|---|
| 트리거 저장 | 초당 ~1,400건 누적 (백로그) | 서로 다른 종목 수만큼 (푸시 횟수 무관) |
| 매칭 실행 | 소비 능력 초과 | 미체결 종목 수 × drain 주기 |
| Redis 작업 | 초당 ~1,400 `XADD` | 초당 ~1,400 `SADD` (변화 없음) |

**푸시 빈도와 무관하게 매칭 실행 횟수의 상한이 정해진다**는 것이 핵심이다. Redis 작업 횟수 자체는 줄지 않는다.

## 4. 기존 Stream backlog 처리

`XACK`은 PEL에서만 제거할 뿐 **스트림 레코드 자체를 지우지 않는다.** 그리고 배포 시점에 스트림에는 소비되지 않은 `ORDER_BOOK_UPDATED` 레코드가 대량으로 남아 있다 (백로그 상태이므로 `maxlen` 100만에 가까울 수 있다). 그대로 두면 새 컨슈머가 **오래된 호가 트리거를 몇 시간 동안 계속 처리한다.**

### 4.1 컨슈머가 `ORDER_BOOK_UPDATED`를 즉시 ACK하고 건너뛴다

```java
if ("ORDER_BOOK_UPDATED".equals(reason)) {
    acknowledge(record);   // 매칭하지 않고 배출만 한다
    clearRetryCount(record);
    return;
}
```

이 변경 후에는 스트림에 정상적으로 들어올 `ORDER_BOOK_UPDATED`가 없으므로 영구적으로 둬도 무해하고, 백로그는 매칭 없이 ACK만 하므로 빠르게 빠진다.

### 4.2 롤링 배포 중 신호 유실 구간

배포 중에는 구버전 인스턴스가 여전히 스트림으로 `ORDER_BOOK_UPDATED`를 발행하는데, 신버전 컨슈머는 그것을 건너뛴다. 그 사이 해당 트리거는 유실된다.

**30초 안전망이 이 구간을 덮는다.** 배포 시간이 30초를 크게 넘지 않는다면 추가 조치가 필요 없고, 넘긴다면 배포 전에 `matching-engine.rematch.fixed-delay-ms`를 일시적으로 낮춘다.

### 4.3 배포 절차

1. 배포 전 `XLEN symbols:match-requested`와 consumer group lag를 기록한다.
2. 0·1절만 먼저 배포한다 (트리거 동작 변경 없음).
3. 2·3·4절을 배포한다.
4. 백로그가 빠지는 것을 확인한 뒤 `orderbook:version:*` 키를 정리한다.

---

## 설정 추가

```yaml
spring:
  task:
    scheduling:
      pool:
        size: ${SCHEDULING_POOL_SIZE:4}
      thread-name-prefix: scheduled-

matching-engine:
  dirty-drain:
    fixed-delay-ms: ${MATCHING_ENGINE_DIRTY_DRAIN_DELAY_MS:150}
    batch-size: ${MATCHING_ENGINE_DIRTY_DRAIN_BATCH_SIZE:20}
    pool-size: ${MATCHING_ENGINE_DIRTY_DRAIN_POOL_SIZE:2}
```

**튜닝이 필요한 값들이다.** `batch-size`·`pool-size`·`fixed-delay-ms`는 미체결 종목 수, 매칭 소요 시간, DB 커넥션 풀 크기에 따라 조정해야 한다. 특히 `pool-size × 동시 매칭 커넥션`이 HikariCP 최대 커넥션을 넘지 않아야 한다.

`fixedDelay`는 **매칭 시작 간격이 아니라 이전 실행 종료 후 대기 시간**이다. batch 처리가 5초 걸리면 다음 drain은 5.15초 뒤다. `batch-size`를 작게 유지하고 실행 시간 메트릭을 봐야 하는 이유다.

## 구현 순서

| 단계 | 내용 | 성격 |
|---|---|---|
| 0 | `@Scheduled` 스레드 풀 분리 | 배포된 결함 |
| 1 | 폴링 게이트를 WS 담당 종목에만 적용 | 배포된 결함 |
| 2 | `SymbolMatchingProcessor` 추출 + 안정화 루프 제거 | 선행 리팩터링 |
| 3 | dirty set 전환 | 구조 변경 |
| 4 | 스트림 backlog 처리 | 3과 함께 배포 |

0·1은 dirty set과 무관하게 지금 문제이므로 **먼저 단독 배포한다.** 2는 3의 전제다.

## 검증 방법

**0절**

- 호가 폴링이 오래 걸리는 상황을 만들고 WebSocket 구독 재선언과 스트림 소비가 지연되지 않는지 확인.
- 스레드 이름(`scheduled-`, `dirty-drain-`)으로 작업이 의도한 풀에서 도는지 확인.

**1절**

- `maxSymbols=1`로 설정하고 미체결 종목 2개를 만들어, **두 번째 종목이 REST 폴링되는지** 확인한다. 미체결 종목을 하나만 추가하면 우선순위가 높아 WebSocket에 들어가므로 overflow 경로를 타지 않는다.
- 검증 문구를 "1초마다 호출을 **시도**한다"와 "API 예산에 따라 실제 갱신된다"로 분리한다. REST 실효 한도가 초당 약 12회이므로 overflow 종목이 많으면 모든 종목이 1초마다 갱신되지는 않는다.

**3·4절**

- `SCARD orderbook:dirty`가 활성 종목 수 수준에 머물고 계속 증가하지 않는지.
- **호가 틱 수에 비례해 `XLEN symbols:match-requested`가 증가하지 않는지.** (`XLEN` 자체는 `ORDER_SUBMITTED`·`SAFETY_NET` 때문에 계속 늘고, ACK는 길이를 줄이지 않으므로 "증가하지 않음"은 잘못된 기준이다.)
- consumer group lag가 지속적으로 증가하지 않는지.
- 신규 스트림 레코드에 `reason=ORDER_BOOK_UPDATED`가 없는지.
- 인스턴스 2대에서 같은 종목이 중복 매칭되지 않는지.

## 추가할 자동화 테스트

- 매칭 중 같은 symbol이 다시 `SADD`된 경우 다음 drain에서 처리되는지
- 다른 인스턴스가 재등록된 symbol을 pop한 뒤 락 경합해도 신호가 남는지 (`LOCK_BUSY` → 재등록)
- batch 첫 symbol에서 예외가 발생해도 나머지 symbol이 처리되는지
- consumer와 dirty drainer가 동시에 같은 symbol을 처리할 때 실제 매칭은 한 번만 실행되는지
- `QUOTA_UNAVAILABLE`일 때 재등록 hot loop가 발생하지 않는지
- dirty drain이 느려도 WebSocket 구독 갱신 스케줄러가 지연되지 않는지
- `markDirty()` 실패(Redis 장애) 후 30초 안전망으로 복구되는지

## 이번 범위에서 제외

- **Stream의 ACK·PEL·재시도·DLQ 정책** — 정책 자체는 유지한다. 다만 공통 실행기 추출, 안정화 루프 제거, `ORDER_BOOK_UPDATED` 건너뛰기를 위해 `MatchingEngineStreamConsumer`의 **클래스 구조는 수정한다.**
- **화면 전파 경로** — `publishOrderBook` → Pub/Sub → STOMP는 변경 없음.
- **주문 단위 격리** — 반복 실패하는 주문을 매칭 대상에서 빼는 것은 `Order` 스키마 변경이 필요해 별도로 다룬다.
- **스냅샷 유동성 오차** — 우리는 거래소가 아니라 외부 호가 스냅샷을 읽어 체결시키는 시뮬레이터다. 스냅샷에 10주라고 나왔지만 실제로는 5주뿐이었던 경우는 어떤 루프로도 막지 못한다. 줄이는 방법은 스냅샷을 신선하게 만드는 것뿐이고 WebSocket 전환이 이미 그 일을 했다(오차 구간 1/7). 신선도 검증(`getOrderBookNoOlderThan`)은 그대로 유지한다.

## 확인이 필요한 가정

- 초당 7회라는 푸시 빈도는 **미국 정규장에서 AAPL·TSLA 두 종목을 측정한 값**이다. 유동성이 낮은 종목은 훨씬 적고 변동성이 큰 구간에서는 더 많을 수 있다. drain 주기와 batch 크기는 운영 데이터로 조정해야 한다.
- 미체결 주문 종목 수는 서비스 사용량에 따라 달라진다. 이 값이 커지면 drain 한 사이클이 길어져 `fixedDelay` 가정이 깨지므로, 실행 시간 메트릭을 반드시 함께 본다.
- `SPOP`이 여러 인스턴스에 균등 분배하지는 않는다. 한 인스턴스가 대부분을 가져갈 수 있는데, 종목 락이 중복 처리를 막고 처리량은 확보되므로 문제가 되지 않는다고 보았다. 불균형이 관측되면 배정 규칙을 검토한다.
- 롤링 배포 시간이 30초를 크게 넘지 않는다고 가정했다. 넘긴다면 4.2의 안전망 주기 조정이 필요하다.
