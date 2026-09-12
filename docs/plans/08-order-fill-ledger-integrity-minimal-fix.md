# 체결 원장 무결성 최소 수정 — 부분 체결 롤백 제거와 체결 불가 주문 종료

## Context

계좌 원장(현금·보유·체결) 반영 경로를 감사한 결과, **체결이 정상적으로 끝나는 경우의 원장 반영 자체는 정합적**이다.

- `matchOrder()`는 `Order` 행을 `PESSIMISTIC_WRITE`로 잡고 `Account`·`Holding`도 모두 비관적 락으로 잡는다. 현금, 보유 수량·평균단가, 주문 체결 수량, `Execution`, `CashTransaction`이 한 트랜잭션에서 함께 커밋된다. 부분 커밋으로 원장이 어긋나는 경로는 없다.
- `createCashTransaction()`은 항상 `debitCash`/`creditCash` 이후에 호출되므로 `balanceAfter`가 정확하다. 한 트랜잭션에서 여러 번 체결해도 각 원장 행의 잔고 스냅샷이 순차적으로 맞다.
- 스트림 재전달 시 `matchOrder()` 진입부의 상태 가드와 행 락이 이중 차감을 막는다.
- `orders` 테이블의 `@Check`가 `filled_quantity + remaining_quantity = order_quantity`를 DB 레벨에서 강제한다.

문제는 **검증에 실패하는 경로**다. 아래 세 건이 이번 범위다.

### 결함 1 — 실패 시 이미 성사된 체결까지 롤백되어 주문이 영구 정지한다

`matchBuyOrder()`/`matchSellOrder()`의 while 루프는 **한 트랜잭션 안에서** 여러 번 체결한다. 루프 중간에 잔고 부족(`MatchingEngineTransactionService.java:236`, `:262`)이나 보유 부족(`:231`)으로 `IllegalArgumentException`이 나면, `matchSymbol()`의 `transactionTemplate`(`:64`)이 트랜잭션 전체를 롤백하므로 **앞에서 이미 성사된 체결이 전부 사라진다.**

이건 비결정적 오류가 아니다. 잔고 100만원으로 매수 주문을 냈는데 호가 1단계 60만원 + 2단계 60만원이면, 매 사이클마다 "60만원 체결 → 두 번째에서 잔고 부족 예외 → 전량 롤백"이 반복된다. `PendingOrderRematchScheduler`가 30초마다 재발행해도 결과가 같아서, **체결 가능한 60만원어치조차 영원히 체결되지 않는다.** `:65`의 catch는 "다음 주문으로 넘어간다"만 보장할 뿐 이 주문 자체는 구제하지 못한다.

`current-implementation-overview.md`의 "해당 주문만 오류 log를 남기고 다음 주문을 계속 처리한다"는 서술은 이 롤백 범위를 담고 있지 않다.

### 결함 2 — 내부 체결 수량이 매도자 보유 수량으로 제한되지 않는다

```java
// :181 — 내부 매칭. 보유 수량 캡이 없다
Long executionQuantity = Math.min(sellOrder.getRemainingQuantity(), internalBuyOrder.getRemainingQuantity());

// :190-192 — 외부 호가 매칭. 보유 수량 캡이 있다
Long executionQuantity = Math.min(sellOrder.getRemainingQuantity(),
    Math.min(availableExternalQuantity, sellerHolding.getQuantity()));
```

5주 보유 상태에서 10주 매도 주문을 내고 내부에 10주 매수 주문이 있으면 `executeInternalTrade()`의 `:231`에서 **반드시** 예외가 나고, 결함 1과 결합해 전량 롤백된다. 체결 가능한 5주도 못 팔고 무한 재시도한다. 매수 방향(`matchBuyOrder` → 내부 매도 상대방)도 대칭으로 같은 문제가 있다.

### 결함 3 — 체결이 구조적으로 불가능한 주문이 종료되지 않는다

`OrderStatus.REJECTED`는 enum에만 있고 코드 어디에서도 사용되지 않는다. 보유 종목 row 자체가 없는 매도 주문(`:164`)은 예외를 던지고 `PENDING`으로 남아 30초마다 영원히 재시도된다. 사용자에게는 "접수됨"으로 보이고 실패 사유는 서버 로그에만 남는다.

### 이번 범위의 전제 — 예약(가용잔고)은 도입하지 않는다

