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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.CreationTimestamp;

@Getter
@Entity
@Table(
    name = "account_funding_requests",
    uniqueConstraints = @UniqueConstraint(columnNames = {"account_id", "round_no"})
)
@Check(constraints = "round_no > 0 and requested_amount >= 1000 and requested_amount <= 100000")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class AccountFundingRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    @Column(name = "requested_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal requestedAmount;

    @Column(name = "asset_before_reset", precision = 19, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal assetBeforeReset = BigDecimal.ZERO;

    @Column(name = "profit_before_reset", precision = 19, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal profitBeforeReset = BigDecimal.ZERO;

    @Column(name = "return_rate_before_reset", precision = 10, scale = 6, nullable = false)
    @Builder.Default
    private BigDecimal returnRateBeforeReset = BigDecimal.ZERO;

    @CreationTimestamp
    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;
}
