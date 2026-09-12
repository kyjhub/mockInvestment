# 원장 시스템 재설계와 가용잔고(예약) 도입

## Context

지금까지의 흐름: 08번에서 체결 실패 경로의 원장 반영을 고쳤고, 그 과정에서 **접수 시점에 잔고를 전혀 검증하지 않는다**는 사실이 드러났다(§13.9). 사용자는 예수금 100만원으로 1억짜리 매수 주문을 낼 수 있고, 그 사실을 접수 응답이 아니라 나중의 거절로 알게 된다.

요구사항은 명확하다 — **예수금을 넘는 주문은 접수되지 않아야 한다.** 그리고 이번 범위는 거기서 멈추지 않고, 기존 테이블을 유지하려고 우회하지 말고 원장 시스템 자체를 제대로 세우는 것까지다.

### 현재 구조는 원장 시스템이 아니다

| 요구 | 현재 | 판정 |
| --- | --- | --- |
| append-only journal | `CashTransaction`에 수정 경로 없음 | 충족 |
| 잔고 = 원장의 재구성 | 개시 입금 원장이 없어 `Σ(amount) ≠ cash_balance` | **불충족** |
| 복식부기 (돈의 출처와 행선지) | 단식. 사용자 쪽 금액만 기록 | **불충족** |
| 한 사건 = 한 거래 | 내부 체결의 매수/매도 두 행이 서로 연결되지 않음 | **불충족** |
| 멱등성 | 고유키·unique 제약 없음 | **불충족** |
| 파생값 재계산 가능 | `realized_profit`은 누적만 하고 재계산 불가 | **불충족** |
| 수수료·세금 원장 | `Execution`에 값만 저장. 현금 차감도 원장 기록도 없음 | **불충족** |
| 정정 수단 | 반대분개 개념 없음 | **불충족** |
| 대사 | 없음. 어긋나도 발견할 방법이 없음 | **불충족** |

가장 심각한 건 마지막 두 줄이다. **지금은 원장이 깨져도 알 수 없고, 알아도 고칠 정해진 방법이 없다.**

### T+2 결제는 도입하지 않는다

현업은 체결 원장과 결제 원장을 분리하고 예수금을 D+0/D+1/D+2로 나눈다. 여기서는 하지 않는다 — **이 서비스에 출금 경로가 없기 때문이다.** T+2 분리가 만드는 유일한 사용자 가시 차이는 "매도 대금으로 재매수는 되지만 출금은 안 된다"인데, 출금 자체가 없으므로 미수·미지급 계정과 결제 batch를 추가해도 아무 차이를 만들지 않는다. 모의투자에서 체결 즉시 결제로 본다.

### 복식부기는 도입한다 (이전 판단 정정)

이전 대화에서 "단일 사용자 원장이라 복식부기는 과하다"고 했는데, 그건 틀렸다. 복식부기의 값어치는 계정이 여럿이라는 데 있지 않고 **`Σ(모든 분개) = 0`이라는 전역 불변식**에 있다. 이 불변식 하나가 "버그로 돈이 생기거나 사라졌다"를 구조적으로 탐지해 준다. 단식부기로는 이 검증이 원리적으로 불가능하다.

비용도 생각보다 작다. 한 사건에 행이 2~5개 생기는 것뿐이고, 그 대가로 실현손익·보유원가가 전부 원장에서 재계산 가능해진다.

## 1. 원장 모델

### 1.1 두 개의 테이블

```text
ledger_transactions          하나의 경제적 사건
  id
  transaction_type           ACCOUNT_OPENING | FUNDING | TRADE | FEE | ADJUSTMENT | RESET
  idempotency_key            unique. 같은 사건을 두 번 기록할 수 없다
  occurred_at
  description
  reversal_of_id             정정 거래면 원본 id (nullable, self FK)

ledger_entries               분개. 한 거래에 2행 이상
  id
  transaction_id             FK, not null
  account_id                 FK, not null
  ledger_account             CASH | SECURITIES | REALIZED_PNL | FEE | TAX | EQUITY_FUNDING
  stock_id                   SECURITIES일 때만 (nullable)
  amount                     차변 +, 대변 −. numeric(19,2)
  quantity                   수량 이동. SECURITIES일 때만 (nullable)
  balance_after              그 (account_id, ledger_account) 기준 누적 잔액
  created_at
```

**불변식 1 (거래 단위 균형)**: 한 `transaction_id`의 `SUM(amount) = 0`
**불변식 2 (전역 균형)**: `SELECT SUM(amount) FROM ledger_entries` = 0

