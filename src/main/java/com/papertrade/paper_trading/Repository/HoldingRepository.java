package com.papertrade.paper_trading.Repository;

import com.papertrade.paper_trading.Entity.Holding;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HoldingRepository extends JpaRepository<Holding, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Holding> findByAccountIdAndStockId(Long accountId, Long stockId);

    /**
     * 락 없이 읽는다.
     *
     * <p>어떤 종목의 보유는 그 종목의 매칭에서만 바뀌고, 종목 락이 같은 종목의 동시 매칭을 막는다.
     * 그래서 매칭 중에는 이 값이 우리 자신 말고는 바뀌지 않는다. 판정에만 쓰고 실제 체결은
     * {@link #findByAccountIdAndStockId}로 잠그고 한다.
     */
    @Query("select h from Holding h where h.account.id = :accountId and h.stock.id = :stockId")
    Optional<Holding> findForJudgementByAccountIdAndStockId(
        @Param("accountId") Long accountId,
        @Param("stockId") Long stockId
    );
}
