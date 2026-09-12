package com.papertrade.paper_trading.Dto;

import java.math.BigDecimal;

/**
 * 잔고 화면.
 *
 * <p>예수금과 주문가능금액은 다른 값이다. 미체결 주문은 예수금을 깎지 않는다 — 돈이 나간 게 아니라
 * 묶여 있을 뿐이다. 사용자가 쓸 수 있는 돈은 구속액을 뺀 {@code orderableAmount}이며 저장하지 않고
 * 매번 계산한다.
 *
 * @param unrealizedProfitRate <b>평가손익률</b>. 분모가 총매입금액이라 예수금을 포함하지 않는다.
 *     현업 증권사 잔고 화면과 같은 기준이며, 리더보드의 "수익률"(예수금 포함)과는 다른 값이다.
 *     이름을 구분하지 않으면 사용자가 버그로 신고한다.
 * @param stockEvaluation 시세를 구하지 못하면 {@code null}이다. 일부 종목만 반영한 값을 보여주는
 *     것보다 "계산 중"이 낫다. 이때 {@code unrealizedProfit}·{@code unrealizedProfitRate}·
 *     {@code totalAsset}도 함께 {@code null}이다.
 */
public record AccountBalanceResponse(
    String accountNumber,
    BigDecimal cashBalance,
    BigDecimal reservedCash,
    BigDecimal orderableAmount,
    BigDecimal realizedProfit,
    BigDecimal totalPurchaseAmount,
    BigDecimal stockEvaluation,
    BigDecimal unrealizedProfit,
    BigDecimal unrealizedProfitRate,
    BigDecimal totalAsset
) {
}
