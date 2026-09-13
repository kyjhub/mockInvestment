# 당일 유효(DAY) 주문 — 장 마감 시 미체결 자동 취소

## Context

지금 주문에는 **유효기간 개념이 없다.** `Order`에 `submittedAt`·`canceledAt`·`rejectedAt`은 있지만 만료 시각도, 주문 유효기간 구분(DAY/GTC/IOC/FOK)도 없다. `order.cancel()`을 부르는 곳은 사용자가 직접 취소하는 `DELETE /api/v1/orders/{orderId}` 하나뿐이다.

결과적으로 **모든 주문이 사실상 GTC**인데, 그건 선택한 게 아니라 정의된 적이 없는 상태다. 현업 증권사 주문의 기본값은 DAY이고 GTC는 별도로 지정한다.

### 이게 만드는 문제 둘

**가용잔고가 영원히 묶인다.** 어제 낸 매수 주문이 취소되지 않으면 그 구속액이 계속 예수금을 깎는다(§13.11). 사용자는 주문가능금액이 왜 모자란지 모르고, 주문 조회 API도 아직 없어 확인할 방법이 없다.

**장외에 헛돈다.** `PendingOrderRematchScheduler`가 30초마다 미체결 종목을 재발행하고, 그 종목들이 호가 폴링 대상이 된다. 장이 닫혀 호가가 변하지 않는데도 Toss API 예산을 계속 쓴다.

## 1. 거래일의 끝은 정규장 마감이 아니라 **애프터마켓 종료**다

토스 시장 달력 응답을 실제로 받아보니 한 거래일이 네 세션으로 되어 있다. 모두 KST다.

| 세션 | 시각(KST) |
| --- | --- |
| `dayMarket` (주간거래) | 09:00 ~ 16:50 |
| `preMarket` | 17:00 ~ 22:30 |
| `regularMarket` | 22:30 ~ 익일 05:00 |
| `afterMarket` | 익일 05:00 ~ 07:00 |

정규장이 끝나도 **두 시간 더 거래가 가능하다.** 정규장 마감을 기준으로 실효시키면 아직 체결될 수 있는 주문을 죽인다. 기준 시각은 `afterMarket().endTime()`이다.

```java
MarketCalendarResult calendar = marketCalendarService.getUsMarketCalendar(null).result();
OffsetDateTime tradingDayEnd = calendar.today().afterMarket().endTime();
```

**마감 시각은 계산하지 않는다.** `MarketSession.endTime`이 offset을 가진 `OffsetDateTime`이라 서머타임(KST 05:00 EDT / 06:00 EST)과 **조기 마감**이 공급자 값 그대로 온다. `ZoneId.of("America/New_York")`와 16:00을 하드코딩하면 전환일과 조기 마감을 모두 놓친다.

응답은 12시간 캐시(`market-calendar.cache.ttl-hours`)라 분 단위로 불러도 외부 호출이 늘지 않는다.

**`today()`와 `previousBusinessDay()`를 함께 본다.** 호출 시점에 따라 `today()`의 종료가 아직 미래다 — 정규장이 도는 밤 시간에는 오늘 거래일이 진행 중이므로 직전 거래일의 종료가 기준이 된다. 이미 지난 종료 시각 중 가장 늦은 것을 고른다.

```java
Stream.of(calendar.today(), calendar.previousBusinessDay())
    .map(MarketBusinessDay::afterMarket).map(MarketSession::endTime)
    .filter(endTime -> !endTime.isAfter(now))
    .max(OffsetDateTime::compareTo)
```

**영업일 판정도 이 스트림이 그대로 한다.** 휴장일 응답은 `today` 자체가 비는 게 아니라 `today`는 있고 네 세션이 전부 `null`로 온다.

```json
"today": { "date": "2026-07-03", "dayMarket": null, "preMarket": null,
           "regularMarket": null, "afterMarket": null }
```

