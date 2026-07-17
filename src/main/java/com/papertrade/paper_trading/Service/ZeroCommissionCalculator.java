package com.papertrade.paper_trading.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public class ZeroCommissionCalculator implements CommissionCalculator {

    private static final int MONEY_SCALE = 2;
    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

    @Override
    public BigDecimal calculateCommission(BigDecimal price, Long quantity) {
        return ZERO_MONEY;
    }

    @Override
    public BigDecimal calculateTax(BigDecimal price, Long quantity) {
        return ZERO_MONEY;
    }
}
