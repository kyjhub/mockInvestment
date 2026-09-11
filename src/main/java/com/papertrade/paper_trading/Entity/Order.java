package com.papertrade.paper_trading.Entity;

import com.papertrade.paper_trading.Enum.OrderSide;
import com.papertrade.paper_trading.Enum.OrderStatus;
import com.papertrade.paper_trading.Enum.OrderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Getter
@Entity
@Table(
    name = "orders",
    uniqueConstraints = @UniqueConstraint(columnNames = {"account_id", "client_order_id"})
)
@Check(constraints = "order_quantity > 0 and filled_quantity >= 0 and remaining_quantity >= 0 and filled_quantity + remaining_quantity = order_quantity")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Order {

    private static final List<OrderStatus> CANCELABLE_STATUSES = List.of(
        OrderStatus.PENDING,
        OrderStatus.PARTIALLY_FILLED
    );

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id", nullable = false)
    private Stock stock;

    @Column(name = "client_order_id", length = 36)
    private String clientOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_side", length = 10, nullable = false)
    private OrderSide orderSide;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", length = 20, nullable = false)
    private OrderType orderType;

    @Column(name = "order_price", precision = 19, scale = 4)
    private BigDecimal orderPrice;

    @Column(name = "order_quantity", nullable = false)
    private Long orderQuantity;

    @Column(name = "filled_quantity", nullable = false)
    @Builder.Default
    private Long filledQuantity = 0L;

    @Column(name = "remaining_quantity", nullable = false)
    private Long remainingQuantity;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private OrderStatus status;

    @CreationTimestamp
    @Column(name = "submitted_at", nullable = false, updatable = false)
    private LocalDateTime submittedAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    @Column(name = "reject_reason", length = 255)
    private String rejectReason;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static Order create(
        Account account,
        Stock stock,
        String clientOrderId,
        OrderSide orderSide,
        OrderType orderType,
        BigDecimal orderPrice,
        Long orderQuantity
    ) {
        return Order.builder()
            .account(account)
            .stock(stock)
            .clientOrderId(clientOrderId)
            .orderSide(orderSide)
            .orderType(orderType)
            .orderPrice(orderPrice)
            .orderQuantity(orderQuantity)
            .filledQuantity(0L)
            .remainingQuantity(orderQuantity)
            .status(OrderStatus.PENDING)
            .build();
    }

    public void fill(Long quantity) {
        this.filledQuantity += quantity;
        this.remainingQuantity -= quantity;
        this.status = this.remainingQuantity == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }

    public void waitRemainingAt(BigDecimal waitingPrice) {
        if (this.remainingQuantity > 0) {
            this.orderPrice = waitingPrice;
        }
    }

    public void cancel() {
        if (!CANCELABLE_STATUSES.contains(this.status)) {
            throw new IllegalArgumentException("이미 종료된 주문은 취소할 수 없습니다.");
        }
        this.status = OrderStatus.CANCELED;
        this.canceledAt = LocalDateTime.now();
    }

    /**
     * 계좌 조건(잔고·보유 수량)으로 더 이상 체결될 수 없는 주문을 종료한다.
     *
     * <p>유동성이 없어서 체결이 안 된 주문에는 쓰지 않는다. 그건 계좌 문제가 아니라
     * 시장 상태이므로 계속 대기해야 한다.
     *
     * <p>체결 이력이 있으면 {@code REJECTED}가 아니라 잔량 취소({@code CANCELED})로 종료한다.
     * {@code REJECTED}는 접수 자체가 무효였다는 뜻이라 부분 체결과 같이 쓸 수 없다.
     */
    public void reject(String reason) {
        if (!CANCELABLE_STATUSES.contains(this.status)) {
            throw new IllegalArgumentException("이미 종료된 주문은 거절할 수 없습니다.");
        }
        this.rejectReason = reason;
        if (this.filledQuantity > 0) {
            this.status = OrderStatus.CANCELED;
            this.canceledAt = LocalDateTime.now();
            return;
        }
        this.status = OrderStatus.REJECTED;
        this.rejectedAt = LocalDateTime.now();
    }
}
