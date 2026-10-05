# 매칭 엔진 안정성 수정 (심각 3/4/5 + 경미 4건)

## Context

코드 감사 결과, 1/2/6번(계좌 생성, 충전·초기화·리더보드, totalAssetValue 갱신)은 아직 구현 전 기능이라 이번 범위에서 제외한다. 대신 이미 동작 중인 매칭 엔진(`MatchingEngineTransactionService`, `MatchingEngineStreamConsumer`, `SymbolOrderLockService`, `OrderRepository`, `OrderTradingService`)에 있는 실제 결함을 고친다.

- **심각 3**: `matchOrder()`가 `Order`/`Account`/`Holding` row를 비관적 락으로 잡은 트랜잭션 안에서 `OrderBookService`/`DailyPriceRangeService`를 호출하는데, 이 둘은 캐시 미스 시 Toss API를 동기 호출한다. `SymbolOrderLockService`의 심볼 락 TTL이 고정 10초라, 외부 호출이 길어지면 락이 만료되어 같은 심볼을 다른 컨슈머가 동시에 처리할 수 있다.
- **심각 4**: 매칭은 오직 "새 주문이 스트림에 들어왔을 때"만 트리거된다. 외부 시세가 움직여 대기 중인 지정가 주문의 조건을 충족시켜도, 새로운 반대편 주문이 들어오지 않는 한 그 주문은 영원히 재검토되지 않는다.
- **심각 5**: `matchBuyOrder`의 외부 체결 분기에 `matchSellOrder`에는 있는 `executionQuantity <= 0 → break` 가드가 없다.
- **경미**: (a) `findMatchableSellOrders`/`findMatchableBuyOrders`가 후보 전체에 락을 걸지만 `.findFirst()`로 1건만 사용, (b) 멱등 재요청 응답이 항상 `executions: []`, (c) 커미션/세금이 매칭 엔진에 하드코딩된 0, (d) `canceledAt` 컬럼은 있지만 취소 기능이 없음.

## 1. 외부 API 호출을 트랜잭션 밖으로 분리 (심각 3)

핵심 함정: `MatchingEngineTransactionService` 안에서 `@Transactional` 메서드를 같은 클래스 내부 호출(self-invocation)로 쪼개면 Spring 프록시를 우회해서 트랜잭션이 아예 적용되지 않는다. 그래서 분리는 **호출자인 `MatchingEngineStreamConsumer` 쪽에서** 한다.

- `Service/MatchingEngineStreamConsumer.java`의 `processRecord(...)`에서, 기존에는 `matchingEngineTransactionService.matchOrder(orderId)`만 호출했는데, 여기서 이미 스트림 레코드로부터 `symbol`을 알고 있으므로:
  ```java
  OrderBookResponse orderBook = orderBookService.getOrderBook(symbol);
  DailyPriceRangeResponse dailyPriceRange = dailyPriceRangeService.getDailyPriceRange(symbol);
  matchingEngineTransactionService.matchOrder(orderId, orderBook, dailyPriceRange);
  ```
  `OrderBookService`, `DailyPriceRangeService`를 생성자 주입으로 추가한다. `recoverPendingOrders()`도 같은 `processRecord`를 재사용하므로 자동으로 함께 고쳐진다.

- `Service/MatchingEngineTransactionService.java`: `matchOrder(Long orderId)` 시그니처를 `matchOrder(Long orderId, OrderBookResponse orderBook, DailyPriceRangeResponse dailyPriceRange)`로 바꾸고, 메서드 내부에서 하던 `orderBookService.getOrderBook(...)` / `getDailyPriceRangeIfMarketOrder(...)` 호출을 제거하고 파라미터를 그대로 사용한다. `getDailyPriceRangeIfMarketOrder` 헬퍼는 삭제한다 — 주문 타입과 무관하게 항상 미리 가져온 값을 쓴다.

  주의할 점: 지정가 주문도 첫 체결 시 `dailyPriceRangeService.updateWithExecutionPrice(symbol, null, price)`를 타면 내부적으로 `getDailyPriceRange(symbol)`를 호출해 캐시 미스 시 Toss를 호출하는 경로가 이미 있었다(시장가 주문만 프리페치하던 기존 코드의 사각지대). 이제 항상 프리페치된 `dailyPriceRange`를 넘기므로 이 경로 자체가 트랜잭션 안에서 발동할 일이 없어진다.

