package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Client.TossApiQuotaUnavailableException;
import com.papertrade.paper_trading.Dto.PriceResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 평가에 쓸 현재가를 종목 코드 → 가격 map으로 모아 온다.
 *
 * <p>{@code PriceService.getPrices()}가 캐시에 없는 종목만 묶어 <b>한 번의 Toss 호출</b>로 가져오므로,
 * 계좌마다 부르지 말고 전체 보유의 distinct 종목을 한 번에 넘겨야 한다.
 *
 * <p>예산이 소진되면 빈 map을 돌려준다. 평가는 다음 주기에 다시 하면 되고, 표시용 값 하나 때문에
 * 호출자에게 예외를 퍼뜨릴 이유가 없다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketPriceLookup {

    private final PriceService priceService;

    public Map<String, BigDecimal> lastPricesOf(List<String> symbols) {
        if (symbols.isEmpty()) {
            return Map.of();
        }

        try {
            return priceService.getPrices(symbols).result().stream()
                .filter(price -> price.symbol() != null && price.lastPrice() != null)
                .collect(Collectors.toMap(
                    PriceResult::symbol,
                    PriceResult::lastPrice,
                    (first, second) -> first));
        } catch (TossApiQuotaUnavailableException e) {
            log.debug("Skipping valuation because the price quota is unavailable. symbols={}", symbols.size());
            return Map.of();
        }
    }

    /** 보유 목록에서 조회할 종목 코드를 뽑는다. 같은 종목을 여러 계좌가 들고 있어도 한 번만 조회한다. */
    public static List<String> distinctSymbols(List<com.papertrade.paper_trading.Entity.Holding> holdings) {
        return holdings.stream()
            .map(holding -> holding.getStock().getSymbol())
            .distinct()
            .collect(Collectors.collectingAndThen(Collectors.toList(), Function.identity()));
    }
}
