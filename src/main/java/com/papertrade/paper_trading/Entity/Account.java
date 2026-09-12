package com.papertrade.paper_trading.Entity;

import com.papertrade.paper_trading.Enum.AccountStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
@Table(name = "accounts")
// 애플리케이션 캡이 유일한 방어선이면, 캡 계산이 깨지는 순간 음수 잔고가 조용히 저장된다.
// realized_profit은 손실이면 음수가 정상이므로 제약하지 않는다.
@Check(constraints = "current_round > 0 and cash_balance >= 0")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "account_number", length = 30, nullable = false, unique = true)
    private String accountNumber;

    @Column(name = "cash_balance", precision = 19, scale = 2, nullable = false)
    private BigDecimal cashBalance;

    @Column(name = "initial_balance", precision = 19, scale = 2, nullable = false)
    private BigDecimal initialBalance;

    @Column(name = "total_asset_value", precision = 19, scale = 2, nullable = false)
    private BigDecimal totalAssetValue;

    @Column(name = "current_round", nullable = false)
    @Builder.Default
    private Integer currentRound = 1;

    @Column(name = "realized_profit", precision = 19, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal realizedProfit = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private AccountStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void debitCash(BigDecimal amount) {
        this.cashBalance = this.cashBalance.subtract(amount);
    }

    public void creditCash(BigDecimal amount) {
        this.cashBalance = this.cashBalance.add(amount);
    }

    public void addRealizedProfit(BigDecimal profitAmount) {
        this.realizedProfit = this.realizedProfit.add(profitAmount);
    }
}
