package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Config.SchedulingConfig;
import com.papertrade.paper_trading.Dto.AccountValuation;
import com.papertrade.paper_trading.Entity.Account;
import com.papertrade.paper_trading.Entity.Holding;
import com.papertrade.paper_trading.Repository.AccountRepository;
import com.papertrade.paper_trading.Repository.HoldingRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * {@code accounts.total_asset_value}를 주기적으로 갱신한다.
 *
 * <p>이 컬럼은 지금까지 계좌 개설 시 한 번 채워지고 체결이 갱신하지 않아 실제 자산과 무관한 값이었다.
 *
 * <p>시세는 <b>전체 보유의 distinct 종목</b>을 한 번에 조회한다. 계좌마다 부르면 호출 수가 계좌 수에
 * 비례하는데, 같은 종목을 여러 계좌가 들고 있어도 시세는 하나다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TotalAssetValuationScheduler {

    private final HoldingRepository holdingRepository;
    private final AccountRepository accountRepository;
    private final MarketPriceLookup marketPriceLookup;
    private final ValuationService valuationService;

    @Scheduled(
        scheduler = SchedulingConfig.VALUATION_SCHEDULER,
        fixedDelayString = "${valuation.total-asset.fixed-delay-ms:60000}"
    )
    public void refreshTotalAssetValue() {
        List<Holding> openPositions = holdingRepository.findOpenPositions();
        if (openPositions.isEmpty()) {
            return;
        }

        Map<String, BigDecimal> prices =
            marketPriceLookup.lastPricesOf(MarketPriceLookup.distinctSymbols(openPositions));
        if (prices.isEmpty()) {
            return;
        }

        Map<Account, List<Holding>> positionsByAccount = openPositions.stream()
            .collect(Collectors.groupingBy(Holding::getAccount));

        int valued = 0;
        for (Map.Entry<Account, List<Holding>> entry : positionsByAccount.entrySet()) {
            Account account = entry.getKey();
            Optional<AccountValuation> valuation = valuationService.value(
                account.getId(), account.getCashBalance(), entry.getValue(), prices);
            if (valuation.isEmpty()) {
                continue;
            }
            accountRepository.updateTotalAssetValue(account.getId(), valuation.get().totalAsset());
            valued++;
        }

        log.debug("Refreshed total asset value. accounts={}, skipped={}",
            valued, positionsByAccount.size() - valued);
    }
}
