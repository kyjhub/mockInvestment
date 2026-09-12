package com.papertrade.paper_trading.Repository;

import com.papertrade.paper_trading.Entity.LedgerTransaction;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, Long> {

    Optional<LedgerTransaction> findByIdempotencyKey(String idempotencyKey);
}