부호 규약은 표준 회계를 따른다. 자산 계정(`CASH`, `SECURITIES`)은 증가가 `+`, 수익·자본 계정(`REALIZED_PNL`, `EQUITY_FUNDING`)은 증가가 `−`다. 비용 계정(`FEE`, `TAX`)은 발생이 `+`다.

`balance_after`는 (계좌, 계정과목)별 누적 잔액이다. 쓰기가 계좌 row lock으로 직렬화되므로 값이 정확하다. 거래내역 화면과 대사 양쪽에 쓴다.

### 1.2 거래 유형별 분개

**계좌 개설 — 모의 투자금 100만원 지급**

```text
CASH(user)            +1,000,000
EQUITY_FUNDING(user)  −1,000,000
                      ─────────── 합계 0
```

**외부 호가 매수 — 60만원 1주**

```text
CASH(user)                  −600,000
SECURITIES(user, 종목)       +600,000   qty +1
                            ─────────── 합계 0
```

현금이 유가증권으로 바뀐 것이라 사용자 안에서 균형이 맞는다. 외부 시장을 상대 계정으로 둘 필요가 없다.

**외부 호가 매도 — 취득원가 60만, 매도금액 70만**

```text
CASH(user)                  +700,000
SECURITIES(user, 종목)       −600,000   qty −1
REALIZED_PNL(user)          −100,000
                            ─────────── 합계 0
```

**실현손익이 원장에서 저절로 나온다.** `Account.realizedProfit`을 따로 누적할 필요가 없어지고, 무엇보다 원장에서 재계산해 검증할 수 있게 된다.

**내부 체결 — 매수자 A, 매도자 B, 70만원 1주, B 취득원가 60만**

```text
CASH(A)                     −700,000
SECURITIES(A, 종목)          +700,000   qty +1
CASH(B)                     +700,000
SECURITIES(B, 종목)          −600,000   qty −1
REALIZED_PNL(B)             −100,000
                            ─────────── 합계 0
```

**한 거래에 두 계좌가 들어간다.** 지금은 `CashTransaction` 두 행이 서로 연결되지 않아 "같은 체결"이라는 사실이 데이터에 없는데, 이 모델에서는 구조로 표현된다.

**수수료·세금** (`CommissionCalculator`가 0이 아닌 값을 낼 때)

```text
CASH(user)    −1,500
FEE(user)     +1,500
```

체결 거래와 같은 `transaction_id`에 묶는다. 계산 결과가 0이면 분개를 만들지 않는다 — 0원 분개는 정보가 없다.

같은 값이 `executions.commission` / `tax`에도 남지만 그쪽은 표시용 사본이고 이 분개가 authoritative다(2.1).

**정정**

원본 분개를 수정하거나 삭제하지 않는다. 부호를 뒤집은 새 거래를 `reversal_of_id`로 연결해 추가한다.

### 1.3 멱등키

`idempotency_key`에 unique 제약을 건다.

| 거래 | 키 |
| --- | --- |
| 계좌 개설 | `OPEN:{accountId}` |
| 투자금 충전 | `FUNDING:{fundingRequestId}` |
| 체결 | `FILL:{tradeId}` — 매칭 엔진이 체결 1건마다 발급하는 UUID |
| 계좌 초기화 | `RESET:{accountResetId}` |

`tradeId`는 `executions`에도 저장한다. 내부 체결은 `Execution` 2건이 같은 `tradeId`와 같은 `ledger_transaction_id`를 갖는다.

## 2. 기존 테이블의 처리

| 테이블 | 처리 | 근거 |
| --- | --- | --- |
| `cash_transactions` | **삭제**. `ledger_entries`가 대체 | 단식부기라 그대로 두면 원장이 둘이 된다 |
| `accounts.cash_balance` | 유지. **원장 파생 캐시**로 역할 명시 | `= Σ(CASH entries)`. 대사 대상 |
| `accounts.realized_profit` | 유지하되 파생 캐시 | `= −Σ(REALIZED_PNL entries)`. 조회 성능용, 대사 대상 |
| `accounts.total_asset_value` | 유지. **원장 파생 아님** | 시가 평가액이라 원장으로 재구성 불가. 별도 평가 batch |
| `accounts.reserved_cash` | **추가하지 않는다** | 3절 참고. `orders`에서 파생한다 |
| `holdings.quantity` / `total_purchase_amount` | 유지. 파생 캐시 | `= Σ(SECURITIES entries)`. 대사 대상 |
| `holdings.average_price` | 유지 | 이동평균 계산에 상태가 필요하다. `total_purchase_amount / quantity` |
| `executions` | 유지 + `trade_id`, `ledger_transaction_id` 추가 | 체결 사실과 원장 거래를 연결 |
| `executions.commission` / `tax` | 유지하되 **표시용 사본**으로 명시 | 원장이 authoritative. 2.1 참고 |
| `orders` | 유지 | 가용잔고의 원천이 된다 |
| `daily_account_snapshots` | 유지 + 6절에서 실제로 채운다 | 마감 확정 |
| `CashTransactionType` enum | 삭제. `LedgerAccount` + `LedgerTransactionType`으로 대체 | |