- `Service/OrderBookService.java`, `Service/DailyPriceRangeService.java`는 수정하지 않는다 — 호출 위치만 옮긴다.

## 2. 대기 주문 재매칭 스케줄러 신규 추가 (심각 4)

기존 소비자/재시도/DLQ 로직을 전혀 건드리지 않고, 같은 Redis Stream(`orders:submitted`)에 이벤트를 추가로 발행하는 새 스케줄러만 추가한다 (순수 확장, OCP 준수).

- 신규 `Repository/OrderRepository.java` 메서드 (락 없는 단순 조회):
  ```java
  @Query("select distinct o.stock.id from Order o where o.status in :statuses")
  List<Long> findDistinctStockIdsByStatusIn(@Param("statuses") Collection<OrderStatus> statuses);

  @Query("select o.id from Order o where o.stock.id = :stockId and o.status in :statuses")
  List<Long> findIdsByStockIdAndStatusIn(@Param("stockId") Long stockId, @Param("statuses") Collection<OrderStatus> statuses);
  ```

- 신규 `Service/PendingOrderRematchScheduler.java`:
  ```java
  @Scheduled(fixedDelayString = "${matching-engine.rematch.fixed-delay-ms:2000}")
  public void rematchPendingOrders() {
      for (Long stockId : orderRepository.findDistinctStockIdsByStatusIn(MATCHABLE_STATUSES)) {
          stockRepository.findById(stockId).ifPresent(stock ->
              orderRepository.findIdsByStockIdAndStatusIn(stockId, MATCHABLE_STATUSES)
                  .forEach(orderId -> orderSubmittedStreamPublisher.publish(new OrderSubmittedEvent(orderId, stock.getSymbol())))
          );
      }
  }
  ```

**판단 사항 (알려드릴 부분)**: 재매칭 대상을 "웹소켓으로 실시간 구독 중인 종목"이 아니라 **"DB에 미체결 주문이 있는 모든 종목"** 기준으로 잡았습니다. 아무도 화면에서 보고 있지 않은 종목이라도 대기 주문은 시세 조건이 맞으면 체결돼야 하는 게 맞다고 판단했습니다. 대신 아무도 안 보는 종목까지 Toss 폴링 대상이 되어 API 호출이 늘어나는 트레이드오프가 있습니다. 매 틱마다 미체결 주문을 전부 재발행하는 방식이라 다소 낭비가 있지만, `matchOrder`는 이미 체결 불가 상태면 즉시 반환하는 가드가 있어 안전하고, 스트림은 `MAXLEN`으로 트리밍되므로 지금 범위에서는 이 정도로 충분하다고 봤습니다.

> 후속 발견: 이 방식이 실제로 스트림을 오염시킬 수 있다는 문제가 다음 라운드(03번 계획)에서 확인되어 쿨다운 로직으로 보완했다.

## 3. `matchBuyOrder` 외부 체결 수량 가드 추가 (심각 5)

`Service/MatchingEngineTransactionService.java`의 `matchBuyOrder` 외부 체결 분기에 `matchSellOrder`와 동일한 가드를 추가:
```java
long availableExternalQuantity = externalAsk.volume() - consumedExternalAskQuantity;
Long executionQuantity = Math.min(buyOrder.getRemainingQuantity(), availableExternalQuantity);
if (executionQuantity <= 0) {
    break;
}
```

## 4. 심볼 락 TTL 설정화 (5번과 묶어서 처리하는 방어책)

`Service/SymbolOrderLockService.java`의 `LOCK_TTL = Duration.ofSeconds(10)` 상수를 `@Value("${matching-engine.lock.ttl-ms:15000}")` 필드로 변경. 1번 수정으로 트랜잭션 안에서 외부 호출이 사라지므로 락 보유 시간이 크게 줄지만, 방어적으로 여유(15초)를 두고 설정 가능하게 만든다.

## 5. 경미 (a) — 매칭 후보 조회 시 1건만 락