현업 증권사는 주문 접수 시 증거금을 구속해서 예수금(원장잔고)과 주문가능금액(가용잔고)을 분리한다. 이 프로젝트에 옮기면 `Account.reservedCash`, `Holding.reservedQuantity` 추가와 취소·부분체결·거절 전 경로의 해제 보장이 필요하고, 해제 누락은 곧 자산 영구 동결이다.

이번 계획은 **"체결 시점에만 검증한다"는 현재 방식을 유지**하되 그 뒤를 채운다. 오버커밋(잔고 100만원으로 100만원짜리 매수 주문 10건 접수)은 그대로 허용된다. 데이터를 깨뜨리지는 않고, 실제로 지금 서비스를 막고 있는 건 결함 1·2이기 때문이다. 예약 도입은 대사(reconciliation) 장치를 먼저 깐 뒤 별도 계획으로 다룬다.

## 1. 설계 판단 — 트랜잭션 분리가 아니라 사전 수량 캡으로 간다

결함 1의 교과서적 해법은 **체결 1건 = 트랜잭션 1건**으로 쪼개는 것이다(현업 백오피스가 그렇게 한다). 하지만 이 코드베이스에서는 비용이 크다.

- 루프 상태(`buyOrder` 엔티티, `externalAskIndex`, `consumedExternalAskQuantity`, `updatedDailyPriceRange`)가 영속성 컨텍스트 안에 있어서, 트랜잭션을 쪼개면 매 스텝마다 엔티티를 다시 로드하고 커서를 트랜잭션 밖 값으로 들고 다녀야 한다.
- 롤백된 스텝이 커서를 오염시키지 않도록 커서 갱신을 결과값으로 되돌려 받아야 한다.
- `@Transactional` 메서드를 같은 클래스 내부 호출로 쪼개면 Spring 프록시를 우회한다 — `02-matching-engine-stability-fixes.md`가 이미 지적한 함정이다.

대신 **예외를 던지지 않게 만든다.** 잔고·보유 제약을 체결을 시도한 뒤 예외로 발견하는 대신, **체결 수량을 정하기 전에 제약으로 캡을 씌운다.** 감당 가능한 만큼만 체결하고, 0이면 루프를 빠져나온다. 예외가 없으면 롤백도 없고, 트랜잭션 구조를 전혀 건드리지 않고도 부분 체결이 살아남는다.

이 변경 후 `executeInternalTrade`/`executeExternalBuy`에 남는 예외는 "캡을 씌웠는데도 제약을 위반했다" = 데이터 불일치 신호가 된다. 그때는 전량 롤백이 오히려 올바른 대응이므로 방어적 불변식으로 그대로 둔다.

**단조성 근거 (루프를 `break`해도 되는 이유)**: 매수 루프에서 후보 체결가는 반복마다 **비감소**한다. 내부 매도 후보는 `orderPrice asc`로 정렬되고 소진된 후보는 제외되며, 외부 ask는 오름차순 정렬에 인덱스만 전진하고, `shouldUseInternalSell()`은 둘 중 낮은 쪽을 고르기 때문이다. 따라서 **가장 싼 후보에서 1주도 못 사면 이후 어떤 후보도 못 산다** — `break`가 안전하다. 매도 루프에서는 후보 체결가가 비증가이고 캡이 보유 수량(단조 감소)이므로 보유가 0이면 마찬가지로 `break`가 안전하다.

## 2. `executeInternalTrade()`를 "실제 체결 수량 반환"으로 변경

상대방 계좌의 제약은 상대방 계좌·보유 row를 잡아야 알 수 있으므로, 캡 계산을 이 메서드 안으로 넣고 실제 체결한 수량을 돌려준다. 매수/매도 양쪽 호출자가 같은 메서드를 쓰므로 한 번의 수정으로 두 방향이 모두 고쳐진다.

