package com.papertrade.paper_trading.Repository;

import com.papertrade.paper_trading.Entity.Holding;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface HoldingRepository extends JpaRepository<Holding, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Holding> findByAccountIdAndStockId(Long accountId, Long stockId);
}
