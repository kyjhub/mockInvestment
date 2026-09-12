package com.papertrade.paper_trading.Repository;

import com.papertrade.paper_trading.Entity.LedgerEntry;
import com.papertrade.paper_trading.Enum.LedgerAccount;
import java.math.BigDecimal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    /**
     * 전역 균형. 복식부기의 핵심 불변식이며 항상 0이어야 한다.
     *
     * <p>0이 아니라는 것은 어딘가에서 돈이 생기거나 사라졌다는 뜻이다. 단식부기로는 이 검증이
     * 원리적으로 불가능하다.
     */
    @Query("select coalesce(sum(e.amount), 0) from LedgerEntry e")
    BigDecimal sumAllAmounts();

    /** 계좌별·계정과목별 잔액. 잔고 캐시를 원장에서 재계산해 대사할 때 쓴다. */
    @Query("""
        select coalesce(sum(e.amount), 0)
        from LedgerEntry e
        where e.account.id = :accountId and e.ledgerAccount = :ledgerAccount
        """)
    BigDecimal sumAmountByAccount(
        @Param("accountId") Long accountId,
        @Param("ledgerAccount") LedgerAccount ledgerAccount
    );

    /** 종목별 보유 원가와 수량. */
    @Query("""
        select coalesce(sum(e.amount), 0)
        from LedgerEntry e
        where e.account.id = :accountId
          and e.ledgerAccount = com.papertrade.paper_trading.Enum.LedgerAccount.SECURITIES
          and e.stock.id = :stockId
        """)
    BigDecimal sumSecuritiesAmount(@Param("accountId") Long accountId, @Param("stockId") Long stockId);

    @Query("""
        select coalesce(sum(e.quantity), 0)
        from LedgerEntry e
        where e.account.id = :accountId
          and e.ledgerAccount = com.papertrade.paper_trading.Enum.LedgerAccount.SECURITIES
          and e.stock.id = :stockId
        """)
    long sumSecuritiesQuantity(@Param("accountId") Long accountId, @Param("stockId") Long stockId);
}
