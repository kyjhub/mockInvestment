package com.papertrade.paper_trading.Entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.UpdateTimestamp;

@Getter
@Entity
@Table(
    name = "holdings",
    uniqueConstraints = @UniqueConstraint(columnNames = {"account_id", "stock_id"})
)
// 보유 수량과 취득원가는 음수가 될 수 없다. Holding.sell()이 이미 막지만,
// 그 검사를 우회하는 경로가 생기면 DB가 마지막으로 거부한다.
@Check(constraints = "quantity >= 0 and total_purchase_amount >= 0")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Holding {

    private static final int MONEY_SCALE = 2;
    private static final int PRICE_SCALE = 4;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_id", nullable = false)
    private Stock stock;

    @Column(nullable = false)
    private Long quantity;

    @Column(name = "average_price", precision = 19, scale = 4, nullable = false)
    private BigDecimal averagePrice;

    @Column(name = "total_purchase_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal totalPurchaseAmount;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static Holding create(Account account, Stock stock) {
        return Holding.builder()
            .account(account)
            .stock(stock)
            .quantity(0L)
            .averagePrice(BigDecimal.ZERO.setScale(PRICE_SCALE, RoundingMode.HALF_UP))
            .totalPurchaseAmount(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP))
            .build();
    }

    public void buy(Long buyQuantity, BigDecimal price) {
        BigDecimal purchaseAmount = price.multiply(BigDecimal.valueOf(buyQuantity))
            .setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        this.quantity += buyQuantity;
        this.totalPurchaseAmount = this.totalPurchaseAmount.add(purchaseAmount);
        this.averagePrice = this.totalPurchaseAmount
            .divide(BigDecimal.valueOf(this.quantity), PRICE_SCALE, RoundingMode.HALF_UP);
    }

    public BigDecimal sell(Long sellQuantity) {
        if (this.quantity < sellQuantity) {
            throw new IllegalArgumentException("보유 수량이 부족합니다.");
        }

        BigDecimal costBasis = this.averagePrice.multiply(BigDecimal.valueOf(sellQuantity))
            .setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        this.quantity -= sellQuantity;
        this.totalPurchaseAmount = this.totalPurchaseAmount.subtract(costBasis);

        if (this.quantity == 0) {
            this.averagePrice = BigDecimal.ZERO.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
            this.totalPurchaseAmount = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }

        return costBasis;
    }
}