`CashTransaction` 엔티티·리포지토리·`CashTransactionType`을 삭제하고 `MatchingEngineTransactionService`의 `createCashTransaction()`을 원장 기록으로 교체한다.

**기존 데이터**: 운영 중이 아니므로 이관하지 않는다. `cash_transactions`를 드롭한다. 운영 데이터가 생긴 뒤라면 개시 분개로 잔고를 맞추는 이관 script가 별도로 필요하다.

### 2.1 `executions`의 수수료·세금은 표시용 사본이다

수수료·세금이 `FEE`/`TAX` 분개가 되면 같은 값이 `executions.commission` / `executions.tax`에도 남아 두 곳에 존재하게 된다. 두 컬럼을 **지우지 않고 표시용 비정규화로 유지**한다.

근거는 조회 경로다. 체결 내역은 사용자가 가장 자주 보는 화면이고, 거기에 수수료를 같이 보여주는 것은 현업 증권사 화면과 같은 형태다. 매번 원장을 조인해서 읽게 만들 이유가 없다.

대신 역할을 못박는다.

| | 역할 |
| --- | --- |
| `ledger_entries`의 `FEE`/`TAX` | **authoritative.** 잔고·손익 계산의 근거는 언제나 이쪽 |
| `executions.commission` / `tax` | 표시용 사본. 계산에 쓰지 않는다 |

사본은 어긋날 수 있으므로 6.1의 대사 항목에 넣는다. 엔티티 주석에도 "표시용 사본이며 계산에 쓰지 않는다"를 남겨, 나중에 이 값으로 손익을 계산하는 코드가 들어오지 않게 한다.

## 3. 가용잔고 — 저장하지 않고 `orders`에서 파생한다

이번 설계에서 가장 중요한 선택이다.

`Account.reservedCash` 컬럼을 두고 접수 시 `+`, 체결·취소·거절 시 `−` 하는 방식이 직관적이지만, **모든 해제 경로를 빠짐없이 구현해야 하고 하나라도 빠지면 그 금액이 영구히 묶인다.**

컬럼이 맞는지 검증하는 것 자체는 가능하다 — 미체결 주문을 집계해 비교하면 된다. 다만 그 대사의 기준값이 곧 아래의 파생값이므로, 그럴 거면 컬럼을 둘 이유가 읽기 속도밖에 남지 않는다.

대신 미체결 주문에서 파생한다.

```sql
-- 매수 구속 금액
select coalesce(sum(
    case
        when o.order_type = 'LIMIT' then o.order_price * o.remaining_quantity
        else :marketOrderUnitPrice * o.remaining_quantity  -- 시장가 미체결분: 당일 고가 (4절)
    end
), 0)
from orders o
where o.account_id = :accountId
  and o.order_side = 'BUY'
  and o.status in ('PENDING', 'PARTIALLY_FILLED')

-- 매도 구속 수량 (종목별)
select coalesce(sum(o.remaining_quantity), 0)
from orders o
where o.account_id = :accountId and o.stock_id = :stockId
  and o.order_side = 'SELL'
  and o.status in ('PENDING', 'PARTIALLY_FILLED')
```

**해제 경로라는 것이 존재하지 않는다.** 주문이 체결되면 `remaining_quantity`가 줄고, 취소·거절되면 `status`가 빠지면서 합계에서 자동으로 사라진다. 부분 체결도 자동 반영된다. 드리프트가 개념적으로 발생할 수 없다.

```text
주문가능금액 = accounts.cash_balance − 매수 구속 금액
매도가능수량 = holdings.quantity − 매도 구속 수량
```

둘 다 저장하지 않는 계산값이다.

**대가**: 주문 접수마다 집계 쿼리가 한 번 더 나간다. `orders(account_id, order_side, status)` 복합 index를 추가한다. 계좌당 미체결 주문이 수십 건 수준이면 무시할 만하고, 정합성이 규칙이 아니라 구조로 보장되는 값어치가 훨씬 크다.

> **이 선택이 도입 순서를 바꾼다.** 이전에 "대사 batch를 먼저 깔고 예약을 나중에"라고 권했던 이유는 해제 누락이 곧 자산 동결이라는 것이었다. 파생 방식에는 해제 경로 자체가 없어 그 위험이 사라지므로, 대사를 기다리지 않고 바로 갈 수 있다.

