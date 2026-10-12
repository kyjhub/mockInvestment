package com.papertrade.paper_trading.Dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 원장에서 잔고 캐시를 다시 계산한 결과. 바뀐 값만 담지 않고 전후를 모두 담는다 — 무엇이 얼마나 어긋나
 * 있었는지가 원인을 찾는 단서다.
 */
public record LedgerCacheRebuildResult(
    Long accountId,
    BigDecimal cashBalanceBefore,
    BigDecimal cashBalanceAfter,
    BigDecimal realizedProfitBefore,
    BigDecimal realizedProfitAfter,
    List<HoldingChange> changedHoldings
) {

    public boolean changed() {
        return cashBalanceBefore.compareTo(cashBalanceAfter) != 0
            || realizedProfitBefore.compareTo(realizedProfitAfter) != 0
            || !changedHoldings.isEmpty();
    }

    public record HoldingChange(
        Long stockId,
        String symbol,
        long quantityBefore,
        long quantityAfter,
        BigDecimal totalPurchaseAmountBefore,
        BigDecimal totalPurchaseAmountAfter
    ) {
    }
}
