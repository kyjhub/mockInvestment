package com.papertrade.paper_trading.Dto;

import java.math.BigDecimal;

/**
 * 시가 평가 결과.
 *
 * <p>평가액은 <b>원장 파생이 아니다.</b> 시가는 외부에서 온 값이고 같은 보유라도 어제와 오늘이 다르다.
 * 그래서 {@code stockEvaluation}·{@code unrealizedProfit}·{@code totalAsset}은 대사 대상이 아니다.
 * 반면 입력인 {@code totalPurchaseAmount}는 원장에서 재계산해 검증할 수 있다.
 *
 * @param unrealizedProfitRate 평가손익률. 분모가 총매입금액이라 <b>예수금을 포함하지 않는다</b>.
 *     현업 증권사 잔고 화면과 같은 기준이다. 보유가 없으면 {@code null}이다 — 0%가 아니라
 *     수익률이라는 개념 자체가 없다.
 */
public record AccountValuation(
    BigDecimal totalPurchaseAmount,
    BigDecimal stockEvaluation,
    BigDecimal unrealizedProfit,
    BigDecimal unrealizedProfitRate,
    BigDecimal totalAsset
) {
}