`Repository/OrderRepository.java`의 `findMatchableSellOrders`/`findMatchableBuyOrders`에 `Pageable` 파라미터를 추가하고, `Service/MatchingEngineTransactionService.java`에서 `PageRequest.of(0, 1)`을 넘겨 실제로 필요한 1건에만 비관적 락이 걸리도록 한다.

## 6. 경미 (b) — 멱등 재요청 응답에 실제 체결 내역 포함

`Service/OrderTradingService.java`의 `toAcceptedResponse(Order order)`가 항상 `List.of()`를 반환하던 것을, `ExecutionRepository.findByOrderIdOrderByIdAsc(order.getId())` 결과를 `OrderExecutionResponse`로 매핑해서 채우도록 수정. `ExecutionRepository`를 생성자 주입으로 추가.

## 7. 경미 (c) — 커미션/세금 계산을 교체 가능한 컴포넌트로 분리

수수료율 자체는 아직 정해진 게 없어서 임의로 퍼센트를 만들어 넣지 않는다. 대신 매칭 엔진에 박혀 있던 `ZERO_MONEY` 하드코딩을 인터페이스 뒤로 옮겨서, 나중에 실제 수수료 모델이 정해지면 매칭 엔진 코드를 건드리지 않고 구현체만 교체하면 되게 한다 (OCP).

- 신규 `Service/CommissionCalculator.java` 인터페이스: `calculateCommission(BigDecimal price, Long quantity)`, `calculateTax(BigDecimal price, Long quantity)`
- 신규 `Service/ZeroCommissionCalculator.java` (`@Component`, 현재와 동일하게 항상 0 반환)
- `Service/MatchingEngineTransactionService.java`의 `createExecution(...)`에서 `ZERO_MONEY` 대신 주입받은 `CommissionCalculator`를 호출

## 8. 경미 (d) — 주문 취소 기능 추가

- `Entity/Order.java`에 `cancel()` 메서드 추가 (기존 `fill()`과 같은 패턴):
  ```java
  private static final List<OrderStatus> CANCELABLE_STATUSES = List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);

  public void cancel() {
      if (!CANCELABLE_STATUSES.contains(this.status)) {
          throw new IllegalStateException("이미 종료된 주문은 취소할 수 없습니다.");
      }
      this.status = OrderStatus.CANCELED;
      this.canceledAt = LocalDateTime.now();
  }
  ```
- `Service/OrderTradingService.java`에 `cancelOrder(User user, Long orderId)` 추가: `orderRepository.findByIdForUpdate(orderId)`로 락 획득 → 소유자 검증 → `order.cancel()` → `toAcceptedResponse(order)` 반환. 매칭 엔진(`matchOrder`)도 같은 `Order` row에 비관적 락을 거는 `findByIdForUpdate`를 쓰므로, 취소와 매칭은 같은 락으로 자연스럽게 직렬화된다 — 매칭이 먼저 락을 잡으면 취소는 매칭이 끝난 뒤의 최신 상태를 보고, 취소가 먼저 락을 잡으면 매칭 쪽 `MATCHABLE_STATUSES` 가드에 걸려 조용히 반환된다. 매칭 엔진 코드 변경 불필요.
- `Controller/OrderTradingController.java`에 취소 엔드포인트 추가: `DELETE /api/v1/orders/{orderId}`.

## 설정 추가 (`application.yaml`, 선택)

```yaml
matching-engine:
  rematch:
    fixed-delay-ms: ${MATCHING_ENGINE_REMATCH_FIXED_DELAY_MS:2000}
  lock:
    ttl-ms: ${MATCHING_ENGINE_LOCK_TTL_MS:15000}
```
(둘 다 코드 쪽 `@Value` 기본값이 있으므로 `application.yaml`에 안 넣어도 동작은 하지만, 다른 폴링 설정들과 일관성을 위해 명시하는 걸 추천합니다.)

## 구현 상태

완료 (구현 및 점검 완료). `Order.cancel()`이 `IllegalStateException`을 던지는 부분은 `GlobalExceptionHandler`가 처리하지 못한다는 문제가 후속 점검에서 드러나 03번 계획에서 `IllegalArgumentException`으로 재조정했다.
