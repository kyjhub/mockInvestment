package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Config.SchedulingConfig;
import com.papertrade.paper_trading.Repository.OrderRepository;
import com.papertrade.paper_trading.Repository.OrderRepository.AwaitingPriceOrder;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 현재가 없이 접수된 주문(접수 검증 대기)의 검증을 마친다.
 *
 * <p>현재가는 대기 주문 전체의 종목을 모아 한 번에 조회한다. 현재가 API는 요청 한 번에 200종목을 받으므로
 * 주문마다 부르는 것보다 예산이 훨씬 적게 든다. 주문 접수는 한 종목만 필요해 일봉 API를 쓰지만, 여기서는
 * 여러 종목을 다루므로 현재가 API가 맞다. 캐시에 있는 종목은 호출하지 않는다.
 *
 * <p>예산이 없어 현재가를 못 구한 주문은 그대로 대기하고 다음 주기에 다시 시도한다.
 * 대기가 길어져도 장 마감 실효가 상한이 된다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AwaitingPriceOrderScheduler {

    private final OrderRepository orderRepository;
    private final MarketPriceLookup marketPriceLookup;
    private final OrderTradingService orderTradingService;

    @Scheduled(
        scheduler = SchedulingConfig.MARKET_DATA_POLLING_SCHEDULER,
        fixedDelayString = "${order.awaiting-price.fixed-delay-ms:1000}"
    )
    public void confirmAwaitingOrders() {
        List<AwaitingPriceOrder> awaitingOrders = orderRepository.findAwaitingPriceOrders();
        if (awaitingOrders.isEmpty()) {
            return;
        }

        List<String> symbols = awaitingOrders.stream().map(AwaitingPriceOrder::symbol).distinct().toList();
        Map<String, BigDecimal> prices = marketPriceLookup.lastPricesOf(symbols);
        if (prices.isEmpty()) {
            return;
        }

        int confirmed = 0;
        for (AwaitingPriceOrder awaitingOrder : awaitingOrders) {
            BigDecimal currentPrice = prices.get(awaitingOrder.symbol());
            if (currentPrice == null || currentPrice.signum() <= 0) {
                continue;
            }
            try {
                // 주문 하나가 하나의 트랜잭션이다. 한 건이 실패해도 나머지가 막히지 않는다.
                if (orderTradingService.confirmAwaitingPrice(awaitingOrder.id(), currentPrice)) {
                    confirmed++;
                }
            } catch (RuntimeException e) {
                log.warn("Failed to confirm an awaiting-price order. orderId={}, reason={}",
                    awaitingOrder.id(), e.getMessage());
            }
        }
        log.debug("Confirmed awaiting-price orders. confirmed={}, awaiting={}", confirmed, awaitingOrders.size());
    }
}
