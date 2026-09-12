package com.papertrade.paper_trading.Entity;

import com.papertrade.paper_trading.Enum.LedgerTransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 하나의 경제적 사건. 여기 달린 {@link LedgerEntry}들의 금액 합계는 항상 0이다.
 *
 * <p>append-only다. 기록된 거래는 수정하거나 삭제하지 않는다. 정정이 필요하면 부호를 뒤집은 새 거래를
 * {@link #reversalOf}로 연결해 추가한다.
 */
@Getter
@Entity
@Table(name = "ledger_transactions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", length = 30, nullable = false)
    private LedgerTransactionType transactionType;

    /**
     * 같은 사건을 두 번 기록하지 못하게 하는 키.
     *
     * <p>재처리·재전송이 일상인 경로(스트림 재전달 등)에서 중복 계상을 DB가 막는다.
     * 체결은 {@code FILL:{tradeId}}, 계좌 개설은 {@code OPEN:{accountId}} 형태다.
     */
    @Column(name = "idempotency_key", length = 100, nullable = false, unique = true)
    private String idempotencyKey;

    @CreationTimestamp
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    @Column(length = 255)
    private String description;

    /** 정정 거래면 원본 거래. 원본은 그대로 남는다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reversal_of_id")
    private LedgerTransaction reversalOf;
}
