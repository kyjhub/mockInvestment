package com.papertrade.paper_trading.Service;

import java.math.BigDecimal;

public interface CommissionCalculator {

    BigDecimal calculateCommission(BigDecimal price, Long quantity);

    BigDecimal calculateTax(BigDecimal price, Long quantity);
}
