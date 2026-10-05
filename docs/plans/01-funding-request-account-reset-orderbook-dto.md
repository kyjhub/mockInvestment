# 모의투자금액 규칙 변경 + 실시간 호가 DTO + 계좌 초기화 기능 반영

## Context

`docs/entity-spec.md`와 현재 엔티티들은 모의투자 사이트의 초기 스펙을 반영하고 있었지만, 실제 서비스 요구사항과 대조한 결과 아래 3가지가 새로 확정/변경되었다.

1. 모의투자 신청 금액은 특정 간격이 아니라 1000 ~ 100000 사이의 임의 금액이어야 한다. (기존 엔티티는 `mod(requested_amount, 500) = 0` 제약이 걸려 있어 요구사항과 다름)
2. 토스증권 `GET /api/v1/orderbook`으로 매수/매도 호가(각 3단계)를 받아와야 한다. 이 데이터는 지속적으로 갱신되는 실시간 틱 데이터이므로, 기존에 이미 확립된 컨벤션(`MarketCalendarResponse` 등 — 운영성 API 데이터는 Entity가 아니라 DTO record로 표현하고 DB에 영속화하지 않음, Redis/캐시로 처리)을 그대로 따르는 것이 맞다.
3. 계좌에는 다음 3가지 새 정책이 추가된다.
   - 금액 충전은 계좌당 하루 1회만 신청 가능
   - 누적 신청금액 합계가 5,000,000에 도달하면 더 이상 신청 불가
   - 사용자는 누적 수익률과 무관하게 언제든 "초기화 신청"을 할 수 있고, 이를 실행하면 누적 수익률/누적 수익금/누적 신청금액이 모두 0으로 리셋된다 (과거 이력은 감사 목적으로 DB에 남긴다).

기존 `AccountFundingRequest`는 이미 "라운드 전환"(잔고 소진 후 새 금액 신청) 시점의 성과를 `assetBeforeReset`/`profitBeforeReset`/`returnRateBeforeReset`에 스냅샷으로 남기고, 리더보드용 누적 수익은 전체 이력에 대해 합산하는 구조다. 이번에 추가되는 "초기화 신청"은 이 라운드 전환과는 별개의, 누적치 자체를 0으로 되돌리는 이벤트이므로 기존 `AccountFundingRequest`를 변형하지 않고 새 엔티티로 분리한다. 이렇게 하면 기존 라운드 전환 로직과 제약조건을 전혀 건드리지 않고(수정에 닫힘) "초기화"라는 새 개념만 추가(확장에 열림)하는 형태가 된다.

## 변경 사항

### 1. `AccountFundingRequest` — 금액 제약조건만 수정

`src/main/java/com/papertrade/paper_trading/Entity/AccountFundingRequest.java`

- `@Check` 제약에서 `mod(requested_amount, 500) = 0` 조건 제거.
- 남는 제약: `round_no > 0 and requested_amount >= 1000 and requested_amount <= 100000`
- 컬럼/구조 변경 없음.

### 2. 신규 엔티티 `AccountReset` — "초기화 신청" 이력

`src/main/java/com/papertrade/paper_trading/Entity/AccountReset.java` (신규)

기존 엔티티들과 동일한 스타일(`@Getter`, `@NoArgsConstructor(PROTECTED)`, `@AllArgsConstructor(PRIVATE)`, `@Builder`, `@CreationTimestamp`)로 작성.

| Field | Column | Type | 설명 | 제약 |
|---|---|---|---|---|
| `id` | `id` | `Long` | PK | identity |
| `account` | `account_id` | `Account` | 초기화 대상 계좌 | FK, not null |
| `cumulativeReturnRateBeforeReset` | `cumulative_return_rate_before_reset` | `BigDecimal(10,6)` | 초기화 시점 누적 수익률 (감사용 스냅샷) | not null |
| `cumulativeProfitAmountBeforeReset` | `cumulative_profit_amount_before_reset` | `BigDecimal(19,2)` | 초기화 시점 누적 수익금 | not null |
| `cumulativeRequestedAmountBeforeReset` | `cumulative_requested_amount_before_reset` | `BigDecimal(19,2)` | 초기화 시점까지의 누적 신청금액 | not null |
| `resetAt` | `reset_at` | `LocalDateTime` | 초기화 시각 | not null, `@CreationTimestamp` |

