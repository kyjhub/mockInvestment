package com.papertrade.paper_trading.Service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.papertrade.paper_trading.Repository.OrderRepository;
import com.papertrade.paper_trading.Repository.OrderRepository.CashReservation;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 구속액은 체결대금에 수수료·세금 예상액을 더한 값이다. 이미 걸린 주문들의 수수료가 빠지면
 * 그만큼 예수금을 넘는 주문이 접수된다.
 */
class CashReservationCalculatorTests {

    private static final Long ACCOUNT_ID = 1L;

    private final OrderRepository orderRepository = mock(OrderRepository.class);

    @Test
    void reservesOnlyTheTradeAmountWhenThereAreNoFees() {
        givenOpenBuys(new CashReservation(new BigDecimal("100.0000"), 3L));

        CashReservationCalculator calculator = new CashReservationCalculator(orderRepository, new ZeroCommissionCalculator());

        assertThat(calculator.reservedCash(ACCOUNT_ID)).isEqualByComparingTo("300.00");
    }

    @Test
    void includesEachOpenOrdersFeesInTheReservation() {
        givenOpenBuys(
            new CashReservation(new BigDecimal("100.0000"), 3L),
            new CashReservation(new BigDecimal("50.0000"), 2L)
        );

        CashReservationCalculator calculator = new CashReservationCalculator(orderRepository, fees("0.01", "0"));

        // (300 + 3) + (100 + 1)
        assertThat(calculator.reservedCash(ACCOUNT_ID)).isEqualByComparingTo("404.00");
    }

    @Test
    void appliesNonLinearFeesPerOrder() {
        // 최소 수수료는 주문마다 붙는다. 합계 금액에 한 번 매기는 것과 다르다.
        givenOpenBuys(
            new CashReservation(new BigDecimal("10.0000"), 1L),
            new CashReservation(new BigDecimal("10.0000"), 1L)
        );

        CashReservationCalculator calculator = new CashReservationCalculator(orderRepository, fees("0.001", "5.00"));

        assertThat(calculator.reservedCash(ACCOUNT_ID)).isEqualByComparingTo("30.00");
    }

    @Test
    void requiredCashForANewOrderUsesTheSameRule() {
        CashReservationCalculator calculator = new CashReservationCalculator(orderRepository, fees("0.01", "0"));

        assertThat(calculator.requiredCash(new BigDecimal("100.0000"), 10L)).isEqualByComparingTo("1010.00");
    }

    @Test
    void isZeroWithoutOpenBuys() {
        givenOpenBuys();

        CashReservationCalculator calculator = new CashReservationCalculator(orderRepository, fees("0.01", "0"));

        assertThat(calculator.reservedCash(ACCOUNT_ID)).isEqualByComparingTo("0.00");
    }

    private void givenOpenBuys(CashReservation... reservations) {
        when(orderRepository.findCashReservations(eq(ACCOUNT_ID), anyCollection())).thenReturn(List.of(reservations));
    }

    private CommissionCalculator fees(String commissionRate, String minimumCommission) {
        BigDecimal rate = new BigDecimal(commissionRate);
        BigDecimal minimum = new BigDecimal(minimumCommission);
        return new CommissionCalculator() {
            @Override
            public BigDecimal calculateCommission(BigDecimal price, Long quantity) {
                return price.multiply(BigDecimal.valueOf(quantity)).multiply(rate).max(minimum);
            }

            @Override
            public BigDecimal calculateTax(BigDecimal price, Long quantity) {
                return BigDecimal.ZERO;
            }
        };
    }
}
