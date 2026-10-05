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
import jakarta.persistence.Index;
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
    uniqueConstraints = @UniqueConstraint(columnNames = {"account_id", "client_order_id"}),
    // 주문 접수마다 이 계좌의 미체결 구속액을 집계한다. 가용잔고를 컬럼으로 들고 있지 않기 때문이다.
    indexes = @Index(name = "idx_orders_account_side_status", columnList = "account_id, order_side, status")
)
@Check(constraints = "order_quantity > 0 and filled_quantity >= 0 and remaining_quantity >= 0 and filled_quantity + remaining_quantity = order_quantity")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Order {

    private static final List<OrderStatus> CANCELABLE_STATUSES = List.of(
        OrderStatus.AWAITING_PRICE,
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

    /**
     * 이 주문이 예수금에서 구속하는 1주당 금액. 매수 주문에만 있다.
     *
     * <p>지정가는 주문가격, 시장가는 <b>접수 시점의 당일 고가</b>다. 접수 때 한 번 정하고 이후 바뀌지 않는다.
     *
     * <p>파생값을 저장하는 것처럼 보이지만 성격이 다르다. 주문의 불변 속성이라 드리프트가 생길 수 없고,
     * 덕분에 구속액 집계가 외부 시세 조회 없이 {@code orders} 한 테이블에서 순수 SQL로 끝난다.
     * 계좌 row lock을 쥔 채 Toss를 기다리는 일이 없어야 하므로 이 점이 중요하다.
     */
    @Column(name = "reserved_unit_price", precision = 19, scale = 4)
    private BigDecimal reservedUnitPrice;

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

    /**
     * 주문이 종료된 사유. 거절·잔량 취소·만료가 공유한다.
     *
     * <p>거절 전용이 아니라 "종료 사유"인 이유는, 체결 이력이 있는 주문의 거절이 잔량 취소로,
     * 거래일 종료 미체결이 만료로 끝나기 때문이다. 셋 다 사용자에게 이유를 알려야 한다.
     */
    @Column(name = "close_reason", length = 255)
    private String closeReason;

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
        Long orderQuantity,
        BigDecimal reservedUnitPrice
    ) {
        return Order.builder()
            .account(account)
            .stock(stock)
            .clientOrderId(clientOrderId)
            .orderSide(orderSide)
            .orderType(orderType)
            .orderPrice(orderPrice)
            .orderQuantity(orderQuantity)
            .reservedUnitPrice(reservedUnitPrice)
            .filledQuantity(0L)
            .remainingQuantity(orderQuantity)
            .status(OrderStatus.PENDING)
            .build();
    }

    /**
     * 현재가를 확보하지 못한 채 접수하는 주문. 접수 검증이 끝날 때까지 매칭에 쓰지 않는다.
     *
     * <p>시장가는 주문가격을 아직 정하지 못했으므로 {@code orderPrice}가 {@code null}이다. 매수라면
     * 구속 단가도 {@code null}이라 구속액이 0이고, 주문가능금액 검증은 {@link #confirmPrice}할 때 한다.
     */
    public static Order createAwaitingPrice(
        Account account,
        Stock stock,
        String clientOrderId,
        OrderSide orderSide,
        OrderType orderType,
        BigDecimal orderPrice,
        Long orderQuantity,
        BigDecimal reservedUnitPrice
    ) {
        Order order = create(
            account, stock, clientOrderId, orderSide, orderType, orderPrice, orderQuantity, reservedUnitPrice);
        order.status = OrderStatus.AWAITING_PRICE;
        return order;
    }

    /** 접수 검증을 마치고 매칭 대상으로 넘긴다. 시장가는 여기서 주문가격과 구속 단가가 정해진다. */
    public void confirmPrice(BigDecimal orderPrice, BigDecimal reservedUnitPrice) {
        if (this.status != OrderStatus.AWAITING_PRICE) {
            throw new IllegalArgumentException("접수 검증 대기 중인 주문이 아닙니다.");
        }
        this.orderPrice = orderPrice;
        this.reservedUnitPrice = reservedUnitPrice;
        this.status = OrderStatus.PENDING;
    }

    public void fill(Long quantity) {
        this.filledQuantity += quantity;
        this.remainingQuantity -= quantity;
        this.status = this.remainingQuantity == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }

    public void cancel() {
        if (!CANCELABLE_STATUSES.contains(this.status)) {
            throw new IllegalArgumentException("이미 종료된 주문은 취소할 수 없습니다.");
        }
        this.status = OrderStatus.CANCELED;
        this.canceledAt = LocalDateTime.now();
    }

    /**
     * 당일 유효 주문을 거래일 종료로 실효시킨다.
     *
     * <p>체결 이력이 있어도 {@code EXPIRED}다. 거절과 달리 만료는 "접수가 무효였다"는 뜻이 아니라
     * "유효기간이 끝났다"는 뜻이라, 부분 체결과 모순되지 않는다.
     */
    public void expire(String reason) {
        if (!CANCELABLE_STATUSES.contains(this.status)) {
            throw new IllegalArgumentException("이미 종료된 주문은 만료시킬 수 없습니다.");
        }
        this.status = OrderStatus.EXPIRED;
        this.canceledAt = LocalDateTime.now();
        this.closeReason = reason;
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
        this.closeReason = reason;
        if (this.filledQuantity > 0) {
            this.status = OrderStatus.CANCELED;
            this.canceledAt = LocalDateTime.now();
            return;
        }
        this.status = OrderStatus.REJECTED;
        this.rejectedAt = LocalDateTime.now();
    }
}
