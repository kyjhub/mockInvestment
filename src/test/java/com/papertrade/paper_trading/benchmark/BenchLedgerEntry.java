package com.papertrade.paper_trading.benchmark;

import com.papertrade.paper_trading.Entity.Account;
import com.papertrade.paper_trading.Entity.Stock;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** {@code LedgerEntry}를 {@code SEQUENCE}로 바꾼 형태. 벤치마크 전용이다. */
@Entity
@Table(
    name = "bench_ledger_entries",
    indexes = {
        @Index(name = "idx_bench_ledger_entries_account", columnList = "account_id, ledger_account"),
        @Index(name = "idx_bench_ledger_entries_transaction", columnList = "transaction_id")
    }
)
public class BenchLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "bench_ledger_entry_seq")
    @SequenceGenerator(name = "bench_ledger_entry_seq", sequenceName = "bench_ledger_entry_seq",
        allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private BenchLedgerTransaction transaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "ledger_account", length = 30, nullable = false)
    private String ledgerAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id")
    private Stock stock;

    @Column(precision = 19, scale = 2, nullable = false)
    private BigDecimal amount;

    private Long quantity;

    @Column(name = "balance_after", precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected BenchLedgerEntry() {
    }

    BenchLedgerEntry(BenchLedgerTransaction transaction, Account account, String ledgerAccount,
        Stock stock, BigDecimal amount, Long quantity, BigDecimal balanceAfter) {
        this.transaction = transaction;
        this.account = account;
        this.ledgerAccount = ledgerAccount;
        this.stock = stock;
        this.amount = amount;
        this.quantity = quantity;
        this.balanceAfter = balanceAfter;
        this.createdAt = LocalDateTime.now();
    }
}
