package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Dto.AccountValuation;
import com.papertrade.paper_trading.Entity.Holding;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 보유와 시세로 계좌 자산을 평가한다.
 *
 * <p>시세 조회를 하지 않고 <b>가격 map을 받는다.</b> 그래야 여러 계좌를 평가할 때 시세를 한 번만
 * 조회할 수 있고, 이 계산 자체는 외부 의존 없이 test할 수 있다.
 */
@Service
@Slf4j
public class ValuationService {

    private static final int MONEY_SCALE = 2;
    private static final int RATE_SCALE = 6;

    /**
     * @param pricesBySymbol 종목 코드 → 현재가
     * @return 평가 결과. 아래 경우에는 <b>비어 있다</b> — 일부만 반영한 총자산은 틀린 값이고,
     *     틀린 값을 보여주는 것보다 "계산 중"이 낫다.
     *     <ul>
     *       <li>보유 종목의 통화가 둘 이상일 때 (환산 수단이 없다)</li>
     *       <li>시세를 구하지 못한 종목이 하나라도 있을 때</li>
     *     </ul>
     */
    public Optional<AccountValuation> value(
        Long accountId,
        BigDecimal cashBalance,
        Collection<Holding> openPositions,
        Map<String, BigDecimal> pricesBySymbol
    ) {
        if (openPositions.isEmpty()) {
            // 보유가 없으면 총자산은 예수금 그대로다. 수익률은 분모가 없어 null이다.
            return Optional.of(new AccountValuation(
                money(BigDecimal.ZERO), money(BigDecimal.ZERO), money(BigDecimal.ZERO),
                null, money(cashBalance)));
        }

        // 계좌에 통화 개념이 없으므로(스키마에 없다) 보유 통화가 섞이면 예수금과 더할 수 없다.
        // 지금은 거래 가능한 종목이 전부 USD라 성립하지만, 국내 종목이 들어오는 순간 조용히 틀린 값이 나온다.
        // 그때 드러나도록 주석이 아니라 코드로 막는다.
        Set<String> currencies = openPositions.stream()
            .map(holding -> holding.getStock().getCurrency())
            .collect(Collectors.toSet());
        if (currencies.size() > 1) {
            log.error("Cannot value a multi-currency account without FX. accountId={}, currencies={}",
                accountId, currencies);
            return Optional.empty();
        }

        BigDecimal stockEvaluation = BigDecimal.ZERO;
        BigDecimal totalPurchaseAmount = BigDecimal.ZERO;
        for (Holding holding : openPositions) {
            String symbol = holding.getStock().getSymbol();
            BigDecimal price = pricesBySymbol.get(symbol);
            if (price == null || price.signum() <= 0) {
                log.warn("Skipping valuation because a price is unavailable. accountId={}, symbol={}",
                    accountId, symbol);
                return Optional.empty();
            }
            stockEvaluation = stockEvaluation.add(price.multiply(BigDecimal.valueOf(holding.getQuantity())));
            totalPurchaseAmount = totalPurchaseAmount.add(holding.getTotalPurchaseAmount());
        }

        BigDecimal evaluation = money(stockEvaluation);
        BigDecimal purchaseAmount = money(totalPurchaseAmount);
        BigDecimal unrealizedProfit = evaluation.subtract(purchaseAmount);

        return Optional.of(new AccountValuation(
            purchaseAmount,
            evaluation,
            unrealizedProfit,
            // 총매입금액이 0이면 나눌 수 없다. 0%가 아니라 수익률이라는 개념이 없는 상태다.
            purchaseAmount.signum() == 0
                ? null
                : unrealizedProfit.divide(purchaseAmount, RATE_SCALE, RoundingMode.HALF_UP),
            money(cashBalance.add(evaluation))
        ));
    }

    private BigDecimal money(BigDecimal amount) {
        return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