관계: `Account` 1 : N `AccountReset` (한 계좌가 여러 번 초기화할 수 있음).

이 테이블은 순수 추가이며, 기존 `Account`/`AccountFundingRequest` 스키마는 건드리지 않는다. 이후 "누적" 계산(리더보드용 누적 수익률/수익금, 5,000,000 캡 체크)은 모두 `AccountFundingRequest.requestedAt > (해당 계좌의 MAX(AccountReset.resetAt), 없으면 전체 이력)` 범위로 스코프를 좁혀서 집계한다 — 이 스코핑은 서비스/리포지토리 쿼리 레벨에서 처리할 몫이며, 현재 리포지토리 계층이 아직 없으므로 이번 변경 범위에는 포함하지 않는다.

하루 1회 신청 제한과 5,000,000 누적 캡은 엔티티 스키마 변경 없이 서비스 레이어에서 검증할 정책이다 (엔티티에 이미 필요한 데이터 — `requestedAt`, `requestedAmount` — 가 있으므로 스키마 추가 불필요).

### 3. 신규 DTO — 실시간 호가 (Toss `/api/v1/orderbook`)

`src/main/java/com/papertrade/paper_trading/Dto/OrderBookResponse.java`, `OrderBookResult.java`, `OrderBookLevel.java` (신규, `MarketCalendarResponse` 패턴과 동일한 record 래핑 구조)

```java
public record OrderBookResponse(OrderBookResult result) {}

public record OrderBookResult(
    OffsetDateTime timestamp,
    String currency,
    List<OrderBookLevel> asks,
    List<OrderBookLevel> bids
) {}

public record OrderBookLevel(
    BigDecimal price,
    Long volume
) {}
```

- `price`는 금액이므로 컨벤션에 따라 `BigDecimal`, `volume`은 `StockPrice.volume`과 동일하게 `Long`. Toss 응답은 문자열("72300")이지만 Jackson이 숫자형 필드에 대해 문자열 숫자를 기본적으로 역직렬화 지원하므로 별도 컨버터 불필요.
- Entity로 만들지 않고 DTO로만 유지 — 실시간 호가는 계속 갱신되는 틱 데이터라 relational DB에 영속화하지 않는 기존 원칙(`docs/entity-spec.md`의 Market Calendar DTO 섹션과 `StockPrice` 노트)을 그대로 따름.

### 4. `docs/entity-spec.md` 갱신

- `AccountFundingRequest` 섹션: 제약조건에서 500 단위 문구 제거, Business rules에 "하루 1회 신청 제한(서비스 레벨)", "누적 신청금액 5,000,000 캡", "누적 계산은 마지막 `AccountReset.resetAt` 이후로 스코프" 반영.
- 신규 `AccountReset` 엔티티 섹션 추가 (컬럼 표, 제약, 관계, 초기화 조건: 누적 수익률과 무관하게 언제든 신청 가능).
- Relationship Summary에 `accounts 1 : N account_resets` 추가.
- 신규 "Order Book DTOs" 섹션 추가 (Market Calendar DTOs 섹션과 동일한 형식 — record 구조 + "DB에 저장하지 않고 Redis/캐시 사용" 명시).
- Mermaid ERD에 `ACCOUNT_RESETS` 엔티티/관계 추가.

## 확인이 필요한 가정

- 초기화 신청에는 누적 수익률 조건이 없으며, 계좌 소유자가 원하면 언제든 신청 가능하다고 반영했습니다.
- 초기화 후 `Account.currentRound`는 계속 누적 증가한다고 가정했습니다 (초기화 시 1로 되돌리지 않음). 별도 요구가 없어 기존 필드 의미(누적 라운드 번호)를 그대로 유지했습니다.
- 하루 1회 제한과 5,000,000 캡은 서비스 레이어 검증으로만 계획했고, DB 레벨 제약(부분 유니크 인덱스 등)은 추가하지 않았습니다 — 필요하면 별도로 추가 가능합니다.

## 구현 상태

완료 (구현 및 점검 완료).