## 4. 시장가 매수의 구속 금액 — 당일 고가 기준

지정가 매수는 `order_price × remaining_quantity`가 그대로 구속 금액이다. 문제는 시장가 매수인데, `orderPrice`가 `null`이라 접수 시점에 주문금액을 계산할 수 없다.

**기준은 `DailyPriceRangeResponse.dailyHighPrice`(당일 고가)로 한다.**

```java
/** 시장가 매수 1주당 구속 금액. */
private BigDecimal reservationUnitPrice(String symbol) {
    BigDecimal dailyHighPrice = dailyPriceRangeService.getDailyPriceRange(symbol).dailyHighPrice();
    if (dailyHighPrice == null || dailyHighPrice.signum() <= 0) {
        // 구속 금액을 계산할 수 없는 주문을 받아들이면 예수금 규칙에 구멍이 생긴다.
        throw new IllegalArgumentException("시장가 주문을 받을 수 없습니다. 잠시 후 다시 시도해 주세요.");
    }
    return dailyHighPrice;
}
```

### 이미 있는 규칙과 일치한다

`MatchingEngineTransactionService.applyMarketOrderRemainingPrice()`가 **이미 같은 값을 쓰고 있다.** 시장가 매수의 잔여 물량에 당일 고가를 대기 가격으로 심는다.

```java
BigDecimal waitingPrice = order.getOrderSide() == OrderSide.BUY
    ? dailyHighPrice(dailyPriceRange)
    : dailyLowPrice(dailyPriceRange);
```

덕분에 구속 금액 계산이 주문 생애 내내 한 가지 기준으로 이어진다.

| 시점 | `orders.order_price` | 구속 단가 |
| --- | --- | --- |
| 접수 직후 (시장가) | `null` | 당일 고가를 조회해서 사용 |
| 첫 체결 이후 | 당일 고가가 심어짐 | `order_price` 그대로 |

즉 3절의 파생 쿼리에서 시장가 주문의 특례 처리가 **첫 체결 전 구간에만** 필요하다. 그 뒤로는 지정가와 같은 식으로 계산된다.

### 당일 고가는 상한이 아니다 — 받아들이는 한계

당일 고가는 "**지금까지** 거래된 최고가"지 "오늘 도달 가능한 최고가"가 아니다. 시장가 매수가 당일 고가보다 높은 가격에 체결될 수 있고, 그러면 구속 금액이 실제 체결금액보다 작아진다.

원장이 깨지지는 않는다. **08번에서 넣은 체결 시점 캡(`affordableQuantity`)이 방어선으로 남아 있기 때문이다.** 잔고가 모자라면 살 수 있는 만큼만 체결되고 잔량은 거절된다. 접수 검증이 놓친 것을 체결 검증이 잡는 2중 구조이고, 5절에서 체결 시점 거절을 남겨 두는 이유가 이것이다.

사용자 경험상의 결과만 남는다 — 시장가 매수가 급등 구간에서 부분 체결 후 잔량 거절될 수 있다. 이를 줄이려면 구속 단가에 여유 계수를 곱하는 방법이 있지만, 그만큼 주문가능금액이 과하게 묶인다. 기본은 계수 없이 가고 실사용 데이터를 보고 정한다.

### 국내 주식의 진짜 상한가

국내 주식은 당일 가격제한폭이 있어 **전일 종가 × 1.3**(KOSPI·KOSDAQ, KONEX는 × 1.15)을 넘을 수 없다. 이 값이 존재하는 진짜 상한이므로, 국내 종목에 한해서는 당일 고가보다 안전한 기준이다.

지금 쓰지 않는 이유는 두 가지다.

- **전일 종가를 구할 경로가 없다.** `StockPrice.previousClose` 필드는 있으나 그 엔티티를 쓰는 코드가 하나도 없고, Toss price API는 `lastPrice`만 준다.
- **국내 종목 거래 자체가 아직 안 된다.** 종목 마스터 적재가 미구현(§18)이다.

국내 종목 지원이 실제로 들어올 때 이 분기를 추가한다. 그때는 당일 고가가 정의상 상한가 이하이므로 두 값 중 **상한가**를 쓰면 구속 부족이 구조적으로 사라진다.

```java
// 국내 종목 지원 시 추가할 분기
case KOSPI, KOSDAQ -> previousClose(stock).multiply(new BigDecimal("1.30"));
case KONEX         -> previousClose(stock).multiply(new BigDecimal("1.15"));
```

### 수수료·세금

구속 금액에 수수료·세금 예상액을 더한다. 현재 `ZeroCommissionCalculator`라 0이지만, 규약을 먼저 세워 두면 계산기를 바꿔도 접수 검증이 따라온다.

