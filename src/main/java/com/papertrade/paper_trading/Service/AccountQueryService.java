package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Dto.AccountBalanceResponse;
import com.papertrade.paper_trading.Dto.AccountValuation;
import com.papertrade.paper_trading.Entity.Account;
import com.papertrade.paper_trading.Entity.Holding;
import com.papertrade.paper_trading.Entity.User;
import com.papertrade.paper_trading.Enum.OrderStatus;
import com.papertrade.paper_trading.Repository.AccountRepository;
import com.papertrade.paper_trading.Repository.HoldingRepository;
import com.papertrade.paper_trading.Repository.OrderRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountQueryService {

    private static final List<OrderStatus> RESERVING_STATUSES = List.of(
        OrderStatus.PENDING,
        OrderStatus.PARTIALLY_FILLED
    );

    private final AccountRepository accountRepository;
    private final OrderRepository orderRepository;
    private final HoldingRepository holdingRepository;
    private final MarketPriceLookup marketPriceLookup;
    private final ValuationService valuationService;

    @Transactional(readOnly = true)
    public AccountBalanceResponse getBalance(User user) {
        if (user == null) {
            throw new IllegalArgumentException("인증 정보가 필요합니다.");
        }

        Account account = accountRepository.findByUserId(user.getId())
            .orElseThrow(() -> new IllegalArgumentException("계좌를 찾을 수 없습니다."));

        // 조회는 락을 잡지 않는다. 여기서 본 주문가능금액은 그 시점의 값이고,
        // 실제 판정은 주문 접수 시점에 계좌 row를 잠근 뒤 다시 집계해서 한다.
        BigDecimal reservedCash = orderRepository.sumReservedCash(account.getId(), RESERVING_STATUSES);

        // 화면에 보이는 평가액은 batch가 갱신한 total_asset_value가 아니라 지금 시세로 계산한다.
        // 그 컬럼은 분 단위 캐시라 사용자가 보는 순간의 값과 어긋날 수 있다.
        List<Holding> openPositions = holdingRepository.findOpenPositionsByAccountId(account.getId());
        Optional<AccountValuation> valuation = valuationService.value(
            account.getId(),
            account.getCashBalance(),
            openPositions,
            marketPriceLookup.lastPricesOf(MarketPriceLookup.distinctSymbols(openPositions)));

        return new AccountBalanceResponse(
            account.getAccountNumber(),
            account.getCashBalance(),
            reservedCash,
            account.getCashBalance().subtract(reservedCash),
            account.getRealizedProfit(),
            // 총매입금액은 시세와 무관하게 항상 보여줄 수 있다.
            openPositions.stream()
                .map(Holding::getTotalPurchaseAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add),
            valuation.map(AccountValuation::stockEvaluation).orElse(null),
            valuation.map(AccountValuation::unrealizedProfit).orElse(null),
            valuation.map(AccountValuation::unrealizedProfitRate).orElse(null),
            valuation.map(AccountValuation::totalAsset).orElse(null)
        );
    }
}
