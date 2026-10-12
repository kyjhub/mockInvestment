package com.papertrade.paper_trading.Service;

import java.math.BigDecimal;

/**
 * 체결금액에 매기는 수수료·세금. 반올림하지 않은 값을 돌려준다.
 *
 * <p>인자가 체결 한 건이 아니라 <b>체결금액</b>인 이유는, 시중 증권사처럼 수수료가 체결금액에만 의존해야
 * 하기 때문이다. 같은 금액이면 한 번에 체결되든 나눠 체결되든 수수료가 같다. 그 보장과 반올림은
 * {@link TradingFees}가 주문의 누적 체결금액으로 계산해 지킨다.
 */
public interface CommissionCalculator {

    BigDecimal commission(BigDecimal tradeAmount);

    BigDecimal tax(BigDecimal tradeAmount);
}
