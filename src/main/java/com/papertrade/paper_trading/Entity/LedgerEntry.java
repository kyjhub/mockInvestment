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
import jakarta.persistence.SequenceGenerator;
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

    /**
     * {@code SEQUENCE}를 쓰는 이유는 <b>JDBC 배치</b> 때문이다.
     *
     * <p>{@code IDENTITY}면 Hibernate가 생성된 키를 INSERT 직후 받아야 해서 INSERT를 모을 수 없다.
     * 체결 1건이 원장에 6행을 쓰므로 그대로 6번의 DB 왕복이 된다. 측정해 보니 체결 1건의 15.9%를
     * 여기에 쓰고 있었다(docs/load-test-candidates.md ④).
     *
     * <p>{@code allocationSize = 50}이면 pooled optimizer가 시퀀스 왕복을 50건에 한 번으로 줄인다.
     * <b>DB 시퀀스의 increment와 반드시 같아야 한다.</b> 어긋나면 id가 겹친다.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ledger_entries_seq")
    @SequenceGenerator(name = "ledger_entries_seq", sequenceName = "ledger_entries_seq", allocationSize = 50)
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