세션의 `null`을 걸러내면 그날이 후보에서 빠지고 자연히 직전 영업일의 종료가 기준이 된다. 별도의 영업일 판정 분기가 필요 없다. 반대로 세션을 `null` 검사 없이 참조하면 휴장일마다 `NullPointerException`이 난다.

끝난 거래일이 하나도 없으면 기준 시각이 없으니 아무것도 만료하지 않는다. 휴장일에 접수된 주문은 다음 영업일까지 살아남는다 — 예약주문과 같은 취급이다.

## 2. 취소 대상 — 마감 시각 **이전에 접수된** 미체결 주문

```text
status in (PENDING, PARTIALLY_FILLED)
  and submitted_at < 직전에 끝난 거래일의 애프터마켓 종료 시각
```

`submitted_at` 조건이 핵심이다. 마감 이후에 접수된 주문은 **다음 세션 주문**(예약주문)이므로 이번 마감에 취소하면 안 된다. 그 주문은 다음 세션 마감까지 유효하고, 그때도 미체결이면 그때 만료된다.

이 조건 덕분에 **배치가 멱등해진다.** 마감 후 반복 실행해도 첫 회에 전부 만료되고, 이후에는 대상이 없다. 마감 이후 접수된 주문은 애초에 조건에 걸리지 않는다. 별도의 "이미 처리함" 상태를 들고 다닐 필요가 없다.

부분 체결된 주문은 **잔량만** 만료시키고 체결 이력은 그대로 둔다.

## 3. 상태는 `EXPIRED`를 새로 만든다

사용자가 직접 취소한 것과 시스템이 만료시킨 것은 다르다. 주문 조회 화면에서 "내가 취소함"과 "장 마감으로 실효됨"이 같아 보이면 문의가 생긴다.

```java
// Enum/OrderStatus
PENDING, PARTIALLY_FILLED, FILLED, CANCELED, REJECTED, EXPIRED
```

```java
// Entity/Order
public void expire(String reason) {
    if (!CANCELABLE_STATUSES.contains(this.status)) {
        throw new IllegalArgumentException("이미 종료된 주문은 만료시킬 수 없습니다.");
    }
    this.status = OrderStatus.EXPIRED;
    this.canceledAt = LocalDateTime.now();
    this.closeReason = reason;
}
```

**`reject_reason`을 `close_reason`으로 개명한다.** 지금도 거절뿐 아니라 잔량 취소(부분 체결 후 `reject()`)에 쓰이고 있어 이름이 이미 좁다. 만료까지 더하면 "종료 사유"가 맞는 이름이다. `OrderResponse`의 필드명도 함께 바꾼다.

종료 시각은 `canceled_at`을 재사용한다. 만료도 종료의 한 형태이고, 상태 컬럼이 종류를 구분하므로 타임스탬프 컬럼을 하나 더 늘릴 이유가 없다.

> **대안**: `CANCELED`를 재사용하고 사유만 남기면 스키마 변경이 `close_reason` 개명뿐이다. 다만 조회 화면에서 사용자 취소와 구분하려면 결국 사유 문자열을 파싱해야 한다. enum 값 하나가 더 싸다.

## 4. 실행은 1분 주기 폴링으로 한다

cron 고정 시각(예: KST 07:00)은 단순하지만, 서머타임 때문에 **마감 후 최대 2시간 동안 주문이 살아 있다.** 그동안 가용잔고가 묶이고, 장외 호가가 잡히면 체결될 수도 있다.

```java
@Scheduled(
    scheduler = SchedulingConfig.VALUATION_SCHEDULER,
    fixedDelayString = "${order.day-expiry.fixed-delay-ms:60000}"
)
public void expireDayOrders() {
    // 이미 끝난 거래일이 있는가 → 있으면 그 종료 시각 이전 접수분을 만료
}
```

2절의 멱등성 덕분에 자주 돌아도 안전하다. 마감 직후 1분 안에 처리되므로 구속이 길게 남지 않는다.

전용 스케줄러를 새로 만들지 않고 평가 스케줄러(`VALUATION_SCHEDULER`)를 함께 쓴다. 둘 다 분 단위 저빈도 작업이고, 기본 풀에 두면 100ms·150ms 주기 작업을 밀어낸다.

