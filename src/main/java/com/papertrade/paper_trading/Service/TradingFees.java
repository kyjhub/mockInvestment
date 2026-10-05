package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Entity.Order;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 주문에 부과할 수수료·세금. 반올림과 부분 체결 규칙을 한곳에 둔다.
 *
 * <p><b>같은 체결금액이면 같은 수수료다.</b> 체결 건마다 수수료를 매기고 그때마다 센트로 반올림하면,
 * 30달러를 한 번에 체결할 때(0.075 → 0.08)와 10달러씩 세 번 체결할 때(0.025 → 0.03, 합 0.09)가 달라진다.
 * 그래서 주문의 누적 체결금액에 대한 수수료를 구하고, 이미 부과한 만큼을 뺀 차액을 이번 체결에 매긴다.
 * 주문 전체의 수수료는 분할 방식과 무관하게 {@code 반올림(총 체결금액 × 요율)}이 된다.
 *
 * <p>센트 미만은 반올림(HALF_UP)한다. 원장은 센트 단위로 기록된다.
 */
@Component
@RequiredArgsConstructor
public class TradingFees {

    private static final int MONEY_SCALE = 2;

    private final CommissionCalculator commissionCalculator;

    /** 이 체결금액 전체에 매기는 수수료·세금. */
    public Fees forAmount(BigDecimal tradeAmount) {
        return new Fees(
            money(commissionCalculator.commission(tradeAmount)),
            money(commissionCalculator.tax(tradeAmount))
        );
    }

    /**
     * 이 주문이 {@code fillAmount}만큼 더 체결될 때 이번 체결에 매길 수수료·세금.
     * {@link Order#fill}로 누적값을 갱신하기 <b>전에</b> 호출해야 한다.
     */
    public Fees nextFill(Order order, BigDecimal fillAmount) {
        return outstanding(order.getFilledAmount().add(fillAmount), order.getChargedCommission(), order.getChargedTax());
    }

    /**
     * 누적 체결금액이 {@code totalAmount}가 될 때까지 앞으로 더 매길 수수료·세금.
     * 부분 체결된 주문의 남은 수량이 묶어야 할 수수료도 이것으로 구한다.
     */
    public Fees outstanding(BigDecimal totalAmount, BigDecimal chargedCommission, BigDecimal chargedTax) {
        Fees total = forAmount(totalAmount);
        return new Fees(
            total.commission().subtract(chargedCommission).max(money(BigDecimal.ZERO)),
            total.tax().subtract(chargedTax).max(money(BigDecimal.ZERO))
        );
    }

    private BigDecimal money(BigDecimal amount) {
        return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public record Fees(BigDecimal commission, BigDecimal tax) {

        public BigDecimal total() {
            return commission.add(tax);
        }
    }
}