```java
/**
 * 요청 수량을 매수자 잔고와 매도자 보유 수량으로 캡을 씌운 뒤 체결한다.
 *
 * <p>제약 위반을 예외로 알리지 않고 <b>실제 체결한 수량</b>을 반환한다. 예외를 던지면
 * 같은 트랜잭션에서 이미 성사된 체결까지 롤백되어, 체결 가능한 물량마저 영영 체결되지 않는다.
 * 0을 반환하면 이 상대방과는 체결할 수 없다는 뜻이다.
 */
private long executeInternalTrade(
    Order buyOrder,
    Order sellOrder,
    BigDecimal executionPrice,
    long requestedQuantity
) {
    Account buyerAccount = accountRepository.findByIdForUpdate(buyOrder.getAccount().getId())
        .orElseThrow(() -> new IllegalArgumentException("매수 계좌를 찾을 수 없습니다."));
    Account sellerAccount = accountRepository.findByIdForUpdate(sellOrder.getAccount().getId())
        .orElseThrow(() -> new IllegalArgumentException("매도 계좌를 찾을 수 없습니다."));

    Holding sellerHolding = holdingRepository.findByAccountIdAndStockId(
        sellerAccount.getId(),
        sellOrder.getStock().getId()
    ).orElse(null);
    if (sellerHolding == null) {
        return 0L;
    }

    long executionQuantity = Math.min(
        requestedQuantity,
        Math.min(sellerHolding.getQuantity(), affordableQuantity(buyerAccount, executionPrice))
    );
    if (executionQuantity <= 0) {
        return 0L;
    }

    Holding buyerHolding = holdingRepository.findByAccountIdAndStockId(
        buyerAccount.getId(),
        buyOrder.getStock().getId()
    ).orElseGet(() -> holdingRepository.save(Holding.create(buyerAccount, buyOrder.getStock())));

    BigDecimal executionAmount = money(executionPrice.multiply(BigDecimal.valueOf(executionQuantity)));
    BigDecimal costBasis = sellerHolding.sell(executionQuantity);
    buyerAccount.debitCash(executionAmount);
    sellerAccount.creditCash(executionAmount);
    sellerAccount.addRealizedProfit(executionAmount.subtract(costBasis));
    buyerHolding.buy(executionQuantity, executionPrice);
    buyOrder.fill(executionQuantity);
    sellOrder.fill(executionQuantity);

    Execution buyExecution = createExecution(buyOrder, executionPrice, executionQuantity);
    Execution sellExecution = createExecution(sellOrder, executionPrice, executionQuantity);
    createCashTransaction(buyerAccount, buyOrder, buyExecution, CashTransactionType.BUY, executionAmount.negate());
    createCashTransaction(sellerAccount, sellOrder, sellExecution, CashTransactionType.SELL, executionAmount);
    return executionQuantity;
}
```

바뀐 점:

- `sellerHolding` 조회의 `orElseThrow`가 `orElse(null)` + `return 0L`이 되었다.
- `보유 수량이 부족합니다` / `주문 가능 금액이 부족합니다` 예외 두 건(`:231`, `:236`)이 캡으로 대체되었다. `Holding.sell()`이 자체적으로 수량 부족을 던지므로 불변식 방어는 유지된다.
- `buyerHolding` 생성이 캡 계산 **뒤로** 이동했다. 체결이 0이면 빈 보유 row를 만들지 않는다.
- 반환 타입이 `void` → `long`.

잔고를 수량으로 환산하는 헬퍼를 추가한다.

```java
/** 이 가격에 몇 주까지 살 수 있는지. 잔고가 음수이거나 가격이 유효하지 않으면 0. */
private long affordableQuantity(Account account, BigDecimal executionPrice) {
    if (executionPrice == null || executionPrice.signum() <= 0) {
        return 0L;
    }
    return account.getCashBalance()
        .divide(executionPrice, 0, RoundingMode.DOWN)
        .longValue();
}
```

`cash_balance`는 scale 2이고 `RoundingMode.DOWN`으로 내림하므로 `money(price * quantity) <= cashBalance`가 보장된다 — `price * quantity`가 이미 잔고 이하이고, 잔고가 소수점 2자리이므로 scale 2로 `HALF_UP` 반올림해도 잔고를 넘지 못한다.

## 3. `matchBuyOrder()` — 잔고 캡, 체결 불가 상대방 건너뛰기, 잔고 부족 거절

내부 체결 분기에 잔고 캡을 넣고, 외부 체결 분기에도 같은 캡을 추가한다.