## 5. 가용잔고는 저절로 풀린다

만료가 `status`를 `PENDING`에서 빼면 구속액 집계에서 자동으로 사라진다(§13.11). **해제 코드를 따로 쓰지 않는다.**

```sql
where o.account_id = ? and o.order_side = 'BUY'
  and o.status in ('PENDING', 'PARTIALLY_FILLED')   -- EXPIRED는 여기서 빠진다
```

구속액을 컬럼에 저장했다면 만료 경로에도 해제 코드를 넣어야 했고, 빠뜨리면 잔고가 영구 동결됐을 것이다. 파생 방식의 값어치가 다시 드러나는 지점이다.

## 6. 장외 매칭도 저절로 조용해진다

마감 후 그 세션의 미체결 주문이 0이 되므로 `PendingOrderRematchScheduler`가 재발행할 종목이 없어진다. **스케줄러를 건드리지 않아도 장외 재매칭이 멈춘다.**

다만 마감 후 접수된 예약주문은 다음 개장까지 미체결로 남아 30초마다 재발행된다. 호가가 변하지 않으면 dirty 트리거가 없어 안전망 주기만 돌지만, 그 종목이 호가 폴링 대상으로 남는 것은 그대로다. 이건 별도 항목이다(아래 제외 참고).

## 7. 타임존 함정

`Order.submittedAt`은 `LocalDateTime`이라 **타임존 정보가 없다.** 서버 타임존 기준으로 저장된 값이다.

마감 시각은 `OffsetDateTime`으로 오므로, 비교하려면 **서버 타임존의 `LocalDateTime`으로 변환**해야 한다.

```java
LocalDateTime cutoff = regularClose
    .atZoneSameInstant(ZoneId.systemDefault())
    .toLocalDateTime();
```

`toLocalDateTime()`을 바로 부르면 ET 기준 벽시계 시각이 나와 서버 타임존 값과 비교할 수 없다. `atZoneSameInstant`가 반드시 앞에 와야 한다.

> 더 근본적으로는 시각 컬럼이 `OffsetDateTime`이거나 UTC 저장이어야 한다. 서버 타임존이 바뀌면 기존 값의 의미가 달라지기 때문이다. 이 계획의 범위를 넘으므로 가정에 기록만 한다.

## 8. 국내 종목 확장

지금은 거래 가능한 종목이 전부 미국 시장이라 하나의 마감 시각으로 충분하다. 국내 종목이 들어오면 마감이 15:30 KST로 달라지므로 **주문을 종목 시장별로 묶어 각자의 마감 시각을 적용**해야 한다.

그때 `MarketCalendarService`도 미국 전용(`getUsMarketCalendar`)이라 국내 장 운영정보 경로가 필요하다. 지금은 확장 지점만 남기고 구현하지 않는다.

## 설정 추가

```yaml
order:
  day-expiry:
    # 마감 직후 만료를 위해 분 단위로 확인한다. 대상이 없으면 no-op이다.
    fixed-delay-ms: ${ORDER_DAY_EXPIRY_DELAY_MS:60000}
```

## 구현 순서

1. `OrderStatus`에 `EXPIRED` 추가.
2. `orders.reject_reason` → `close_reason` 개명, `Order.rejectReason` → `closeReason`, `OrderResponse` 필드명 변경.
3. `Order.expire(reason)` 추가.
4. `OrderRepository`에 만료 대상 조회 추가 — `status in :statuses and submittedAt < :cutoff`.
5. `DayOrderExpiryScheduler` 신설 — 마감 판정, cutoff 계산, 만료.
6. 문서 갱신 — §13에 주문 유효기간 절 추가, §18에서 관련 항목 정리.

2번이 가장 넓게 퍼지지만 기계적이다. 1·3·4는 서로 독립이다.

## 검증 방법

**마감 시각**

