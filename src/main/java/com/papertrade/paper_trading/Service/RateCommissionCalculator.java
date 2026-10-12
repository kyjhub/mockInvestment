package com.papertrade.paper_trading.Service;

import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 체결금액에 요율을 곱한다. 운영은 두 요율 모두 0이다.
 *
 * <p>요율을 설정으로 둔 이유는 수수료를 나중에 도입할 때 코드를 바꾸지 않기 위해서다. 체결·구속·잔고 캡은
 * 이미 수수료가 있다고 가정하고 동작한다.
 */
@Component
public class RateCommissionCalculator implements CommissionCalculator {

    private final BigDecimal commissionRate;
    private final BigDecimal taxRate;

    public RateCommissionCalculator(
        @Value("${order.fee.commission-rate:0}") BigDecimal commissionRate,
        @Value("${order.fee.tax-rate:0}") BigDecimal taxRate
    ) {
        this.commissionRate = commissionRate;
        this.taxRate = taxRate;
    }

    /** 수수료 없는 계산기. 테스트와 운영 기본값이 같은 규칙을 쓰도록 이 클래스로 만든다. */
    public static RateCommissionCalculator free() {
        return new RateCommissionCalculator(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Override
    public BigDecimal commission(BigDecimal tradeAmount) {
        return tradeAmount.multiply(commissionRate);
    }

    @Override
    public BigDecimal tax(BigDecimal tradeAmount) {
        return tradeAmount.multiply(taxRate);
    }
}
