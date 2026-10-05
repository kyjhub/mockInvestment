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
 * 구속액은 남은 수량의 체결대금에 앞으로 더 매길 수수료·세금을 더한 값이다. 이미 걸린 주문들의 수수료가
 * 빠지면 그만큼 예수금을 넘는 주문이 접수된다.
 */
class CashReservationCalculatorTests {

    private static final Long ACCOUNT_ID = 1L;
    private static final BigDecimal NOTHING = BigDecimal.ZERO;

    private final OrderRepository orderRepository = mock(OrderRepository.class);

    @Test
    void reservesOnlyTheTradeAmountWhenThereAreNoFees() {
        givenOpenBuys(unfilled("100.0000", 3L));

        assertThat(calculator("0").reservedCash(ACCOUNT_ID)).isEqualByComparingTo("300.00");
    }

    @Test
    void includesEachOpenOrdersFeesInTheReservation() {
        givenOpenBuys(unfilled("100.0000", 3L), unfilled("50.0000", 2L));

        // (300 + 3) + (100 + 1)
        assertThat(calculator("0.01").reservedCash(ACCOUNT_ID)).isEqualByComparingTo("404.00");
    }

    @Test
    void partiallyFilledOrderReservesOnlyTheFeeItHasNotBeenChargedYet() {
        // 0.25%, 10달러 × 3주 주문 중 1주 체결. 1주분 수수료 0.025 → 0.03이 이미 부과됐다.
        // 다 체결되면 주문 전체 수수료는 30 × 0.25% = 0.075 → 0.08이므로 남은 2주는 0.05만 더 묶는다.
        // 남은 20달러의 수수료를 따로 반올림하면 0.05지만, 이미 부과한 쪽과 합쳐 0.08을 넘지 않는 것이 핵심이다.
        givenOpenBuys(new CashReservation(
            new BigDecimal("10.0000"), 2L, new BigDecimal("10.0000"), new BigDecimal("0.03"), NOTHING));

        assertThat(calculator("0.0025").reservedCash(ACCOUNT_ID)).isEqualByComparingTo("20.05");
    }

    @Test
    void requiredCashForANewOrderUsesTheSameRule() {
        assertThat(calculator("0.01").requiredCash(new BigDecimal("100.0000"), 10L)).isEqualByComparingTo("1010.00");
    }

    @Test
    void isZeroWithoutOpenBuys() {
        givenOpenBuys();

        assertThat(calculator("0.01").reservedCash(ACCOUNT_ID)).isEqualByComparingTo("0.00");
    }

    private CashReservationCalculator calculator(String commissionRate) {
        return new CashReservationCalculator(
            orderRepository,
            new TradingFees(new RateCommissionCalculator(new BigDecimal(commissionRate), BigDecimal.ZERO)));
    }

    private CashReservation unfilled(String unitPrice, long quantity) {
        return new CashReservation(new BigDecimal(unitPrice), quantity, NOTHING, NOTHING, NOTHING);
    }

    private void givenOpenBuys(CashReservation... reservations) {
        when(orderRepository.findCashReservations(eq(ACCOUNT_ID), anyCollection())).thenReturn(List.of(reservations));
    }
}
