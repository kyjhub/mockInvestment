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
@Table(name = "executions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Execution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "execution_price", precision = 19, scale = 4, nullable = false)
    private BigDecimal executionPrice;

    @Column(name = "execution_quantity", nullable = false)
    private Long executionQuantity;

    /**
     * 이 체결이 만든 원장 거래.
     *
     * <p>내부 체결은 매수·매도 {@code Execution} 두 건이 같은 거래를 가리킨다. 같은 사건이라는 사실이
     * 데이터에 표현되어야 체결 내역과 원장을 대사할 수 있다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ledger_transaction_id")
    private LedgerTransaction ledgerTransaction;

    /** 체결 사건의 고유 키. 원장 거래의 멱등키 {@code FILL:{tradeId}}와 같은 값이다. */
    @Column(name = "trade_id", length = 36)
    private String tradeId;

    /**
     * 수수료. <b>표시용 사본이며 계산에 쓰지 않는다.</b>
     *
     * <p>잔고·손익의 근거는 언제나 원장의 {@code FEE} 분개다. 이 값은 체결 내역 화면이 원장 조인
     * 없이 읽을 수 있도록 둔 비정규화이고, 대사 대상이다.
     */
    @Column(precision = 19, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal commission = BigDecimal.ZERO;

    /** 거래세. {@link #commission}과 같이 표시용 사본이며 계산에 쓰지 않는다. */
    @Column(precision = 19, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal tax = BigDecimal.ZERO;

    @CreationTimestamp
    @Column(name = "executed_at", nullable = false, updatable = false)
    private LocalDateTime executedAt;
}