## 5. 접수 시점 검증

`OrderTradingService.placeOrder()`에 검증을 넣는다.

```java
@Transactional
public OrderResponse placeOrder(User user, OrderPlaceRequest request) {
    ...
    // 계좌 row를 잠가 동시 접수를 계좌 단위로 직렬화한다.
    // 잠그지 않으면 두 요청이 같은 가용잔고를 읽고 둘 다 통과한다.
    Account account = accountRepository.findByUserIdForUpdate(user.getId())
        .orElseThrow(() -> new IllegalArgumentException("계좌를 찾을 수 없습니다."));
    ...
    if (request.side() == OrderSide.BUY) {
        BigDecimal required = requiredReservation(stock, request);
        BigDecimal available = account.getCashBalance().subtract(reservedCash(account.getId()));
        if (available.compareTo(required) < 0) {
            throw new IllegalArgumentException("주문가능금액이 부족합니다.");
        }
    } else {
        long available = holdingQuantity(account.getId(), stock.getId())
            - reservedQuantity(account.getId(), stock.getId());
        if (available < request.quantity()) {
            throw new IllegalArgumentException("매도가능수량이 부족합니다.");
        }
    }
    ...
}
```

`findByUserIdForUpdate`는 이미 `AccountRepository`에 선언되어 있고 아직 호출부가 없다. 이 자리가 그 메서드가 있어야 할 곳이다.

**멱등 재요청 경로**(`clientOrderId`로 기존 주문을 찾아 반환)는 검증보다 먼저 처리한다. 이미 접수된 주문을 재검증하면 그 사이 잔고가 줄었을 때 같은 요청이 성공했다가 실패하게 된다.

**08번의 체결 시점 거절은 그대로 둔다.** 접수 검증이 생기면 거의 발동하지 않지만, 두 가지 경우에 여전히 필요하다 — (a) 여러 미체결 주문이 경합해 먼저 체결된 쪽이 잔고를 가져간 경우, (b) 시장가 구속 금액이 실제 체결가보다 작았던 경우. 방어선을 두 겹으로 유지한다.

## 6. 대사와 마감

원장 시스템의 진짜 안전망이다. 여기까지 해야 "틀렸다는 걸 발견할 수 있는" 상태가 된다.

### 6.1 대사 batch

```text
1. 전역 균형    SELECT SUM(amount) FROM ledger_entries                    = 0
2. 거래 균형    거래별 SUM(amount)                                        = 0  (전부)
3. 현금 잔고    accounts.cash_balance          = Σ(CASH entries)
4. 실현손익      accounts.realized_profit       = −Σ(REALIZED_PNL entries)
5. 보유 수량    holdings.quantity              = Σ(SECURITIES entries.quantity)
6. 보유 원가    holdings.total_purchase_amount = Σ(SECURITIES entries.amount)
7. 수수료 사본  Σ(executions.commission)       = Σ(FEE entries)      (거래 단위로 대조)
8. 세금 사본    Σ(executions.tax)              = Σ(TAX entries)
```

가용잔고는 저장하지 않으므로 대사 대상이 아니다(3절).

불일치는 **자동으로 덮어쓰지 않는다.** 로그·metric으로 올리고 사람이 판단한다. 조용히 맞춰 버리면 버그를 숨기게 된다. 정정이 필요하면 1.2절의 반대분개로 한다.

### 6.2 일별 마감

`DailyAccountSnapshot`에 영업일 종료 시점의 `cash_balance`, `realized_profit`, `unrealized_profit`, 보유 평가액을 확정 저장한다. 이후 대사는 전체 원장이 아니라 **직전 스냅샷 + 그 이후 분개**로 수행할 수 있어 원장이 길어져도 비용이 일정하게 유지된다.

> **보류**: 이 스냅샷은 시가 평가를 요구하는데 그건 이번 범위에서 제외한 항목이다. 그리고 6.1의 질의가
> 불일치 항목만 돌려주는 고정 개수 aggregate라 비용 문제도 아직 없다. 구현 순서 14번 참고.

## 7. 통합 테스트 기반 — 이 계획과 함께 깐다

원장 불변식(`잔고 = Σ원장`)과 가용잔고 경합은 **실제 transaction과 row lock 없이는 검증할 수 없다.** Mockito 단위 테스트는 분기 로직만 고정할 뿐, "두 요청이 동시에 들어와도 예수금을 넘지 못한다"를 증명하지 못한다. 그래서 테스트 기반을 이 계획의 1단계로 넣는다.

### PostgreSQL Testcontainers를 쓴다