```java
Set<Long> excludedSellOrderIds = new HashSet<>();
excludedSellOrderIds.add(buyOrder.getId());

while (buyOrder.getRemainingQuantity() > 0) {
    Order internalSellOrder = bestInternalSellOrder(buyOrder, excludedSellOrderIds);
    OrderBookLevel externalAsk = externalLevel(externalAsks, externalAskIndex);

    if (internalSellOrder == null && externalAsk == null) {
        // 체결 상대가 아예 없다. 계좌 문제가 아니라 유동성 문제이므로 거절하지 않고 대기시킨다.
        break;
    }

    if (shouldUseInternalSell(internalSellOrder, externalAsk)) {
        BigDecimal executionPrice = internalSellOrder.getOrderPrice();
        long affordableQuantity = affordableQuantity(buyerAccount, executionPrice);
        if (affordableQuantity <= 0) {
            // 가장 싼 후보를 1주도 못 산다. 이후 후보는 가격이 비감소라 마찬가지다(1절 단조성).
            // 체결 상대는 눈앞에 있는데 잔고가 모자란 것이므로 거절로 종료한다(6절).
            buyOrder.reject("주문 가능 금액이 부족합니다.");
            return;
        }

        long requestedQuantity = Math.min(
            Math.min(buyOrder.getRemainingQuantity(), internalSellOrder.getRemainingQuantity()),
            affordableQuantity
        );
        long executedQuantity = executeInternalTrade(buyOrder, internalSellOrder, executionPrice, requestedQuantity);
        if (executedQuantity <= 0) {
            // 상대 매도자의 보유가 비어 있다. 같은 후보를 다시 뽑으면 무한 루프이므로 제외하고 계속한다.
            excludedSellOrderIds.add(internalSellOrder.getId());
            continue;
        }

        updatedDailyPriceRange = dailyPriceRangeService.updateWithExecutionPrice(
            buyOrder.getStock().getSymbol(), updatedDailyPriceRange, executionPrice);
    } else {
        long affordableQuantity = affordableQuantity(buyerAccount, externalAsk.price());
        if (affordableQuantity <= 0) {
            buyOrder.reject("주문 가능 금액이 부족합니다.");
            return;
        }

        long availableExternalQuantity = externalAsk.volume() - consumedExternalAskQuantity;
        long executionQuantity = Math.min(
            Math.min(buyOrder.getRemainingQuantity(), availableExternalQuantity),
            affordableQuantity
        );
        if (executionQuantity <= 0) {
            break;
        }

        executeExternalBuy(buyOrder, buyerAccount, buyerHolding, externalAsk.price(), executionQuantity);
        // ... 이하 기존과 동일
    }
}
```

**`return`으로 빠지는 경로는 `applyMarketOrderRemainingPrice()`를 건너뛴다.** 의도한 동작이다 — 이 헬퍼는 시장가 주문의 **잔여 물량이 계속 대기할 때** 쓸 대기 가격을 심는 용도인데, 거절로 종료된 주문에는 대기할 잔여가 없다.

**제외 목록의 비용**: 건너뛴 후보마다 `bestInternalSellOrder()` 쿼리가 한 번 더 나간다. 반복 횟수는 그 종목의 매칭 가능한 반대편 주문 수로 상한이 잡히고, 6절에서 보유 0인 매도 주문이 자기 스윕에서 종료되므로 다음 사이클부터는 후보에서 아예 빠진다. 정상 상태에서 이 경로는 거의 타지 않는다.

**`consumedExternalAskQuantity`가 잔고 캡으로 줄었을 때**: 체결 수량이 `availableExternalQuantity`보다 작으면 그 level을 다 채우지 못하므로 인덱스가 전진하지 않는다. 다음 반복에서 같은 level을 다시 보지만 잔고는 그만큼 줄었으므로 `affordableQuantity`가 0이 되어 거절로 종료한다 — 무한 루프는 없다.
## 4. `matchSellOrder()` — 보유 캡, 체결 불가 상대방 건너뛰기, 보유 부족 거절

대칭으로 바꾼다. 보유 수량 검사를 내부/외부 분기보다 **앞으로** 올려서 두 경로가 같은 규칙을 쓰게 한다.

```java
Set<Long> excludedBuyOrderIds = new HashSet<>();
excludedBuyOrderIds.add(sellOrder.getId());

while (sellOrder.getRemainingQuantity() > 0) {
    Order internalBuyOrder = bestInternalBuyOrder(sellOrder, excludedBuyOrderIds);
    OrderBookLevel externalBid = externalLevel(externalBids, externalBidIndex);

    if (internalBuyOrder == null && externalBid == null) {
        // 유동성 문제다. 거절하지 않는다.
        break;
    }

    if (sellerHolding.getQuantity() <= 0) {
        // 체결 상대는 있는데 팔 물량이 없다. 보유 수량은 단조 감소하므로 이후 반복도 마찬가지다.
        sellOrder.reject("보유 수량이 부족합니다.");
        return;
    }

    if (shouldUseInternalBuy(internalBuyOrder, externalBid)) {
        BigDecimal executionPrice = internalBuyOrder.getOrderPrice();
        long requestedQuantity = Math.min(
            Math.min(sellOrder.getRemainingQuantity(), internalBuyOrder.getRemainingQuantity()),
            sellerHolding.getQuantity()
        );
        long executedQuantity = executeInternalTrade(internalBuyOrder, sellOrder, executionPrice, requestedQuantity);
        if (executedQuantity <= 0) {
            // 상대 매수자의 잔고가 부족하다. 제외하고 다음 후보로.
            excludedBuyOrderIds.add(internalBuyOrder.getId());
            continue;
        }

        updatedDailyPriceRange = dailyPriceRangeService.updateWithExecutionPrice(
            sellOrder.getStock().getSymbol(), updatedDailyPriceRange, executionPrice);
    } else {
        // ... 기존과 동일 (이미 sellerHolding.getQuantity() 캡이 있다)
    }
}
```

