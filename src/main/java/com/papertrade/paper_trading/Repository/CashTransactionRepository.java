package com.papertrade.paper_trading.Repository;

import com.papertrade.paper_trading.Entity.CashTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CashTransactionRepository extends JpaRepository<CashTransaction, Long> {
}
