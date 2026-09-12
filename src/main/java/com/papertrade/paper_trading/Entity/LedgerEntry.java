package com.papertrade.paper_trading.Entity;

import com.papertrade.paper_trading.Enum.LedgerAccount;
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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 분개 한 줄. 어느 계좌의 어느 계정과목이 얼마 움직였는가.
 *
 * <p>잔고·보유·실현손익은 모두 이 테이블의 파생값이다. {@code accounts.cash_balance} 같은 컬럼은
 * 읽기 속도를 위한 캐시이며, 대사로 재계산해 검증한다.
 */
@Getter
@Entity
@Table(
    name = "ledger_entries",
    indexes = {
        @Index(name = "idx_ledger_entries_account", columnList = "account_id, ledger_account"),
        @Index(name = "idx_ledger_entries_transaction", columnList = "transaction_id")
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private LedgerTransaction transaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "ledger_account", length = 30, nullable = false)
    private LedgerAccount ledgerAccount;

    /** {@link LedgerAccount#SECURITIES}일 때만 있다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id")
    private Stock stock;

    /** 차변 {@code +}, 대변 {@code −}. */
    @Column(precision = 19, scale = 2, nullable = false)
    private BigDecimal amount;

    /** 수량 이동. {@link LedgerAccount#SECURITIES}일 때만 있다. */
    private Long quantity;

    /**
     * 이 분개 직후 해당 (계좌, 계정과목)의 누적 잔액.
     *
     * <p>유지되는 잔고 캐시가 있는 계정과목에만 채운다 — {@code CASH}는 {@code accounts.cash_balance},
     * {@code SECURITIES}는 {@code holdings.total_purchase_amount}. 나머지는 {@code null}이다.
     *
     * <p>집계로 계산하지 않는다. 분개마다 전체 이력을 {@code SUM}하면 원장이 길어질수록 체결이 느려진다.
     * 대사는 이 값이 아니라 {@code SUM(amount)}으로 하므로 {@code null}이어도 검증에 지장이 없다.
     */
    @Column(name = "balance_after", precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