**"상대 없음"을 "보유 부족"보다 먼저 판정하는 이유**: 유동성이 없어서 체결이 안 된 것과 팔 물량이 없어서 체결이 안 된 것은 다른 사유고, 전자는 거절 대상이 아니다. 순서를 바꾸면 호가가 잠깐 빈 종목의 정상 매도 주문까지 거절된다.

`sellerHolding`은 영속 엔티티라 루프 안에서 체결될 때마다 `getQuantity()`가 줄어든 값을 그대로 읽는다.
## 5. `OrderRepository` — 후보 제외 목록 파라미터

`o.id <> :incomingOrderId`를 `o.id not in :excludedOrderIds`로 바꾼다. 호출자가 항상 자기 주문 id를 넣고 시작하므로 컬렉션이 비는 경우는 없다 — Hibernate의 빈 `in` 절 처리를 걱정하지 않아도 된다.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("""
    select o from Order o
    where o.id not in :excludedOrderIds
      and o.stock.id = :stockId
      and o.orderSide = com.papertrade.paper_trading.Enum.OrderSide.SELL
      and o.status in :statuses
      and o.remainingQuantity > 0
      and o.orderPrice is not null
      and (:maxPrice is null or o.orderPrice <= :maxPrice)
    order by o.orderPrice asc, o.submittedAt asc, o.remainingQuantity desc
    """)
List<Order> findMatchableSellOrders(
    @Param("excludedOrderIds") Collection<Long> excludedOrderIds,
    @Param("stockId") Long stockId,
    @Param("maxPrice") BigDecimal maxPrice,
    @Param("statuses") Collection<OrderStatus> statuses,
    Pageable pageable
);
```

`findMatchableBuyOrders`도 동일하게 바꾼다. `MatchingEngineTransactionService`의 `bestInternalSellOrder`/`bestInternalBuyOrder`는 제외 목록을 받아 그대로 넘긴다.

```java
private Order bestInternalSellOrder(Order buyOrder, Collection<Long> excludedOrderIds) {
    return orderRepository.findMatchableSellOrders(
        excludedOrderIds,
        buyOrder.getStock().getId(),
        limitPrice(buyOrder),
        MATCHABLE_STATUSES,
        FIRST_MATCHABLE_ORDER
    ).stream().findFirst().orElse(null);
}
```

## 6. 체결 불가 주문의 `REJECTED` 전이

`Entity/Order.java`에 거절 전이를 추가한다.

```java
@Column(name = "rejected_at")
private LocalDateTime rejectedAt;

@Column(name = "reject_reason", length = 255)
private String rejectReason;

public void reject(String reason) {
    if (!CANCELABLE_STATUSES.contains(this.status)) {
        throw new IllegalArgumentException("이미 종료된 주문은 거절할 수 없습니다.");
    }
    this.rejectReason = reason;
    if (this.filledQuantity > 0) {
        // 체결 이력이 있는 주문은 "거절"이 아니라 잔량 취소로 종료한다.
        // REJECTED는 접수 자체가 무효였다는 뜻이라 부분 체결과 같이 쓸 수 없다.
        this.status = OrderStatus.CANCELED;
        this.canceledAt = LocalDateTime.now();
        return;
    }
    this.status = OrderStatus.REJECTED;
    this.rejectedAt = LocalDateTime.now();
}
```

`matchSellOrder()` 진입부의 `orElseThrow`(`:164`)도 거절 전이로 바꾼다.

```java
Holding sellerHolding = holdingRepository.findByAccountIdAndStockId(
    sellerAccount.getId(),
    sellOrder.getStock().getId()
).orElse(null);

