package com.papertrade.paper_trading.Entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.papertrade.paper_trading.Enum.OrderSide;
import com.papertrade.paper_trading.Enum.OrderStatus;
import com.papertrade.paper_trading.Enum.OrderType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * {@code REJECTED}는 "접수 자체가 무효였다"는 뜻이라 부분 체결과 같이 쓸 수 없다.
 * 체결 이력이 있는 주문은 잔량 취소로 종료하되 사유와 체결 수량은 보존해야 한다.
 */
class OrderRejectionTests {

    @Test
    void pendingOrderBecomesRejected() {
        Order order = order();

        order.reject("주문 가능 금액이 부족합니다.");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getRejectedAt()).isNotNull();
        assertThat(order.getCanceledAt()).isNull();
        assertThat(order.getRejectReason()).isEqualTo("주문 가능 금액이 부족합니다.");
    }

    @Test
    void partiallyFilledOrderIsCanceledWithTheReasonPreserved() {
        Order order = order();
        order.fill(3L);

        order.reject("보유 수량이 부족합니다.");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(order.getCanceledAt()).isNotNull();
        assertThat(order.getRejectedAt()).isNull();
        assertThat(order.getRejectReason()).isEqualTo("보유 수량이 부족합니다.");
        assertThat(order.getFilledQuantity()).isEqualTo(3L);
    }

    @Test
    void terminatedOrderCannotBeRejectedAgain() {
        Order order = order();
        order.cancel();

        assertThatThrownBy(() -> order.reject("보유 수량이 부족합니다."))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("이미 종료된 주문은 거절할 수 없습니다.");
    }

    private Order order() {
        return Order.create(
            null,
            null,
            null,
            OrderSide.BUY,
            OrderType.LIMIT,
            new BigDecimal("100.0000"),
            10L,
            new BigDecimal("100.0000")
        );
    }
}
