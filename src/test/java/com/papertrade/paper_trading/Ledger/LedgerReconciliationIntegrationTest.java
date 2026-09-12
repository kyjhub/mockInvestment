package com.papertrade.paper_trading.Ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.papertrade.paper_trading.Dto.LedgerReconciliationReport;
import com.papertrade.paper_trading.Entity.Account;
import com.papertrade.paper_trading.Entity.User;
import com.papertrade.paper_trading.Enum.AccountStatus;
import com.papertrade.paper_trading.Enum.Role;
import com.papertrade.paper_trading.Enum.Status;
import com.papertrade.paper_trading.Repository.AccountRepository;
import com.papertrade.paper_trading.Repository.UserRepository;
import com.papertrade.paper_trading.Service.AccountOpeningService;
import com.papertrade.paper_trading.Service.LedgerReconciliationService;
import com.papertrade.paper_trading.support.ApplicationIntegrationTest;
import com.papertrade.paper_trading.support.IntegrationTestContainers;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대사는 "잘 되고 있다"를 확인하는 장치가 아니라 <b>틀렸을 때 반드시 발견하는</b> 장치다.
 * 그러므로 정상 상태만 검증하면 의미가 없다. 일부러 깨뜨리고 잡히는지 본다.
 */
@ApplicationIntegrationTest
class LedgerReconciliationIntegrationTest extends IntegrationTestContainers {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    @Autowired private AccountOpeningService accountOpeningService;
    @Autowired private LedgerReconciliationService reconciliationService;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void aLedgerBuiltOnlyThroughThePostingServiceIsClean() {
        Account account = openAccount("1000000.00");

        LedgerReconciliationReport report = reconciliationService.reconcile();

        // 원장 내부 불변식은 전역으로 본다. 원장을 거치지 않고 계좌·보유를 심는 fixture가 있어도
        // 분개 자체는 건드리지 않으므로 이 둘은 항상 성립해야 한다.
        assertThat(report.globalImbalance()).isEqualByComparingTo("0.00");
        assertThat(report.unbalancedTransactionIds()).isEmpty();

        // 캐시 대사는 이 test가 만든 계좌로 좁힌다. 같은 DB를 쓰는 다른 통합 test가
        // accountRepository.save()로 계좌를 직접 심어 원장 없는 잔고를 남기기 때문이다.
        // 그 계좌들이 실제로 불일치로 잡힌다는 사실 자체가 대사가 동작한다는 방증이다.
        assertThat(report.cashBalanceMismatchAccountIds()).doesNotContain(account.getId());
        assertThat(report.realizedProfitMismatchAccountIds()).doesNotContain(account.getId());
    }

    @Test
    void anAccountSeededWithoutOpeningEntriesIsReportedAsMismatched() {
        // 위 test가 전역 isClean()을 쓰지 못하는 이유를 명시적으로 고정한다.
        // 잔고만 있고 개시 분개가 없는 계좌는 원장으로 재구성할 수 없으므로 불일치가 맞다.
        Account seeded = seedAccountWithoutLedger("500000.00");

        assertThat(reconciliationService.reconcile().cashBalanceMismatchAccountIds())
            .contains(seeded.getId());
    }

    @Test
    @Transactional
    void aTamperedCashBalanceIsDetected() {
        Account account = openAccount("1000000.00");

        // 원장을 거치지 않고 잔고만 바꾼다. 애플리케이션 버그가 만들 수 있는 상태다.
        entityManager.createQuery("update Account a set a.cashBalance = :balance where a.id = :id")
            .setParameter("balance", new BigDecimal("999999.00"))
            .setParameter("id", account.getId())
            .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        LedgerReconciliationReport report = reconciliationService.reconcile();

        assertThat(report.isClean()).isFalse();
        assertThat(report.cashBalanceMismatchAccountIds()).contains(account.getId());
        // 원장 자체는 멀쩡하므로 전역 균형은 유지된다. 깨진 건 캐시 쪽이다.
        assertThat(report.globalImbalance()).isEqualByComparingTo("0.00");
    }

    @Test
    @Transactional
    void aDeletedEntryBreaksBothGlobalAndTransactionBalance() {
        Account account = openAccount("1000000.00");

        Long entryId = (Long) entityManager.createQuery("""
            select e.id from LedgerEntry e
            where e.account.id = :accountId
              and e.ledgerAccount = com.papertrade.paper_trading.Enum.LedgerAccount.EQUITY_FUNDING
            """).setParameter("accountId", account.getId()).getSingleResult();
        Long transactionId = (Long) entityManager.createQuery(
            "select e.transaction.id from LedgerEntry e where e.id = :id")
            .setParameter("id", entryId).getSingleResult();

        entityManager.createQuery("delete from LedgerEntry e where e.id = :id")
            .setParameter("id", entryId)
            .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        LedgerReconciliationReport report = reconciliationService.reconcile();

        assertThat(report.isClean()).isFalse();
        // 전역 합계가 0이 아니라는 것은 돈이 생기거나 사라졌다는 뜻이다.
        // 단식부기로는 이 검증이 원리적으로 불가능하다.
        assertThat(report.globalImbalance()).isNotEqualByComparingTo("0.00");
        assertThat(report.unbalancedTransactionIds()).contains(transactionId);
    }

    @Test
    @Transactional
    void reconciliationDoesNotRepairWhatItFinds() {
        // 조용히 맞춰 버리면 버그를 숨기게 된다. 보고만 하고 고치지 않는 것이 설계다.
        Account account = openAccount("1000000.00");
        entityManager.createQuery("update Account a set a.cashBalance = :balance where a.id = :id")
            .setParameter("balance", new BigDecimal("777.00"))
            .setParameter("id", account.getId())
            .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        reconciliationService.reconcile();
        reconciliationService.reconcile();

        BigDecimal afterReconciliation = (BigDecimal) entityManager
            .createQuery("select a.cashBalance from Account a where a.id = :id")
            .setParameter("id", account.getId())
            .getSingleResult();
        assertThat(afterReconciliation).isEqualByComparingTo("777.00");
    }

    /** 개시 분개 없이 잔고만 있는 계좌. 원장을 거치지 않은 상태를 재현한다. */
    private Account seedAccountWithoutLedger(String cashBalance) {
        long suffix = SEQUENCE.incrementAndGet();
        User user = userRepository.save(User.builder()
            .email("seeded-" + suffix + "@example.com")
            .passwordHash("test")
            .nickname("seeded-" + suffix)
            .status(Status.ACTIVE)
            .role(Role.USER)
            .build());
        return accountRepository.save(Account.builder()
            .user(user)
            .accountNumber("SEED-" + suffix)
            .cashBalance(new BigDecimal(cashBalance))
            .initialBalance(new BigDecimal(cashBalance))
            .totalAssetValue(new BigDecimal(cashBalance))
            .status(AccountStatus.ACTIVE)
            .build());
    }

    private Account openAccount(String initialAmount) {
        long suffix = SEQUENCE.incrementAndGet();
        User user = userRepository.save(User.builder()
            .email("recon-" + suffix + "@example.com")
            .passwordHash("test")
            .nickname("recon-" + suffix)
            .status(Status.ACTIVE)
            .role(Role.USER)
            .build());
        return accountOpeningService.open(user, new BigDecimal(initialAmount));
    }
}
