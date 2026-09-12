package com.papertrade.paper_trading.Dto;

import java.math.BigDecimal;

/**
 * 예수금과 주문가능금액은 다른 값이다.
 *
 * <p>미체결 주문은 예수금을 깎지 않는다 — 돈이 나간 게 아니라 묶여 있을 뿐이다. 사용자가 보는
 * "쓸 수 있는 돈"은 그 구속액을 뺀 {@code orderableAmount}이며, 저장하지 않고 매번 계산한다.
 */
public record AccountBalanceResponse(
    String accountNumber,
    BigDecimal cashBalance,
    BigDecimal reservedCash,
    BigDecimal orderableAmount,
    BigDecimal realizedProfit
) {
}