H2나 embedded DB로는 안 된다. 이 계획이 의존하는 동작이 PostgreSQL 고유이기 때문이다.

- `PESSIMISTIC_WRITE`의 실제 차단 동작 (5절 계좌 row lock, 매칭 엔진 전반)
- `@Check` 제약과 unique 제약(멱등키)의 실제 위반 시점
- 데드락 감지 (08번에서 제외 항목으로 남긴 계좌 락 순서 문제)

```gradle
testImplementation 'org.springframework.boot:spring-boot-testcontainers'
testImplementation 'org.testcontainers:junit-jupiter'
testImplementation 'org.testcontainers:postgresql'
```

`@ServiceConnection`을 붙인 공용 `PostgresTestContainer` 설정 클래스를 두고, container를 test class 간에 재사용해(`static` + `withReuse`) 기동 비용을 한 번만 낸다.

### 두 층으로 나눈다

| 층 | 범위 | 대상 |
| --- | --- | --- |
| `@DataJpaTest` + Testcontainers | JPA·SQL·제약·row lock | 원장 분개, 잔고 재구성, 가용잔고 집계 쿼리, 멱등키 |
| `@SpringBootTest` + Testcontainers | 서비스 경계 | 주문 접수 검증, 동시 접수 경합, 체결 transaction |

Redis가 필요한 경로(매칭 트리거, 심볼 락)는 이번 범위 밖이므로 slice로 잘라낸다. 필요해지면 Redis container를 같은 방식으로 추가한다.

### 기존 실패 test 정리

`PaperTradingApplicationTests.contextLoads()`와 benchmark test 2건이 로컬 PostgreSQL 부재로 계속 실패하고 있다(§17.2). Testcontainers를 도입하면 `contextLoads()`는 자연히 통과하게 되므로 함께 정리한다. benchmark test는 성격이 달라 별도로 둔다.

## 설정 추가

```yaml
ledger:
  reconciliation:
    cron: ${LEDGER_RECONCILIATION_CRON:0 30 5 * * *}
```

## 구현 순서

네 단계로 나눈다. **0단계가 먼저다** — 나머지 전부가 통합 테스트로만 검증되기 때문이다. 그 뒤 **B는 A에 의존하지 않는다** — 가용잔고는 `orders`에서만 파생하므로, 요구사항(예수금 초과 주문 불가)이 급하면 A를 건너뛰고 B부터 해도 된다.

**0. 테스트 기반** (7절) — **구현 완료 (2026-09-12)**

1. ~~Testcontainers 의존성 추가, 공용 container 설정 클래스, `@DataJpaTest` 슬라이스 구성.~~
   `IntegrationTestContainers`(PostgreSQL·Redis static singleton), `@JpaIntegrationTest`, `@ApplicationIntegrationTest`, `application-integration.yaml`.
2. ~~기존 `contextLoads()` 실패 정리.~~ 통과. benchmark test는 `benchmarkTest` task로 분리.
3. ~~기반이 실제로 도는지 증명하는 첫 통합 테스트.~~ `OrderMatchingQueryIntegrationTest` 5건 —
   08번에서 바꾼 `excludedOrderIds` 쿼리를 실제 SQL로 검증하고, `orders`의 `@Check` 제약이 DB에서 실제로 걸리는지 확인한다.

전체 test가 52건 전부 통과하는 상태가 되었다. 자세한 구성은 `current-implementation-overview.md` §17.

**A. 원장 모델 교체** — **구현 완료 (2026-09-12)**

3. ~~`LedgerAccount`, `LedgerTransactionType` enum 신설.~~
4. ~~`LedgerTransaction`, `LedgerEntry` 엔티티·리포지토리, 균형을 강제하는 posting 서비스.~~ `LedgerPostingService`.
5. ~~`createCashTransaction()`을 원장 기록으로 교체. 체결 1건마다 `tradeId` 발급.~~
6. ~~`executions`에 `trade_id`, `ledger_transaction_id` 추가.~~
7. ~~`AccountOpeningService.open()` 신설.~~ 회원가입 연동은 01번 범위로 남는다.
8. ~~`CashTransaction` 엔티티·리포지토리·`CashTransactionType` 삭제.~~

**B. 가용잔고와 접수 검증** — **구현 완료 (2026-09-12)**

> A보다 먼저 구현했다. B는 `orders`에서만 파생하므로 A에 의존하지 않는다.

9. ~~`OrderRepository`에 구속 금액·구속 수량 집계 쿼리 추가. index 추가.~~
   `sumReservedCash()`, `sumReservedQuantity()`, `idx_orders_account_side_status`.
