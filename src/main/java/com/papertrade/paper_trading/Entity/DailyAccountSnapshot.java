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
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Getter
@Entity
@Table(
    name = "daily_account_snapshots",
    uniqueConstraints = @UniqueConstraint(columnNames = {"account_id", "snapshot_date"})
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class DailyAccountSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "cash_balance", precision = 19, scale = 2, nullable = false)
    private BigDecimal cashBalance;

    @Column(name = "stock_evaluation", precision = 19, scale = 2, nullable = false)
    private BigDecimal stockEvaluation;

    @Column(name = "total_asset", precision = 19, scale = 2, nullable = false)
    private BigDecimal totalAsset;

    @Column(name = "realized_profit", precision = 19, scale = 2, nullable = false)
    private BigDecimal realizedProfit;

    @Column(name = "unrealized_profit", precision = 19, scale = 2, nullable = false)
    private BigDecimal unrealizedProfit;

    @Column(name = "return_rate", precision = 10, scale = 6, nullable = false)
    private BigDecimal returnRate;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
