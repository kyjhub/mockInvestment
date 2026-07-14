package com.papertrade.paper_trading.Entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "leaderboard_rankings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
// Daily batch materialized ranking for leaderboard read optimization.
public class LeaderboardRanking {

    @Id
    @Column(name = "account_id")
    private Long accountId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "rank_by_return_rate", nullable = false)
    private Integer rankByReturnRate;

    @Column(name = "rank_by_profit_amount", nullable = false)
    private Integer rankByProfitAmount;

    @Column(name = "cumulative_return_rate", precision = 10, scale = 6, nullable = false)
    private BigDecimal cumulativeReturnRate;

    @Column(name = "cumulative_profit_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal cumulativeProfitAmount;

    @Column(name = "calculated_at", nullable = false)
    private LocalDateTime calculatedAt;
}