10. ~~시장가 매수 구속 단가 계산(4절).~~ 당일 고가 기준. 못 구하면 접수 거절.
11. ~~`placeOrder()`에 계좌 row lock + 검증 추가.~~ `IllegalArgumentException`은 기존 handler가 400으로 매핑한다.
12. ~~잔고 조회 응답 노출.~~ `GET /api/v1/accounts/me/balance` → `AccountBalanceResponse`.

**설계 변경 하나**: 계획에는 시장가 구속 단가를 집계 시점에 계산하는 것으로 되어 있었으나, 그러면 계좌 row lock을 쥔 채 시세를 조회하게 된다. `orders.reserved_unit_price` 컬럼을 두어 접수 시점에 한 번 정하고, 집계는 외부 의존 없는 순수 SQL이 되게 했다. 주문의 불변 속성이라 드리프트 위험은 없다. 자세한 내용은 `current-implementation-overview.md` §13.9.

**C. 대사와 마감** — **13번 구현 완료, 14번 보류 (2026-09-12)**

13. ~~대사 batch(6.1)와 metric.~~ `LedgerReconciliationService`. 8개 검사 전부, 불일치 항목만 돌려주는 질의.
14. **일별 스냅샷 batch(6.2) — 지금 구현할 수 없다.**

    `DailyAccountSnapshot`은 `stock_evaluation`, `unrealized_profit`, `return_rate`가 모두 not null인데
    셋 다 **시가 평가**를 요구한다. 그런데 미실현손익 평가는 이 계획이 "이번 범위에서 제외"로 명시한
    항목이다(환율까지 얽힌다). 취득원가를 평가액 자리에 넣으면 컬럼의 의미와 다른 값이 들어가므로
    안 넣느니만 못하다.

    6.2가 내세운 목적(대사 비용을 일정하게 유지)도 지금은 해당하지 않는다. 13번의 질의가 전부
    불일치 항목만 돌려주는 고정 개수 aggregate라, 원장이 길어져도 검사 항목 수만큼만 늘어난다.
    체크포인트가 필요해지는 시점은 그 aggregate가 느려질 때다.

    평가 batch가 생기면 그때 함께 다룬다.

## 검증 방법

**원장 균형**

- 매수·매도·내부 체결·수수료를 섞어 수십 건 실행한 뒤 `SUM(amount) = 0`, 거래별 합계 0을 확인한다.
- 내부 체결 1건이 `ledger_entries` 5행(양쪽 CASH·SECURITIES + 매도자 REALIZED_PNL)을 만들고 모두 같은 `transaction_id`를 갖는지.

**잔고 재구성**

- `accounts.cash_balance`를 지우고 원장에서 재계산했을 때 원래 값과 일치하는지. 이게 "원장 시스템인가"의 최종 판정이다.
- `realized_profit`, `holdings.quantity`, `total_purchase_amount`도 동일하게.

**가용잔고**

- 예수금 100만원으로 100만원짜리 매수 주문 2건을 연속 접수 → **두 번째가 거절**되는지. 이게 이번 요구사항의 핵심 검증이다.
- 같은 상황에서 두 요청을 **동시에** 보내 둘 다 통과하지 않는지(계좌 row lock 확인).
- 5주 보유 상태에서 5주 매도 주문 2건 → 두 번째 거절.
- 첫 주문을 취소하면 주문가능금액이 즉시 회복되는지. 컬럼이 아니라 파생이므로 해제 코드 없이 되어야 한다.
- 부분 체결된 주문의 구속 금액이 잔여 수량 기준으로 줄어드는지.

**시장가 매수**

- 당일 고가 × 주문수량으로 구속되는지.
- 첫 체결 이후 `order_price`에 당일 고가가 심어지고, 구속 금액이 지정가와 같은 식으로 계산되는지.
- 당일 고가가 `null`인 종목: 접수가 거절되는지.
- **당일 고가보다 높은 가격에 체결될 때**: 구속이 모자라도 08번의 체결 시점 캡이 걸려 부분 체결 후 잔량이 거절되고, 잔고가 음수가 되지 않는지.

**멱등성**

- 같은 `idempotency_key`로 원장 거래를 두 번 기록하려 하면 unique 제약에 걸리는지.

**대사**

- 원장 분개를 인위적으로 한 행 지우고 batch가 불일치를 보고하는지. 자동으로 덮어쓰지 않는지.

## 추가할 자동화 테스트

