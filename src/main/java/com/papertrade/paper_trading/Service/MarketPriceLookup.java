package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Client.TossApiQuotaUnavailableException;
import java.math.BigDecimal;
import java.util.HashMap;
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
 * <p>{@code getPrices()}는 한 번에 {@link PriceService#MAX_SYMBOL_COUNT}개까지만 받으므로 그 단위로 나눠
 * 부른다. 나누지 않으면 전체 보유 종목이 그 수를 넘는 순간 평가가 매 주기 통째로 실패한다.
 *
 * <p>예산이 소진되면 그때까지 모은 시세만 돌려준다. 같은 초에 남은 묶음을 불러도 또 실패할 뿐이다.
 * 시세가 빠진 계좌는 평가가 생략되고 다음 주기에 다시 하면 되므로, 표시용 값 하나 때문에
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

        Map<String, BigDecimal> pricesBySymbol = new HashMap<>();
        for (int fromIndex = 0; fromIndex < symbols.size(); fromIndex += PriceService.MAX_SYMBOL_COUNT) {
            List<String> chunk = symbols.subList(
                fromIndex, Math.min(fromIndex + PriceService.MAX_SYMBOL_COUNT, symbols.size()));
            try {
                priceService.getPrices(chunk).result().stream()
                    .filter(price -> price.symbol() != null && price.lastPrice() != null)
                    .forEach(price -> pricesBySymbol.putIfAbsent(price.symbol(), price.lastPrice()));
            } catch (TossApiQuotaUnavailableException e) {
                log.debug("Stopping price lookup because the price quota is unavailable. fetched={}, symbols={}",
                    pricesBySymbol.size(), symbols.size());
                break;
            }
        }
        return pricesBySymbol;
    }

    /** 보유 목록에서 조회할 종목 코드를 뽑는다. 같은 종목을 여러 계좌가 들고 있어도 한 번만 조회한다. */
    public static List<String> distinctSymbols(List<com.papertrade.paper_trading.Entity.Holding> holdings) {
        return holdings.stream()
            .map(holding -> holding.getStock().getSymbol())
            .distinct()
            .collect(Collectors.collectingAndThen(Collectors.toList(), Function.identity()));
    }
}
