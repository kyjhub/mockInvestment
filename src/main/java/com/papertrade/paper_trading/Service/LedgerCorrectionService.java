package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Dto.LedgerCacheRebuildResult;
import com.papertrade.paper_trading.Dto.LedgerCacheRebuildResult.HoldingChange;
import com.papertrade.paper_trading.Dto.LedgerReversalResult;
import com.papertrade.paper_trading.Entity.Account;
import com.papertrade.paper_trading.Entity.Holding;
import com.papertrade.paper_trading.Entity.LedgerEntry;
import com.papertrade.paper_trading.Entity.LedgerTransaction;
import com.papertrade.paper_trading.Entity.Stock;
import com.papertrade.paper_trading.Enum.LedgerAccount;
import com.papertrade.paper_trading.Repository.AccountRepository;
import com.papertrade.paper_trading.Repository.HoldingRepository;
import com.papertrade.paper_trading.Repository.LedgerEntryRepository;
import com.papertrade.paper_trading.Repository.LedgerTransactionRepository;
import com.papertrade.paper_trading.Repository.StockRepository;
import com.papertrade.paper_trading.Service.LedgerPostingService.Posting;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대사가 불일치를 보고했을 때 관리자가 쓰는 정정 수단. 대사({@code LedgerReconciliationService})는 고치지 않고
 * 보고만 하므로, 고치는 일은 사람이 원인을 확인한 뒤 여기서 한다.
 *
 * <p>불일치는 두 종류다.
 * <ul>
 *   <li><b>원장은 맞고 캐시가 틀림</b> — {@link #rebuildCaches}. 잔고·실현손익·보유는 원장의 캐시이므로 원장
 *       합계로 다시 계산해 덮어쓴다. 원장에는 아무것도 추가하지 않는다.</li>
 *   <li><b>원장 자체가 틀림</b> — {@link #reverse}. 원거래는 그대로 두고, 부호를 뒤집은 거래를 새로 추가해
 *       상쇄한다. 새 분개가 생겼으므로 캐시도 같은 트랜잭션에서 반영한다. 체결이 분개를 기록하며 잔고를
 *       갱신하는 것과 같은 일이다.</li>
 * </ul>
 *
 * <p>어느 쪽도 과거 기록을 수정하거나 지우지 않는다. 주문과 체결 기록도 그대로 둔다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerCorrectionService {

    private static final int MONEY_SCALE = 2;

    private final AccountRepository accountRepository;
    private final HoldingRepository holdingRepository;
    private final StockRepository stockRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final LedgerTransactionRepository ledgerTransactionRepository;
    private final LedgerPostingService ledgerPostingService;

    /**
     * 계좌의 잔고 캐시를 원장 합계로 다시 계산해 덮어쓴다.
     *
     * <p>계좌 row를 먼저 잠그고 보유 row를 잠근다. 체결과 같은 순서라 데드락이 생기지 않고, 재계산하는 동안
     * 이 계좌의 체결이 끼어들지 않는다.
     */
    @Transactional
    public LedgerCacheRebuildResult rebuildCaches(Long accountId, Long adminId, String reason) {
        Account account = accountRepository.findByIdForUpdate(accountId)
            .orElseThrow(() -> new IllegalArgumentException("계좌를 찾을 수 없습니다."));

        BigDecimal cashBefore = account.getCashBalance();
        BigDecimal realizedBefore = account.getRealizedProfit();
        BigDecimal cash = money(ledgerEntryRepository.sumAmountByAccount(accountId, LedgerAccount.CASH));
        BigDecimal realized = money(ledgerEntryRepository.sumAmountByAccount(accountId, LedgerAccount.REALIZED_PNL).negate());
        if (cash.signum() < 0) {
            // 캐시만 맞춰서는 해결되지 않는다. 원장 자체에 틀린 거래가 있다는 뜻이다.
            throw new IllegalArgumentException("원장의 현금 합계가 음수입니다. 잘못된 원장 거래를 먼저 반대분개해야 합니다.");
        }
        account.restoreFromLedger(cash, realized);

        TreeSet<Long> stockIds = new TreeSet<>(holdingRepository.findStockIdsByAccountId(accountId));
        stockIds.addAll(ledgerEntryRepository.findSecuritiesStockIds(accountId));
        List<HoldingChange> changedHoldings = new ArrayList<>();
        for (Long stockId : stockIds) {
            long quantity = ledgerEntryRepository.sumSecuritiesQuantity(accountId, stockId);
            BigDecimal cost = money(ledgerEntryRepository.sumSecuritiesAmount(accountId, stockId));
            if (quantity < 0 || cost.signum() < 0) {
                throw new IllegalArgumentException(
                    "원장의 보유 합계가 음수입니다. 잘못된 원장 거래를 먼저 반대분개해야 합니다. stockId=" + stockId);
            }

            Holding holding = holdingRepository.findByAccountIdAndStockId(accountId, stockId)
                .orElseGet(() -> holdingRepository.save(Holding.create(account, stock(stockId))));
            if (holding.getQuantity() == quantity && holding.getTotalPurchaseAmount().compareTo(cost) == 0) {
                continue;
            }
            changedHoldings.add(new HoldingChange(
                stockId, holding.getStock().getSymbol(),
                holding.getQuantity(), quantity,
                holding.getTotalPurchaseAmount(), cost));
            holding.restoreFromLedger(quantity, cost);
        }

        LedgerCacheRebuildResult result = new LedgerCacheRebuildResult(
            accountId, cashBefore, cash, realizedBefore, realized, changedHoldings);
        if (result.changed()) {
            log.warn("Rebuilt ledger caches. accountId={}, adminId={}, reason={}, cash={} -> {}, realizedProfit={} -> {}, "
                    + "holdings={}",
                accountId, adminId, reason, cashBefore, cash, realizedBefore, realized, changedHoldings);
        } else {
            log.info("Ledger caches already matched the ledger. accountId={}, adminId={}, reason={}",
                accountId, adminId, reason);
        }
        return result;
    }

    /**
     * 원장 거래를 반대분개한다. 원거래는 그대로 두고 부호를 뒤집은 거래를 새로 추가한다.
     *
     * <p>정정 결과 예수금이나 보유가 음수가 되면 거부한다. 체결로 받은 돈을 이미 다른 주문에 써 버린 경우다.
     * {@code cash_balance >= 0} 제약이 어차피 막지만, 원인을 알 수 있는 메시지로 먼저 거부한다.
     */
    @Transactional
    public LedgerReversalResult reverse(Long transactionId, Long adminId, String reason) {
        LedgerTransaction original = ledgerTransactionRepository.findById(transactionId)
            .orElseThrow(() -> new IllegalArgumentException("원장 거래를 찾을 수 없습니다."));
        if (original.getReversalOf() != null) {
            // 반대분개를 다시 뒤집으면 원거래가 되살아난다. 그게 필요하면 올바른 거래를 새로 기록해야 한다.
            throw new IllegalArgumentException("반대분개 거래는 다시 반대분개할 수 없습니다.");
        }
        if (ledgerTransactionRepository.existsByReversalOfId(transactionId)) {
            throw new IllegalArgumentException("이미 반대분개된 거래입니다.");
        }
        List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionIdOrderByIdAsc(transactionId);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("분개가 없는 거래는 반대분개할 수 없습니다.");
        }

        Map<Long, Account> accounts = lockAccountsInIdOrder(entries);
        Map<String, Holding> holdings = new LinkedHashMap<>();
        List<Posting> postings = new ArrayList<>();
        for (LedgerEntry entry : entries) {
            postings.add(reverseEntry(entry, accounts.get(entry.getAccount().getId()), holdings));
        }
        rejectNegativeBalances(accounts, holdings);

        LedgerTransaction reversal = ledgerPostingService.postReversal(
            original,
            "REVERSAL:" + transactionId,
            "반대분개 #" + transactionId + " (관리자 " + adminId + "): " + reason,
            postings);

        log.warn("Reversed a ledger transaction. originalId={}, reversalId={}, adminId={}, reason={}",
            transactionId, reversal.getId(), adminId, reason);
        return new LedgerReversalResult(transactionId, reversal.getId(), postings.size());
    }

    /** 체결과 같은 규칙으로 잠근다 — 계좌는 id 오름차순, 보유는 계좌를 다 잠근 뒤. */
    private Map<Long, Account> lockAccountsInIdOrder(List<LedgerEntry> entries) {
        TreeSet<Long> accountIds = new TreeSet<>();
        entries.forEach(entry -> accountIds.add(entry.getAccount().getId()));
        Map<Long, Account> accounts = new TreeMap<>();
        for (Long accountId : accountIds) {
            accounts.put(accountId, accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new IllegalArgumentException("계좌를 찾을 수 없습니다. accountId=" + accountId)));
        }
        return accounts;
    }

    /**
     * 분개 하나를 뒤집고, 그 분개가 캐시에 준 효과도 상쇄한다. 분개를 하나씩 적용하며 만들어야
     * {@code balance_after}가 각 분개 직후의 잔액이 된다.
     */
    private Posting reverseEntry(LedgerEntry entry, Account account, Map<String, Holding> holdings) {
        BigDecimal amount = entry.getAmount().negate();
        Long quantity = entry.getQuantity() == null ? null : -entry.getQuantity();
        LedgerAccount ledgerAccount = entry.getLedgerAccount();

        return switch (ledgerAccount) {
            case CASH -> {
                account.creditCash(amount);
                yield new Posting(account, LedgerAccount.CASH, null, amount, null, account.getCashBalance());
            }
            case SECURITIES -> {
                Holding holding = holding(account, entry.getStock(), holdings);
                holding.applyLedgerMovement(quantity == null ? 0L : quantity, amount);
                yield new Posting(account, LedgerAccount.SECURITIES, entry.getStock(), amount, quantity,
                    holding.getTotalPurchaseAmount());
            }
            case REALIZED_PNL -> {
                // 실현손익 캐시는 REALIZED_PNL 합계의 음수다. 이익이 대변(−)이기 때문이다.
                account.addRealizedProfit(amount.negate());
                yield new Posting(account, LedgerAccount.REALIZED_PNL, null, amount, null, null);
            }
            default -> new Posting(account, ledgerAccount, entry.getStock(), amount, quantity, null);
        };
    }

    private Holding holding(Account account, Stock stock, Map<String, Holding> holdings) {
        return holdings.computeIfAbsent(account.getId() + ":" + stock.getId(), key ->
            holdingRepository.findByAccountIdAndStockId(account.getId(), stock.getId())
                .orElseGet(() -> holdingRepository.save(Holding.create(account, stock))));
    }

    private void rejectNegativeBalances(Map<Long, Account> accounts, Map<String, Holding> holdings) {
        for (Account account : accounts.values()) {
            if (account.getCashBalance().signum() < 0) {
                throw new IllegalArgumentException(
                    "반대분개하면 예수금이 음수가 됩니다. 체결로 받은 금액을 이미 사용했습니다. accountId=" + account.getId());
            }
        }
        for (Holding holding : holdings.values()) {
            if (holding.getQuantity() < 0 || holding.getTotalPurchaseAmount().signum() < 0) {
                throw new IllegalArgumentException(
                    "반대분개하면 보유가 음수가 됩니다. 체결로 받은 주식을 이미 매도했습니다. accountId="
                        + holding.getAccount().getId());
            }
        }
    }

    private Stock stock(Long stockId) {
        return stockRepository.findById(stockId)
            .orElseThrow(() -> new IllegalArgumentException("종목을 찾을 수 없습니다. stockId=" + stockId));
    }

    private BigDecimal money(BigDecimal amount) {
        return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
