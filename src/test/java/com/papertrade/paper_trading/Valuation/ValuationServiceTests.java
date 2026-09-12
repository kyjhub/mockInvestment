package com.papertrade.paper_trading.Valuation;

import static org.assertj.core.api.Assertions.assertThat;

import com.papertrade.paper_trading.Dto.AccountValuation;
import com.papertrade.paper_trading.Entity.Account;
import com.papertrade.paper_trading.Entity.Holding;
import com.papertrade.paper_trading.Entity.Stock;
import com.papertrade.paper_trading.Enum.AccountStatus;
import com.papertrade.paper_trading.Enum.Market;
import com.papertrade.paper_trading.Enum.SecurityType;
import com.papertrade.paper_trading.Enum.StockStatus;
import com.papertrade.paper_trading.Service.ValuationService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 평가액은 원장 파생이 아니다. 시세가 입력이라 대사로 검증할 수 없으므로,
 * 계산 자체와 "계산하지 않기로 하는 조건"을 test로 고정한다.
 */
class ValuationServiceTests {

    private final ValuationService valuationService = new ValuationService();

    @Test
    void totalAssetIsCashPlusMarketValueOfHoldings() {
        List<Holding> positions = List.of(
            holding(stock("AAPL", "USD"), 10L, "200.0000"),
            holding(stock("TSLA", "USD"), 5L, "300.0000"));

        AccountValuation valuation = valuationService.value(
            1L, new BigDecimal("1000.00"), positions,
            Map.of("AAPL", new BigDecimal("250.0000"), "TSLA", new BigDecimal("400.0000"))
        ).orElseThrow();

        // 평가금액 = 10×250 + 5×400 = 4,500
        assertThat(valuation.stockEvaluation()).isEqualByComparingTo("4500.00");
        // 총매입 = 10×200 + 5×300 = 3,500
        assertThat(valuation.totalPurchaseAmount()).isEqualByComparingTo("3500.00");
        assertThat(valuation.unrealizedProfit()).isEqualByComparingTo("1000.00");
        assertThat(valuation.totalAsset()).isEqualByComparingTo("5500.00");
    }

    @Test
    void unrealizedProfitRateExcludesCash() {
        // 현업 잔고 화면과 같은 기준이다. 예수금이 분모에 들어가면 리더보드 수익률이 되어 버린다.
        List<Holding> positions = List.of(holding(stock("AAPL", "USD"), 10L, "100.0000"));

        AccountValuation valuation = valuationService.value(
            1L, new BigDecimal("999999.00"), positions,
            Map.of("AAPL", new BigDecimal("110.0000"))
        ).orElseThrow();

        // 1,000 매입 → 1,100 평가. 예수금이 아무리 많아도 +10%다.
        assertThat(valuation.unrealizedProfitRate()).isEqualByComparingTo("0.100000");
    }

    @Test
    void noHoldingsMeansTotalAssetIsCashAndRateIsUnknown() {
        AccountValuation valuation = valuationService.value(
            1L, new BigDecimal("1000.00"), List.of(), Map.of()).orElseThrow();

        assertThat(valuation.totalAsset()).isEqualByComparingTo("1000.00");
        // 0%가 아니다. 분모가 없으면 수익률이라는 개념 자체가 없다.
        assertThat(valuation.unrealizedProfitRate()).isNull();
    }

    @Test
    void aMissingPriceSkipsTheWholeAccount() {
        // 일부 종목만 반영한 총자산은 틀린 값이다. 틀린 값보다 "계산 중"이 낫다.
        List<Holding> positions = List.of(
            holding(stock("AAPL", "USD"), 10L, "200.0000"),
            holding(stock("TSLA", "USD"), 5L, "300.0000"));

        Optional<AccountValuation> valuation = valuationService.value(
            1L, new BigDecimal("1000.00"), positions,
            Map.of("AAPL", new BigDecimal("250.0000")));

        assertThat(valuation).isEmpty();
    }

    @Test
    void aMultiCurrencyAccountIsSkipped() {
        // 계좌에 통화 개념이 없어 USD 평가액과 KRW 예수금을 그냥 더하게 된다.
        // 국내 종목이 들어오는 순간 조용히 틀리지 않도록 여기서 막는다.
        List<Holding> positions = List.of(
            holding(stock("AAPL", "USD"), 10L, "200.0000"),
            holding(stock("005930", "KRW"), 5L, "70000.0000"));

        Optional<AccountValuation> valuation = valuationService.value(
            1L, new BigDecimal("1000.00"), positions,
            Map.of("AAPL", new BigDecimal("250.0000"), "005930", new BigDecimal("77000.0000")));

        assertThat(valuation).isEmpty();
    }

    @Test
    void aZeroOrNegativePriceIsTreatedAsUnavailable() {
        List<Holding> positions = List.of(holding(stock("AAPL", "USD"), 10L, "200.0000"));

        assertThat(valuationService.value(1L, new BigDecimal("1000.00"), positions,
            Map.of("AAPL", BigDecimal.ZERO))).isEmpty();
    }

    private Stock stock(String symbol, String currency) {
        return Stock.builder().id(1L).symbol(symbol).name(symbol).market(Market.NASDAQ)
            .securityType(SecurityType.STOCK).isCommonShare(true)
            .status(StockStatus.ACTIVE).currency(currency).build();
    }

    private Holding holding(Stock stock, long quantity, String averagePrice) {
        Account account = Account.builder().id(1L).accountNumber("A").status(AccountStatus.ACTIVE)
            .cashBalance(BigDecimal.ZERO).initialBalance(BigDecimal.ZERO)
            .totalAssetValue(BigDecimal.ZERO).build();
        Holding holding = Holding.create(account, stock);
        holding.buy(quantity, new BigDecimal(averagePrice));
        return holding;
    }
}
