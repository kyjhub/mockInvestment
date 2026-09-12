package com.papertrade.paper_trading.Dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 대사 결과. 원장과 파생 캐시가 어긋난 곳을 모은다.
 *
 * @param globalImbalance 전 분개의 금액 합계. 0이 아니면 어딘가에서 돈이 생기거나 사라졌다는 뜻이다
 * @param commissionCopyDrift {@code executions.commission} 사본과 {@code FEE} 분개의 차이.
 *     거래 단위가 아니라 전체 합계로 비교하므로 서로 상쇄되는 오차는 잡지 못한다
 */
public record LedgerReconciliationReport(
    BigDecimal globalImbalance,
    List<Long> unbalancedTransactionIds,
    List<Long> cashBalanceMismatchAccountIds,
    List<Long> realizedProfitMismatchAccountIds,
    List<Long> holdingQuantityMismatchIds,
    List<Long> holdingCostMismatchIds,
    BigDecimal commissionCopyDrift,
    BigDecimal taxCopyDrift
) {

    public boolean isClean() {
        return globalImbalance.signum() == 0
            && unbalancedTransactionIds.isEmpty()
            && cashBalanceMismatchAccountIds.isEmpty()
            && realizedProfitMismatchAccountIds.isEmpty()
            && holdingQuantityMismatchIds.isEmpty()
            && holdingCostMismatchIds.isEmpty()
            && commissionCopyDrift.signum() == 0
            && taxCopyDrift.signum() == 0;
    }

    public int mismatchCount() {
        return (globalImbalance.signum() == 0 ? 0 : 1)
            + unbalancedTransactionIds.size()
            + cashBalanceMismatchAccountIds.size()
            + realizedProfitMismatchAccountIds.size()
            + holdingQuantityMismatchIds.size()
            + holdingCostMismatchIds.size()
            + (commissionCopyDrift.signum() == 0 ? 0 : 1)
            + (taxCopyDrift.signum() == 0 ? 0 : 1);
    }
}
