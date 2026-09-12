package com.papertrade.paper_trading.Valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.papertrade.paper_trading.Dto.AccountBalanceResponse;
import com.papertrade.paper_trading.Dto.PriceResponse;
import com.papertrade.paper_trading.Dto.PriceResult;
import com.papertrade.paper_trading.Entity.Account;
import com.papertrade.paper_trading.Entity.Holding;
import com.papertrade.paper_trading.Entity.Stock;
import com.papertrade.paper_trading.Entity.User;
import com.papertrade.paper_trading.Enum.Market;
import com.papertrade.paper_trading.Enum.Role;
import com.papertrade.paper_trading.Enum.SecurityType;
import com.papertrade.paper_trading.Enum.Status;
import com.papertrade.paper_trading.Enum.StockStatus;
import com.papertrade.paper_trading.Repository.AccountRepository;
import com.papertrade.paper_trading.Repository.HoldingRepository;
import com.papertrade.paper_trading.Repository.StockRepository;
import com.papertrade.paper_trading.Repository.UserRepository;
import com.papertrade.paper_trading.Service.AccountOpeningService;
import com.papertrade.paper_trading.Service.AccountQueryService;
import com.papertrade.paper_trading.Service.PriceService;
import com.papertrade.paper_trading.Service.TotalAssetValuationScheduler;
import com.papertrade.paper_trading.support.ApplicationIntegrationTest;
import com.papertrade.paper_trading.support.IntegrationTestContainers;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@code accounts.total_asset_value}는 계좌 개설 때 한 번 채워지고 체결이 갱신하지 않아
 * 실제 자산과 무관한 값이었다(§19.2).
 */
@ApplicationIntegrationTest
class TotalAssetValuationIntegrationTest extends IntegrationTestContainers {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    @Autowired private AccountOpeningService accountOpeningService;
    @Autowired private AccountQueryService accountQueryService;
    @Autowired private TotalAssetValuationScheduler valuationScheduler;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StockRepository stockRepository;
    @Autowired private HoldingRepository holdingRepository;

    @MockitoBean private PriceService priceService;

    @Test
    void totalAssetValueIsRefreshedFromCashAndMarketValue() {
        User user = persistUser();
        Account account = accountOpeningService.open(user, new BigDecimal("1000000.00"));
        Stock stock = persistStock("VAL");
        holdingRepository.save(holdingOf(account, stock, 10L, "1000.0000"));
        givenPrices(new PriceResult(stock.getSymbol(), null, new BigDecimal("1500.0000"), "USD"));

        valuationScheduler.refreshTotalAssetValue();

        // 예수금 1,000,000 + 평가 10×1,500 = 1,015,000
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getTotalAssetValue())
            .isEqualByComparingTo("1015000.00");
    }

    @Test
    void refreshDoesNotOverwriteCashBalance() {
        // 평가는 락을 잡지 않는다. 엔티티를 통째로 flush하면 그 사이 체결이 바꾼 예수금을 되돌린다.
        User user = persistUser();
        Account account = accountOpeningService.open(user, new BigDecimal("1000000.00"));
        Stock stock = persistStock("VAL");
        holdingRepository.save(holdingOf(account, stock, 10L, "1000.0000"));
        givenPrices(new PriceResult(stock.getSymbol(), null, new BigDecimal("1500.0000"), "USD"));

        valuationScheduler.refreshTotalAssetValue();

        assertThat(accountRepository.findById(account.getId()).orElseThrow().getCashBalance())
            .isEqualByComparingTo("1000000.00");
    }

    @Test
    void priceIsLookedUpOncePerSymbolNotPerAccount() {
        // 같은 종목을 여러 계좌가 들고 있어도 시세는 하나다. 계좌 수에 비례해 부르면 예산이 터진다.
        Stock stock = persistStock("SHR");
        for (int i = 0; i < 5; i++) {
            Account account = accountOpeningService.open(persistUser(), new BigDecimal("1000.00"));
            holdingRepository.save(holdingOf(account, stock, 1L, "100.0000"));
        }
        givenPrices(new PriceResult(stock.getSymbol(), null, new BigDecimal("120.0000"), "USD"));

        valuationScheduler.refreshTotalAssetValue();

        verify(priceService, times(1)).getPrices(anyList());
    }

    @Test
    void balanceScreenShowsEvaluationSeparatelyFromCash() {
        User user = persistUser();
        Account account = accountOpeningService.open(user, new BigDecimal("1000000.00"));
        Stock stock = persistStock("BAL");
        holdingRepository.save(holdingOf(account, stock, 10L, "1000.0000"));
        givenPrices(new PriceResult(stock.getSymbol(), null, new BigDecimal("1100.0000"), "USD"));

        AccountBalanceResponse balance = accountQueryService.getBalance(user);

        assertThat(balance.cashBalance()).isEqualByComparingTo("1000000.00");
        assertThat(balance.totalPurchaseAmount()).isEqualByComparingTo("10000.00");
        assertThat(balance.stockEvaluation()).isEqualByComparingTo("11000.00");
        assertThat(balance.unrealizedProfit()).isEqualByComparingTo("1000.00");
        // 평가손익률의 분모는 총매입금액이다. 예수금 100만이 있어도 +10%다.
        assertThat(balance.unrealizedProfitRate()).isEqualByComparingTo("0.100000");
        assertThat(balance.totalAsset()).isEqualByComparingTo("1011000.00");
    }

    @Test
    void balanceStillWorksWhenPricesAreUnavailable() {
        // 시세가 막혔다고 잔고 조회 전체가 실패하면 안 된다. 예수금·주문가능금액은 시세와 무관하다.
        User user = persistUser();
        Account account = accountOpeningService.open(user, new BigDecimal("1000000.00"));
        Stock stock = persistStock("UNK");
        holdingRepository.save(holdingOf(account, stock, 10L, "1000.0000"));
        when(priceService.getPrices(anyList())).thenReturn(new PriceResponse(List.of()));

        AccountBalanceResponse balance = accountQueryService.getBalance(user);

        assertThat(balance.cashBalance()).isEqualByComparingTo("1000000.00");
        assertThat(balance.orderableAmount()).isEqualByComparingTo("1000000.00");
        assertThat(balance.totalPurchaseAmount()).isEqualByComparingTo("10000.00");
        assertThat(balance.stockEvaluation()).isNull();
        assertThat(balance.totalAsset()).isNull();
    }

    // --- fixtures ---

    private void givenPrices(PriceResult... prices) {
        when(priceService.getPrices(anyList())).thenReturn(new PriceResponse(List.of(prices)));
    }

    private User persistUser() {
        long suffix = SEQUENCE.incrementAndGet();
        return userRepository.save(User.builder()
            .email("val-" + suffix + "@example.com").passwordHash("test")
            .nickname("val-" + suffix).status(Status.ACTIVE).role(Role.USER).build());
    }

    private Stock persistStock(String prefix) {
        long suffix = SEQUENCE.incrementAndGet();
        return stockRepository.save(Stock.builder()
            .symbol(prefix + suffix).name("valuation " + suffix).market(Market.NASDAQ)
            .securityType(SecurityType.STOCK).isCommonShare(true)
            .status(StockStatus.ACTIVE).currency("USD").build());
    }

    private Holding holdingOf(Account account, Stock stock, long quantity, String averagePrice) {
        Holding holding = Holding.create(account, stock);
        holding.buy(quantity, new BigDecimal(averagePrice));
        return holding;
    }
}