if (sellerHolding == null || sellerHolding.getQuantity() <= 0) {
    sellOrder.reject("보유 수량이 부족합니다.");
    return;
}
```

예외를 던지지 않으므로 이 전이는 해당 주문의 트랜잭션에서 그대로 커밋된다. 별도 트랜잭션이 필요 없다.

`OrderResponse`에 `rejectReason`을 추가해 사용자가 사유를 볼 수 있게 한다. `toAcceptedResponse()`가 유일한 생성 지점이라 필드 추가만 하면 된다.

### 거절 판정 규칙

**매수 잔고 부족과 매도 보유 부족을 모두 거절한다.** 거절 지점은 네 곳이다.

| 위치 | 조건 | 사유 |
| --- | --- | --- |
| `matchSellOrder()` 진입부 | 보유 row 없음 또는 보유 0 | `보유 수량이 부족합니다.` |
| `matchSellOrder()` 루프 | 체결 상대가 있는데 보유가 0으로 소진됨 | `보유 수량이 부족합니다.` |
| `matchBuyOrder()` 내부 분기 | 체결 상대가 있는데 1주도 살 잔고가 없음 | `주문 가능 금액이 부족합니다.` |
| `matchBuyOrder()` 외부 분기 | 외부 ask가 있는데 1주도 살 잔고가 없음 | `주문 가능 금액이 부족합니다.` |

**거절하지 않는 경우**: 체결 상대(내부 주문·외부 호가) 자체가 없어서 체결되지 않은 주문. 이건 계좌 문제가 아니라 유동성 문제이므로 `PENDING`으로 대기시킨다. 이 구분 때문에 루프 안에서 "상대 없음"을 "잔고·보유 부족"보다 먼저 판정해야 한다(3·4절).

**받아들이는 결과**: 투자금 충전(계획 01) 이후 자동 재체결은 없다. 잔고가 부족했던 주문은 이미 종료되어 있으므로 사용자가 다시 주문해야 한다. 접수 시점 검증이 없는 현재 구조에서는 "체결을 시도한 시점에 판정하고 끝낸다"가 일관된 규칙이고, 영원히 재시도되는 주문을 남기지 않는 쪽을 택했다.

**부수 효과**: 3·4절의 제외 목록 경로가 정상 상태에서는 거의 비게 된다. 보유 0인 매도 주문과 잔고 0인 매수 주문이 각자의 스윕에서 종료되어 다음 사이클부터 매칭 후보에서 빠지기 때문이다. 제외 목록은 같은 스윕 **안에서** 무한 루프를 막는 안전장치로 남는다.

**스키마**: `rejected_at`, `reject_reason` 두 컬럼이 추가된다. `ddl-auto` 기본값이 `none`이라 Compose 밖 환경에서는 DDL을 별도로 적용해야 한다. migration 도구가 아직 없다는 기존 제약 그대로다.
## 7. 남는 예외를 방어적 불변식으로 격하

`executeExternalBuy()`의 잔고 검사(`:262`)는 3절에서 호출자가 이미 캡을 씌우므로 도달할 수 없게 된다. 제거하지 말고 그대로 두되, 의미가 "정상 경로의 거절"에서 "캡 계산이 깨졌다는 신호"로 바뀐 것을 주석으로 남긴다.

`matchSymbol()`의 catch 블록(`:65`)도 같은 이유로 문구를 바꾼다. 이 예외는 이제 비즈니스 조건 위반이 아니라 데이터 불일치를 뜻한다.

```java
} catch (IllegalArgumentException e) {
    // 체결 수량에 이미 잔고·보유 캡을 씌우므로, 여기까지 오는 예외는 정상적인 "조건 미달"이 아니라
    // 데이터 불일치 신호다. 그래도 루프는 중단하지 않는다 — 뒤에 줄 선 정상 주문까지 막히기 때문이다.
    // DB·외부 API 장애 같은 시스템 문제는 잡지 않고 전파해서 재시도와 DLQ 경로를 그대로 타게 한다.
    log.error(...);
}
```

`current-implementation-overview.md`의 해당 서술(§13.4, §13.5, 그리고 "해당 주문만 오류 log를 남기고" 문단)도 함께 갱신한다.

## 구현 순서

1. `OrderRepository`의 두 쿼리를 제외 목록 파라미터로 변경 (5절). 컴파일이 깨지므로 먼저.
2. `affordableQuantity()` 헬퍼 추가 (2절).
3. `executeInternalTrade()`를 `long` 반환 + 캡 적용으로 변경 (2절).
4. `matchBuyOrder()` / `matchSellOrder()` 루프 수정 (3·4절).
5. `Order.reject()` + 컬럼 추가, `matchSellOrder()` 진입부 전이, `OrderResponse` 필드 추가 (6절).
   - 4·5번은 함께 가야 한다. 3·4절의 루프가 `reject()`를 호출하므로 `Order.reject()`가 먼저 있어야 컴파일된다.
6. 주석·문서 갱신 (7절).

3번까지만 해도 결함 1·2는 해소된다. 거절 정책(4·5번)을 빼고 `break`만 남겨도 결함 1·2는 고쳐지므로, 스키마 변경을 뒤로 미루고 싶으면 여기서 끊을 수 있다.

## 검증 방법

**결함 1 — 부분 체결 생존**

- 잔고 100만원 계정으로 매수 주문을 내고, 외부 호가를 1단계 60만원 / 2단계 60만원으로 구성한다.
- 기대: 60만원어치가 `PARTIALLY_FILLED`로 **커밋**되고, `cash_transactions`에 `BUY` 행이 1건 남고, `balance_after = 400,000`.
- 수정 전에는 `filled_quantity = 0`, `cash_transactions` 0건으로 남는다. 이 차이가 회귀 판정 기준이다.

**결함 2 — 내부 체결 보유 캡**

- 5주 보유 계정이 10주 매도 주문, 상대 계정이 10주 매수 주문.
- 기대: 5주 체결, 매도 주문 `PARTIALLY_FILLED`(잔여 5주), 매수 주문도 `PARTIALLY_FILLED`. 예외 로그 없음.
- 매수 방향에서도 대칭으로 확인한다 (매수 주문이 먼저 스윕되는 경우).

**결함 3 — 거절 전이**

- 보유 row가 없는 종목에 매도 주문 → `REJECTED` + `reject_reason` 확인, 30초 후 재매칭 대상에서 빠졌는지(`findMatchableOrdersBySymbol` 결과) 확인.
- 부분 체결 후 보유가 0이 된 매도 주문 → `CANCELED` + `reject_reason` 채워짐, `filled_quantity` 보존 확인.
- 잔고 0인 계정의 매수 주문 + 체결 가능한 외부 ask 존재 → `REJECTED` + `주문 가능 금액이 부족합니다.` 확인.
- **체결 상대가 아예 없는 매수·매도 주문 → 거절되지 않고 `PENDING` 유지 확인.** 유동성 부족과 잔고·보유 부족을 구분하는지가 이 검증의 요지다. 여기가 깨지면 호가가 잠깐 빈 종목의 정상 주문이 전부 거절된다.

**무한 루프 회귀**

- 내부 매도 후보 3건이 모두 보유 0인 상태에서 매수 주문 스윕이 유한 횟수에 끝나는지. 쿼리 호출 횟수가 후보 수 + 1을 넘지 않아야 한다.

**원장 정합**

- 위 시나리오 후 `SUM(amount) FROM cash_transactions WHERE account_id = ?`가 `cash_balance`의 변화량과 일치하는지. (절대값 일치는 `INITIAL_DEPOSIT` 원장이 없어 아직 불가능하다 — 변화량만 본다.)

## 추가할 자동화 테스트

- 잔고가 첫 호가 level만 감당할 때 부분 체결이 커밋되고 두 번째 level에서 루프가 끝나는지
- 매도자 보유가 요청 수량보다 적을 때 내부 체결이 보유 수량만큼만 이뤄지는지 (매수·매도 양방향)
- 보유 0인 내부 매도 후보가 제외 목록에 들어가고 다음 후보로 넘어가는지
- 내부 후보가 전부 체결 불가일 때 스윕이 유한 횟수에 종료되는지
- 잔고 0인 매수 주문이 예외·롤백 없이 `REJECTED`로 종료되는지
- 체결 상대가 없는 주문이 거절되지 않고 `PENDING`으로 남는지 (매수·매도 양방향)
- 보유 row가 없는 매도 주문이 `REJECTED`로 전이되고 재매칭 대상에서 빠지는지
- 부분 체결된 주문의 `reject()`가 `CANCELED`로 가고 `filled_quantity`가 보존되는지
- 한 트랜잭션에서 여러 번 체결될 때 `cash_transactions.balance_after`가 순차적으로 정확한지

`MatchingEngineTransactionServiceTests`는 현재 Mockito 기반이라 `matchSymbol` 루프 격리만 검증한다. 위 항목 대부분은 실제 JPA 트랜잭션과 행 락이 필요하므로 `@DataJpaTest` 또는 Testcontainers 기반 통합 테스트가 있어야 제대로 검증된다. 통합 테스트 기반이 아직 없다는 점(§17.1)이 이 계획의 가장 큰 검증 공백이다.

## 이번 범위에서 제외

- **가용잔고(예약) 도입** — 오버커밋은 그대로 허용된다. 접수 시점 검증이 없으므로 사용자는 여전히 잔고를 넘는 주문을 낼 수 있고, 그 사실을 접수 응답이 아니라 **체결 시도 시점의 거절**로 뒤늦게 알게 된다. 대사 배치를 먼저 깐 뒤 별도 계획으로 다룬다.
- **자기 계좌 자전거래 차단** — `findMatchableSellOrders`/`findMatchableBuyOrders`가 같은 계좌를 제외하지 않아, 한 사용자가 임의 가격으로 자기 자신과 체결시켜 `realizedProfit`과 평균단가를 조작할 수 있다. 현금 순변동은 0이라 원장 정합성은 깨지지 않지만 리더보드 산출의 근거를 오염시킨다. 주문가격 밴드 검증(당일 가격제한폭)과 함께 다뤄야 의미가 있어서 분리한다.
- **계좌 락 순서로 인한 데드락** — 종목 A의 매칭이 계좌1→계좌2 순으로, 종목 B의 매칭이 계좌2→계좌1 순으로 락을 잡으면 데드락이다. 심볼 락은 종목 단위라 서로 다른 종목의 매칭이 동시에 돈다.

  주의할 점: `executeInternalTrade()` 안에서 계좌 id 오름차순으로 잡는 것만으로는 **해결되지 않는다.** `matchBuyOrder`/`matchSellOrder`가 진입 시점에 이미 자기 주문의 계좌를 먼저 잡아 두기 때문이다. 실제로 고치려면 진입부의 선점 락을 없애고 체결 시점에 두 계좌를 id 순으로 잡도록 재구성해야 하는데, 외부 체결 경로와 `Holding.create()`의 `Account` 참조까지 손봐야 해서 최소 수정 범위를 넘는다. 현재는 PostgreSQL이 데드락을 감지해 한쪽을 abort하고, `IllegalArgumentException`이 아니므로 스트림 재시도 경로로 복구된다 — 시끄럽지만 자가 치유된다.
- **수수료·세금의 현금 반영** (§19.4), **`totalAssetValue` 갱신** (§19.2), **외부 호가 수량의 스윕 내 중복 소비** (§19.3) — 기존 문서에 이미 기록된 갭이고 이번 결함들과 독립적이다.
- **`INITIAL_DEPOSIT` 원장과 대사 배치** — `sum(cash_transactions) = cash_balance` 불변식을 세우려면 계좌 생성 기능이 먼저 필요하다.

## 확인이 필요한 가정

- **체결가 단조성**(1절)이 `break`의 근거다. 향후 `bestInternalSellOrder`의 정렬 기준이나 `shouldUseInternalSell`의 선택 규칙이 바뀌면 이 전제가 깨지고 `break`가 체결 가능한 물량을 버리게 된다. 정렬 기준을 바꾸는 변경에는 이 문서를 함께 확인해야 한다.
- **`affordableQuantity`의 반올림 안전성**은 `cash_balance`가 scale 2, `order_price`가 scale 4라는 현재 컬럼 정의에 의존한다. 통화가 늘어나 scale이 바뀌면 재검토가 필요하다.
- **제외 목록의 크기**가 종목당 매칭 가능 주문 수로 상한이 잡힌다고 가정했다. 미체결 주문이 한 종목에 수천 건 쌓이는 상황이면 스윕 한 사이클이 길어져 심볼 락 TTL(15초)을 위협할 수 있다. 실제 사용량에서 종목당 미체결 주문 수를 관측해 확인해야 한다.
- **부분 체결 후 거절을 `CANCELED`로 보낸다**는 6절의 선택은 `REJECTED`를 "접수 자체가 무효"로 정의한 해석에 근거한다. 리더보드나 통계에서 두 상태를 다르게 집계할 계획이 있다면 먼저 확인이 필요하다.
- **거절 판정이 "체결 상대가 있었는가"에 의존한다.** 외부 호가 스냅샷이 일시적으로 비어 있으면 잔고가 부족한 주문도 거절되지 않고 대기한다. 반대로 스냅샷에 체결 불가능한 수량이 잡혀 있으면(§19.3의 중복 소비 오차) 잔고 부족 주문이 예상보다 이르게 거절될 수 있다. 두 경우 모두 원장을 깨뜨리지는 않지만 거절 시점이 호가 품질에 좌우된다는 점은 알고 있어야 한다.
