package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Entity.Account;
import com.papertrade.paper_trading.Entity.LedgerEntry;
import com.papertrade.paper_trading.Entity.LedgerTransaction;
import com.papertrade.paper_trading.Entity.Stock;
import com.papertrade.paper_trading.Enum.LedgerAccount;
import com.papertrade.paper_trading.Enum.LedgerTransactionType;
import com.papertrade.paper_trading.Repository.LedgerEntryRepository;
import com.papertrade.paper_trading.Repository.LedgerTransactionRepository;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 원장에 기록하는 유일한 통로.
 *
 * <p>여기를 거치지 않고 {@code LedgerEntry}를 직접 저장하면 거래 단위 균형이 깨질 수 있다.
 * 균형 검증을 한 곳에 모아 두는 것이 이 클래스의 존재 이유다.
 */
@Service
@RequiredArgsConstructor
public class LedgerPostingService {

    private final LedgerTransactionRepository ledgerTransactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    /**
     * 분개 묶음을 하나의 거래로 기록한다.
     *
     * @throws IllegalArgumentException 분개가 2줄 미만이거나 금액 합계가 0이 아닐 때.
     *     이건 사용자 입력 오류가 아니라 호출자의 계산이 틀렸다는 뜻이므로 저장하지 않고 막는다.
     */
    public LedgerTransaction post(
        LedgerTransactionType transactionType,
        String idempotencyKey,
        String description,
        List<Posting> postings
    ) {
        if (postings.size() < 2) {
            throw new IllegalArgumentException("분개는 두 줄 이상이어야 합니다.");
        }

        BigDecimal balance = postings.stream()
            .map(Posting::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (balance.signum() != 0) {
            throw new IllegalArgumentException("분개 합계가 0이 아닙니다. balance=" + balance);
        }

        LedgerTransaction transaction = ledgerTransactionRepository.save(LedgerTransaction.builder()
            .transactionType(transactionType)
            .idempotencyKey(idempotencyKey)
            .description(description)
            .build());

        for (Posting posting : postings) {
            ledgerEntryRepository.save(LedgerEntry.builder()
                .transaction(transaction)
                .account(posting.account())
                .ledgerAccount(posting.ledgerAccount())
                .stock(posting.stock())
                .amount(posting.amount())
                .quantity(posting.quantity())
                .balanceAfter(posting.balanceAfter())
                .build());
        }
        return transaction;
    }

    /**
     * 분개 한 줄의 명세.
     *
     * @param balanceAfter 유지되는 잔고 캐시가 있는 계정과목에만 넣는다. 없으면 {@code null}.
     *     집계로 계산하지 않는 이유는 {@link LedgerEntry#getBalanceAfter()} 주석 참고.
     */
    public record Posting(
        Account account,
        LedgerAccount ledgerAccount,
        Stock stock,
        BigDecimal amount,
        Long quantity,
        BigDecimal balanceAfter
    ) {

        public static Posting cash(Account account, BigDecimal amount) {
            return new Posting(account, LedgerAccount.CASH, null, amount, null, account.getCashBalance());
        }

        public static Posting securities(
            Account account,
            Stock stock,
            BigDecimal amount,
            long quantity,
            BigDecimal totalPurchaseAmount
        ) {
            return new Posting(account, LedgerAccount.SECURITIES, stock, amount, quantity, totalPurchaseAmount);
        }

        public static Posting of(Account account, LedgerAccount ledgerAccount, BigDecimal amount) {
            return new Posting(account, ledgerAccount, null, amount, null, null);
        }
    }
}
