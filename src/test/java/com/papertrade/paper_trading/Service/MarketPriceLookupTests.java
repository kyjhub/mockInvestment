package com.papertrade.paper_trading.Service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.papertrade.paper_trading.Client.TossApiQuotaUnavailableException;
import com.papertrade.paper_trading.Dto.PriceResponse;
import com.papertrade.paper_trading.Dto.PriceResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 평가는 전체 계좌의 보유 종목을 한 번에 넘긴다. 그 수가 현재가 API의 요청당 한도를 넘어도
 * 평가가 멈추지 않아야 한다.
 */
class MarketPriceLookupTests {

    private final PriceService priceService = mock(PriceService.class);
    private final MarketPriceLookup lookup = new MarketPriceLookup(priceService);

    @Test
    void splitsSymbolsIntoRequestsOfAtMostTheApiLimit() {
        List<String> symbols = symbols(450);
        when(priceService.getPrices(anyList())).thenAnswer(invocation -> pricesOf(invocation.getArgument(0)));

        Map<String, BigDecimal> prices = lookup.lastPricesOf(symbols);

        ArgumentCaptor<List<String>> requested = listCaptor();
        verify(priceService, times(3)).getPrices(requested.capture());
        assertThat(requested.getAllValues()).extracting(List::size).containsExactly(200, 200, 50);
        assertThat(prices).hasSize(450).containsKeys("S0", "S449");
    }

    @Test
    void keepsPricesFetchedBeforeTheQuotaRunsOut() {
        // 이미 받은 시세까지 버리면 그 종목만 든 계좌도 평가하지 못한다.
        List<String> symbols = symbols(450);
        when(priceService.getPrices(anyList()))
            .thenAnswer(invocation -> pricesOf(invocation.getArgument(0)))
            .thenThrow(new TossApiQuotaUnavailableException("market-data", 1L, "예산 없음"));

        Map<String, BigDecimal> prices = lookup.lastPricesOf(symbols);

        // 같은 초에 남은 묶음을 불러도 또 실패하므로 세 번째 호출은 하지 않는다.
        verify(priceService, times(2)).getPrices(anyList());
        assertThat(prices).hasSize(200).containsKeys("S0", "S199").doesNotContainKey("S200");
    }

    @Test
    void doesNotCallTheApiWithoutSymbols() {
        assertThat(lookup.lastPricesOf(List.of())).isEmpty();
        verify(priceService, times(0)).getPrices(anyList());
    }

    private List<String> symbols(int count) {
        return IntStream.range(0, count).mapToObj(i -> "S" + i).toList();
    }

    private PriceResponse pricesOf(List<String> symbols) {
        return new PriceResponse(symbols.stream()
            .map(symbol -> new PriceResult(symbol, null, new BigDecimal("100.0000"), "USD"))
            .toList());
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<String>> listCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }
}