- 거래 합계가 0이 아닌 분개 목록을 `LedgerPosting`이 거부하는지
- 내부 체결이 양쪽 계좌의 분개를 하나의 거래로 묶는지
- 매도 체결의 `REALIZED_PNL` 분개가 `매도금액 − 취득원가`와 일치하는지
- 수수료가 0이 아닐 때 `FEE` 분개와 `executions.commission`이 같은 값을 갖는지
- 수수료가 0이면 `FEE` 분개를 만들지 않는지
- 원장에서 재계산한 잔고가 `accounts.cash_balance`와 일치하는지
- 중복 `idempotency_key`가 거부되는지
- 미체결 매수 주문이 주문가능금액에서 빠지는지
- 주문 취소·거절 후 주문가능금액이 회복되는지
- 부분 체결이 구속 금액을 잔여 수량 기준으로 줄이는지
- 매도 구속 수량이 같은 종목에만 적용되는지
- 시장가 매수 구속 단가가 당일 고가를 따르는지
- 당일 고가를 구할 수 없으면 접수가 거절되는지
- 구속 금액보다 높은 가격에 체결돼도 잔고가 음수가 되지 않는지
- 반대분개가 원본을 남긴 채 잔고를 되돌리는지

A·B 단계 대부분은 실제 JPA transaction과 row lock이 필요하므로 7절의 Testcontainers 기반 위에서 작성한다. 특히 **동시 접수 경합**과 **잔고 재구성**은 단위 테스트로 대체할 수 없다.

## 이번 범위에서 제외

- **T+2 결제 분리** — Context 참고. 출금 경로가 없어 사용자 가시 차이가 없다.
- **미실현손익 평가와 `total_asset_value` 갱신** — 시가 평가라 원장 파생이 아니다. 별도 평가 batch가 필요하고 환율까지 얽힌다.
- **자기 계좌 자전거래 차단과 주문가격 제한폭 검증** — 08번에서도 제외했다. 가용잔고와 독립적이고, 제한폭 검증은 4절의 상한가 데이터가 갖춰진 뒤에 하는 편이 낫다.
- **회원가입 → 계좌 자동 생성 연동, 투자금 충전 API** — 01번 범위. 이 계획은 `AccountOpeningService`와 개시 원장 규약까지만 정의한다.
- **국내 주식의 전일 종가 기반 상한가** — 4절 참고. 전일 종가를 구할 경로가 없고 국내 종목 거래 자체가 미구현이라, 국내 종목 지원과 함께 다룬다.
- **시장가 매수의 금액 지정 방식**("100만원어치") — 구속 금액이 곧 주문금액이라 당일 고가의 한계가 사라지지만, API 스펙 변경이라 분리한다.
- **기존 운영 데이터 이관** — 운영 중이 아니라고 전제했다.
- **계좌 락 순서로 인한 데드락**(08번 제외 항목) — 내부 체결이 두 계좌를 잡는 구조는 그대로다.

## 확인이 필요한 가정

- **당일 고가는 상한이 아니라 하한에 가까운 추정치다.** "지금까지 거래된 최고가"이므로 시장가 매수가 그보다 높게 체결될 수 있고, 그만큼 구속이 부족해진다. 원장은 08번의 체결 시점 캡이 지키지만, 급등 구간에서 시장가 매수의 부분 체결 후 잔량 거절이 얼마나 자주 일어나는지는 실사용 데이터로 확인해야 한다. 잦으면 여유 계수를 도입하거나 금액 지정 방식으로 전환한다.
- **장 시작 직후 당일 고가가 유효한 값으로 온다**고 가정했다. 첫 체결 전이면 `dailyHighPrice`가 `null`이거나 전일 값일 수 있다. `null`이면 접수를 거절하도록 했지만, 개장 직후 시장가 매수가 통째로 막히는 구간이 생길 수 있으므로 실제 응답을 확인해야 한다.
- **Testcontainers가 CI와 개발 환경에서 Docker를 쓸 수 있다**고 가정했다. CI 설정이 아직 없으므로(§19.8) 도입 시점에 Docker 사용 가능 여부를 먼저 확인해야 한다. 불가능하면 `@DataJpaTest`의 row lock 검증을 포기하고 다른 방법을 찾아야 하는데, 그러면 이 계획의 핵심 검증이 빠진다.
- **계좌당 미체결 주문이 수십 건 수준**이라고 가정하고 구속 금액을 매번 집계한다. 한 계좌가 수천 건을 유지하는 사용 패턴이 나오면 저장 컬럼 + 대사 방식으로 바꿔야 한다. 그때도 3절의 파생 정의가 대사의 기준이 된다.
- **이동평균 원가법**을 쓴다고 전제했다. 선입선출(FIFO)로 바꾸면 `SECURITIES` 분개의 매도 원가 계산과 `holdings.average_price`의 의미가 달라진다.
- **운영 데이터가 없다**고 전제하고 `cash_transactions`를 드롭한다. 틀렸다면 개시 분개로 잔고를 맞추는 이관 script가 선행되어야 한다.
