package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Entity.Account;
import com.papertrade.paper_trading.Entity.LedgerTransaction;
import com.papertrade.paper_trading.Entity.User;
import com.papertrade.paper_trading.Enum.AccountStatus;
import com.papertrade.paper_trading.Enum.LedgerAccount;
import com.papertrade.paper_trading.Enum.LedgerTransactionType;
import com.papertrade.paper_trading.Repository.AccountRepository;
import com.papertrade.paper_trading.Service.LedgerPostingService.Posting;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계좌 개설과 개시 분개를 한 transaction으로 묶는다.
 *
 * <p>이 둘이 갈라지면 원장 시스템의 기본 불변식 {@code cash_balance = Σ(CASH entries)}가 처음부터
 * 성립하지 않는다. 개시 입금 분개 없이 잔고만 있는 계좌는 원장으로 재구성할 수 없다.
 *
 * <p>회원가입과의 연결은 이 클래스의 책임이 아니다. 여기서는 "계좌를 열면 개시 분개가 함께 남는다"는
 * 규약만 확정한다.
 */
@Service
@RequiredArgsConstructor
public class AccountOpeningService {

    private static final int MONEY_SCALE = 2;

    private final AccountRepository accountRepository;
    private final LedgerPostingService ledgerPostingService;

    @Transactional
    public Account open(User user, BigDecimal initialAmount) {
        if (user == null) {
            throw new IllegalArgumentException("사용자 정보가 필요합니다.");
        }
        if (initialAmount == null || initialAmount.signum() <= 0) {
            throw new IllegalArgumentException("초기 투자금은 0보다 커야 합니다.");
        }
        if (accountRepository.findByUserId(user.getId()).isPresent()) {
            throw new IllegalArgumentException("이미 계좌가 있습니다.");
        }

        BigDecimal openingBalance = initialAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        Account account = accountRepository.save(Account.builder()
            .user(user)
            .accountNumber(generateAccountNumber())
            .cashBalance(openingBalance)
            .initialBalance(openingBalance)
            .totalAssetValue(openingBalance)
            .status(AccountStatus.ACTIVE)
            .build());

        LedgerTransaction opening = ledgerPostingService.post(
            LedgerTransactionType.ACCOUNT_OPENING,
            "OPEN:" + account.getId(),
            "계좌 개설 모의 투자금 지급",
            List.of(
                Posting.cash(account, openingBalance),
                Posting.of(account, LedgerAccount.EQUITY_FUNDING, openingBalance.negate())
            )
        );
        // 개시 분개가 없으면 이후 모든 대사가 성립하지 않는다. 방어적으로 확인한다.
        if (opening == null) {
            throw new IllegalStateException("개시 분개 기록에 실패했습니다.");
        }
        return account;
    }

    private String generateAccountNumber() {
        return "PT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }
}
