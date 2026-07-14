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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Getter
@Entity
@Table(name = "account_resets")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class AccountReset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "cumulative_return_rate_before_reset", precision = 10, scale = 6, nullable = false)
    private BigDecimal cumulativeReturnRateBeforeReset;

    @Column(name = "cumulative_profit_amount_before_reset", precision = 19, scale = 2, nullable = false)
    private BigDecimal cumulativeProfitAmountBeforeReset;

    @Column(name = "cumulative_requested_amount_before_reset", precision = 19, scale = 2, nullable = false)
    private BigDecimal cumulativeRequestedAmountBeforeReset;

    @CreationTimestamp
    @Column(name = "reset_at", nullable = false, updatable = false)
    private LocalDateTime resetAt;
}
