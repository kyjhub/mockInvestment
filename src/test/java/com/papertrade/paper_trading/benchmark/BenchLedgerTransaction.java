package com.papertrade.paper_trading.benchmark;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * {@code LedgerTransaction}의 <b>옛 구조</b>({@code IDENTITY}) 재현. 벤치마크 전용이다.
 *
 * <p>운영 엔티티를 건드리지 않고 "ID 전략을 바꾸면 얼마나 빨라지는가"를 재기 위해 존재한다.
 * 같은 테이블에 엔티티 둘을 매핑하면 Hibernate가 혼란스러워지므로 별도 표를 쓴다. 컬럼 구성과
 * 인덱스는 원본과 같게 맞췄다.
 *
 * <p>{@code allocationSize = 50}이면 Hibernate가 pooled optimizer를 써서 시퀀스 왕복을 50건에
 * 한 번으로 줄인다. 이게 {@code IDENTITY}에는 없는 이점이다.
 */
@Entity
@Table(name = "bench_ledger_transactions")
public class BenchLedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_type", length = 30, nullable = false)
    private String transactionType;

    @Column(name = "idempotency_key", length = 100, nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(length = 255)
    private String description;

    protected BenchLedgerTransaction() {
    }

    BenchLedgerTransaction(String transactionType, String idempotencyKey, String description) {
        this.transactionType = transactionType;
        this.idempotencyKey = idempotencyKey;
        this.description = description;
        this.occurredAt = LocalDateTime.now();
    }

    Long getId() {
        return id;
    }
}
