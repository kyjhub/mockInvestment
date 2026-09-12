package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Dto.LedgerReconciliationReport;
import com.papertrade.paper_trading.Enum.LedgerAccount;
import com.papertrade.paper_trading.Repository.LedgerEntryRepository;
import com.papertrade.paper_trading.Repository.LedgerReconciliationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 원장과 파생 캐시가 맞는지 주기적으로 검증한다.
 *
 * <p>로직이 아무리 정교해도 버그는 난다. 그래서 원장 시스템의 진짜 안전망은 올바르게 쓰는 코드가 아니라
 * <b>틀렸다는 것을 반드시 발견하는 장치</b>다. 이 batch가 그 장치다.
 *
 * <p><b>불일치를 자동으로 덮어쓰지 않는다.</b> 조용히 맞춰 버리면 버그를 숨기게 된다. 로그와 metric으로
 * 올리고 사람이 판단한다. 정정이 필요하면 원본을 남긴 채 반대분개를 추가한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerReconciliationService {

    private final LedgerReconciliationRepository reconciliationRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final MeterRegistry meterRegistry;

    private Counter runCounter;
    private Counter mismatchCounter;

    @PostConstruct
    void registerMeters() {
        runCounter = Counter.builder("ledger.reconciliation.run")
            .description("대사 실행 횟수")
            .register(meterRegistry);
        mismatchCounter = Counter.builder("ledger.reconciliation.mismatch")
            .description("대사에서 발견한 불일치 건수")
            .register(meterRegistry);
    }

    @Scheduled(cron = "${ledger.reconciliation.cron:0 30 5 * * *}")
    public void reconcileOnSchedule() {
        LedgerReconciliationReport report = reconcile();
        if (report.isClean()) {
            log.info("Ledger reconciliation clean.");
            return;
        }

        // ERROR로 올린다. 원장이 어긋난 것은 지표가 나빠진 수준이 아니라 데이터가 틀린 상태다.
        log.error(
            "Ledger reconciliation found mismatches. globalImbalance={}, unbalancedTransactions={}, "
                + "cashMismatchAccounts={}, realizedProfitMismatchAccounts={}, "
                + "holdingQuantityMismatches={}, holdingCostMismatches={}, "
                + "commissionCopyDrift={}, taxCopyDrift={}",
            report.globalImbalance(),
            report.unbalancedTransactionIds(),
            report.cashBalanceMismatchAccountIds(),
            report.realizedProfitMismatchAccountIds(),
            report.holdingQuantityMismatchIds(),
            report.holdingCostMismatchIds(),
            report.commissionCopyDrift(),
            report.taxCopyDrift()
        );
    }

    @Transactional(readOnly = true)
    public LedgerReconciliationReport reconcile() {
        BigDecimal globalImbalance = ledgerEntryRepository.sumAllAmounts();
        List<Long> unbalancedTransactionIds = reconciliationRepository.findUnbalancedTransactionIds();
        List<Long> cashMismatches = reconciliationRepository.findCashBalanceMismatchAccountIds();
        List<Long> realizedProfitMismatches = reconciliationRepository.findRealizedProfitMismatchAccountIds();
        List<Long> holdingQuantityMismatches = reconciliationRepository.findHoldingQuantityMismatchIds();
        List<Long> holdingCostMismatches = reconciliationRepository.findHoldingCostMismatchIds();

        BigDecimal commissionCopyDrift = reconciliationRepository.sumExecutionCommission()
            .subtract(ledgerEntryRepository.sumAmountByLedgerAccount(LedgerAccount.FEE));
        BigDecimal taxCopyDrift = reconciliationRepository.sumExecutionTax()
            .subtract(ledgerEntryRepository.sumAmountByLedgerAccount(LedgerAccount.TAX));

        LedgerReconciliationReport report = new LedgerReconciliationReport(
            globalImbalance,
            unbalancedTransactionIds,
            cashMismatches,
            realizedProfitMismatches,
            holdingQuantityMismatches,
            holdingCostMismatches,
            commissionCopyDrift,
            taxCopyDrift
        );

        runCounter.increment();
        mismatchCounter.increment(report.mismatchCount());
        return report;
    }
}
