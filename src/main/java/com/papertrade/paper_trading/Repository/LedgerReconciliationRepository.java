package com.papertrade.paper_trading.Repository;

import com.papertrade.paper_trading.Entity.LedgerEntry;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * 대사 전용 조회. 원장과 파생 캐시가 어긋난 곳만 골라낸다.
 *
 * <p>모든 질의가 <b>불일치 항목만</b> 돌려준다. 계좌마다 한 번씩 집계해 비교하면 계좌 수에 비례해
 * 느려지지만, 이 방식은 질의 수가 고정이라 데이터가 늘어도 비용이 항목 수만큼만 늘어난다.
 *
 * <p>여러 entity에 걸쳐 있어 특정 repository에 두기 애매하므로 대사 목적으로 한곳에 모았다.
 */
public interface LedgerReconciliationRepository extends Repository<LedgerEntry, Long> {

    /**
     * 금액 합계가 0이 아닌 거래.
     *
     * <p>하나라도 나오면 {@code LedgerPostingService}를 우회해 분개를 저장한 코드가 있다는 뜻이다.
     */
    @Query("""
        select e.transaction.id
        from LedgerEntry e
        group by e.transaction.id
        having sum(e.amount) <> 0
        """)
    List<Long> findUnbalancedTransactionIds();

    /** 잔고 캐시가 원장과 어긋난 계좌. */
    @Query("""
        select a.id
        from Account a
        where a.cashBalance <> (
            select coalesce(sum(e.amount), 0)
            from LedgerEntry e
            where e.account = a
              and e.ledgerAccount = com.papertrade.paper_trading.Enum.LedgerAccount.CASH
        )
        """)
    List<Long> findCashBalanceMismatchAccountIds();

    /**
     * 실현손익 캐시가 원장과 어긋난 계좌.
     *
     * <p>수익이 대변({@code −})이므로 캐시와 분개 합계를 더하면 0이어야 한다.
     */
    @Query("""
        select a.id
        from Account a
        where a.realizedProfit + (
            select coalesce(sum(e.amount), 0)
            from LedgerEntry e
            where e.account = a
              and e.ledgerAccount = com.papertrade.paper_trading.Enum.LedgerAccount.REALIZED_PNL
        ) <> 0
        """)
    List<Long> findRealizedProfitMismatchAccountIds();

    /** 보유 수량이 원장과 어긋난 보유 row. */
    @Query("""
        select h.id
        from Holding h
        where h.quantity <> (
            select coalesce(sum(e.quantity), 0)
            from LedgerEntry e
            where e.account = h.account
              and e.stock = h.stock
              and e.ledgerAccount = com.papertrade.paper_trading.Enum.LedgerAccount.SECURITIES
        )
        """)
    List<Long> findHoldingQuantityMismatchIds();

    /** 보유 취득원가가 원장과 어긋난 보유 row. */
    @Query("""
        select h.id
        from Holding h
        where h.totalPurchaseAmount <> (
            select coalesce(sum(e.amount), 0)
            from LedgerEntry e
            where e.account = h.account
              and e.stock = h.stock
              and e.ledgerAccount = com.papertrade.paper_trading.Enum.LedgerAccount.SECURITIES
        )
        """)
    List<Long> findHoldingCostMismatchIds();

    /** {@code executions}에 남은 수수료 사본의 총합. 원장의 {@code FEE} 합계와 맞아야 한다. */
    @Query("select coalesce(sum(ex.commission), 0) from Execution ex")
    BigDecimal sumExecutionCommission();

    @Query("select coalesce(sum(ex.tax), 0) from Execution ex")
    BigDecimal sumExecutionTax();
}
