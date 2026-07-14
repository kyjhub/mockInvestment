package com.papertrade.paper_trading.Entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "stock_korean_market_details")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class StockKoreanMarketDetail {

    @Id
    @Column(name = "stock_id")
    private Long stockId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "stock_id", nullable = false)
    private Stock stock;

    @Column(name = "liquidation_trading", nullable = false)
    private Boolean liquidationTrading;

    @Column(name = "nxt_supported", nullable = false)
    private Boolean nxtSupported;

    @Column(name = "krx_trading_suspended", nullable = false)
    private Boolean krxTradingSuspended;

    @Column(name = "nxt_trading_suspended", nullable = false)
    private Boolean nxtTradingSuspended;
}