- 거래일 종료 전에는 아무것도 만료되지 않는지. 정규장이 끝났어도 애프터마켓이 돌고 있으면 살아 있어야 한다.
- 마감 직후 1분 안에 그 세션 주문이 만료되는지.
- **조기 마감일**(13:00 ET)에 그 시각 기준으로 만료되는지 — 16:00 하드코딩이었다면 3시간 늦는다.
- 서머타임 전환일 전후로 마감 시각이 따라 바뀌는지.

**접수 시각 경계**

- 마감 **직전** 접수 주문은 만료되는지.
- 마감 **직후** 접수 주문(예약주문)은 **살아남는지.** 이게 가장 중요한 검증이다 — 여기가 깨지면 예약주문이 불가능해진다.
- 다음 세션 마감에 그 예약주문이 만료되는지.

**멱등성**

- 마감 후 배치를 연속 5회 실행해도 결과가 같은지. 중복 실행이 무해해야 폴링 방식이 성립한다.

**휴장일**

- 끝난 거래일이 없으면 아무것도 만료되지 않는지. 주말·공휴일에 접수된 주문이 다음 영업일까지 살아야 한다.

**가용잔고**

- 만료 직후 주문가능금액이 회복되는지. 해제 코드가 없다는 점을 고정한다.

**부분 체결**

- 부분 체결된 주문이 `EXPIRED`가 되고 `filled_quantity`가 보존되는지.

## 추가할 자동화 테스트

- 마감 전에는 만료 대상이 없다
- 마감 직전 접수 주문이 만료된다
- 마감 직후 접수 주문이 살아남는다
- 조기 마감 시각이 반영된다
- 휴장일에는 만료하지 않는다
- 연속 실행이 결과를 바꾸지 않는다
- 만료로 구속액이 해제된다
- 부분 체결 주문이 체결 이력을 유지한 채 만료된다
- 이미 종료된 주문에 `expire()`를 부르면 거부된다

## 이번 범위에서 제외

- **장외 시간 매칭 차단** — 마감 후 접수된 예약주문이 다음 개장 전에 장외 호가로 체결될 수 있다. 현업 예약주문은 개장까지 대기하므로 다르게 동작하는 셈인데, "장외 체결을 허용할 것인가"는 별도 정책 결정이고 매칭 엔진에 장 운영 시간 개념을 넣는 일이라 분리한다.
- **GTC·IOC·FOK 주문 유형** — DAY를 기본이자 유일한 유효기간으로 둔다. 유형을 늘리려면 `orders`에 `time_in_force` 컬럼과 접수 API 파라미터가 필요하다.
- **국내 시장 마감** — 8절 참고.
- **시각 컬럼의 타임존 정규화** — 7절 참고. `LocalDateTime` 전반을 손대는 일이다.
- **만료 알림** — 주문이 사라진 것을 사용자가 알 방법은 주문 조회 API가 생긴 뒤의 문제다.

## 확인이 필요한 가정

- ~~**`MarketCalendarService`가 휴장일에 무엇을 돌려주는지 확인해야 한다.**~~ 실제 응답으로 확인했다. `MarketBusinessDay` 자체는 `date`와 함께 오고 네 세션만 `null`이다. 세션의 `null`을 걸러내는 것으로 영업일 판정이 된다.
- ~~**조기 마감이 `regularMarket().endTime()`에 반영된다고 가정했다.**~~ 세션별 시작·종료 시각이 응답에 그대로 실려 온다. 다만 기준 세션은 `regularMarket`이 아니라 `afterMarket`이다 — 위 §1 참조.
- **`submittedAt`이 타임존 없는 `LocalDateTime`이다.** 응답의 `OffsetDateTime`을 `atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()`으로 서버 타임존에 맞춰야 비교가 성립한다. `toLocalDateTime()`을 바로 부르면 응답 offset 기준 벽시계 시각이 나와 어긋난다. 주문 시각을 `OffsetDateTime`으로 바꾸는 게 근본 해결이지만 스키마 변경 범위가 커서 이번에는 하지 않았다.
