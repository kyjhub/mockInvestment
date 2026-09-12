package com.papertrade.paper_trading.Matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.papertrade.paper_trading.Dto.DailyPriceRangeResponse;
import com.papertrade.paper_trading.Dto.OrderBookLevel;
import com.papertrade.paper_trading.Dto.OrderBookResponse;
import com.papertrade.paper_trading.Dto.OrderBookResult;
import com.papertrade.paper_trading.Entity.Account;
import com.papertrade.paper_trading.Entity.Holding;
import com.papertrade.paper_trading.Entity.Order;
import com.papertrade.paper_trading.Entity.Stock;
import com.papertrade.paper_trading.Entity.User;
import com.papertrade.paper_trading.Enum.Market;
import com.papertrade.paper_trading.Enum.OrderSide;
import com.papertrade.paper_trading.Enum.OrderType;
import com.papertrade.paper_trading.Enum.Role;
import com.papertrade.paper_trading.Enum.SecurityType;
import com.papertrade.paper_trading.Enum.Status;
import com.papertrade.paper_trading.Enum.StockStatus;
import com.papertrade.paper_trading.Repository.HoldingRepository;
import com.papertrade.paper_trading.Repository.OrderRepository;
import com.papertrade.paper_trading.Repository.StockRepository;
import com.papertrade.paper_trading.Repository.UserRepository;
import com.papertrade.paper_trading.Service.AccountOpeningService;
import com.papertrade.paper_trading.Service.MatchingEngineTransactionService;
import com.papertrade.paper_trading.Service.OrderBookService;
import com.papertrade.paper_trading.support.ApplicationIntegrationTest;
import com.papertrade.paper_trading.support.IntegrationTestContainers;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 가격-시간 우선순위는 타협 대상이 아니다.
 *
 * <p>우선순위에는 두 축이 있다. 하나는 "어떤 상대와 체결하는가", 다른 하나는 <b>"여러 주문 중 누가
 * 먼저 채워지는가"</b>다. 두 번째 축은 매칭 로직만 봐서는 지켜지는지 알 수 없다 — 한 주문이
 * 체결 가능한 물량을 남겨 둔 채 중도에 멈추면, 뒤에 선 주문이 그 물량을 가져가기 때문이다.
 *
 * <p>이 class는 그 두 번째 축을 고정한다.
 */
@ApplicationIntegrationTest
class PriceTimePriorityIntegrationTest extends IntegrationTestContainers {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    @Autowired private AccountOpeningService accountOpeningService;
    @Autowired private MatchingEngineTransactionService matchingEngine;
    @Autowired private UserRepository userRepository;
    @Autowired private StockRepository stockRepository;
    @Autowired private HoldingRepository holdingRepository;
    @Autowired private OrderRepository orderRepository;

    @MockitoBean private OrderBookService orderBookService;

    @Test
    void theEarlierBuyTakesAllSupplyBeforeTheLaterBuyGetsAnything() {
        // 공급 100주를 두 매수 주문이 다툰다. 먼저 접수된 쪽이 전부 가져가야 한다.
        // 매도자를 25계좌로 흩어 한 주문이 많은 상대와 체결해야만 다 채워지는 상황을 만든다.
        Stock stock = persistStock();
        Account earlyBuyer = openAccount("100000000.00");
        Account lateBuyer = openAccount("100000000.00");

        Order earlierBuy = persistOrder(earlyBuyer, stock, OrderSide.BUY, "1000.0000", 100L);
        Order laterBuy = persistOrder(lateBuyer, stock, OrderSide.BUY, "1000.0000", 100L);

        for (int i = 0; i < 25; i++) {
            Account seller = openAccount("1000.00");
            holdingRepository.save(holdingOf(seller, stock, 4L, "1000.0000"));
            persistOrder(seller, stock, OrderSide.SELL, "1000.0000", 4L);
        }

        matchSymbol(stock);

        assertThat(filledOf(earlierBuy)).as("먼저 접수된 매수가 공급 전량을 가져가야 한다").isEqualTo(100L);
        assertThat(filledOf(laterBuy)).as("뒤에 접수된 매수는 앞 주문이 다 채워지기 전에는 0이어야 한다").isZero();
    }

    @Test
    void theBetterPricedBuyTakesAllSupplyEvenWhenSubmittedLater() {
        // 가격이 시간보다 우선이다. 나중에 들어왔어도 비싸게 부른 쪽이 먼저다.
        Stock stock = persistStock();
        Account cheapBuyer = openAccount("100000000.00");
        Account richBuyer = openAccount("100000000.00");

        Order cheaperBuy = persistOrder(cheapBuyer, stock, OrderSide.BUY, "1000.0000", 100L);
        Order richerBuy = persistOrder(richBuyer, stock, OrderSide.BUY, "1500.0000", 100L);

        for (int i = 0; i < 25; i++) {
            Account seller = openAccount("1000.00");
            holdingRepository.save(holdingOf(seller, stock, 4L, "900.0000"));
            persistOrder(seller, stock, OrderSide.SELL, "900.0000", 4L);
        }

        matchSymbol(stock);

        assertThat(filledOf(richerBuy)).as("비싸게 부른 매수가 먼저 채워져야 한다").isEqualTo(100L);
        assertThat(filledOf(cheaperBuy)).isZero();
    }

    @Test
    void theEarlierSellTakesAllDemandBeforeTheLaterSellGetsAnything() {
        // 매도 방향 대칭.
        Stock stock = persistStock();
        Account earlySeller = openAccount("1000.00");
        Account lateSeller = openAccount("1000.00");
        holdingRepository.save(holdingOf(earlySeller, stock, 100L, "1000.0000"));
        holdingRepository.save(holdingOf(lateSeller, stock, 100L, "1000.0000"));

        Order earlierSell = persistOrder(earlySeller, stock, OrderSide.SELL, "1000.0000", 100L);
        Order laterSell = persistOrder(lateSeller, stock, OrderSide.SELL, "1000.0000", 100L);

        for (int i = 0; i < 25; i++) {
            Account buyer = openAccount("1000000.00");
            persistOrder(buyer, stock, OrderSide.BUY, "1000.0000", 4L);
        }

        matchSymbol(stock);

        assertThat(filledOf(earlierSell)).isEqualTo(100L);
        assertThat(filledOf(laterSell)).isZero();
    }

    // --- helpers ---

    private long filledOf(Order order) {
        return orderRepository.findById(order.getId()).orElseThrow().getFilledQuantity();
    }

    private void matchSymbol(Stock stock) {
        when(orderBookService.getOrderBookForMatching(anyString(), any())).thenReturn(emptyOrderBook());
        matchingEngine.matchSymbol(stock.getSymbol(), dailyPriceRange());
    }

    private Account openAccount(String initialAmount) {
        long suffix = SEQUENCE.incrementAndGet();
        User user = userRepository.save(User.builder()
            .email("prio-" + suffix + "@example.com").passwordHash("test")
            .nickname("prio-" + suffix).status(Status.ACTIVE).role(Role.USER).build());
        return accountOpeningService.open(user, new BigDecimal(initialAmount));
    }

    private Stock persistStock() {
        long suffix = SEQUENCE.incrementAndGet();
        return stockRepository.save(Stock.builder()
            .symbol("PR" + suffix).name("priority " + suffix).market(Market.NASDAQ)
            .securityType(SecurityType.STOCK).isCommonShare(true)
            .status(StockStatus.ACTIVE).currency("USD").build());
    }

    private Order persistOrder(Account account, Stock stock, OrderSide side, String price, long quantity) {
        BigDecimal unitPrice = new BigDecimal(price);
        return orderRepository.saveAndFlush(Order.create(
            account, stock, null, side, OrderType.LIMIT, unitPrice, quantity,
            side == OrderSide.BUY ? unitPrice : null));
    }

    private Holding holdingOf(Account account, Stock stock, long quantity, String averagePrice) {
        Holding holding = Holding.create(account, stock);
        holding.buy(quantity, new BigDecimal(averagePrice));
        return holding;
    }

    private OrderBookResponse emptyOrderBook() {
        return new OrderBookResponse(new OrderBookResult(null, "USD",
            List.<OrderBookLevel>of(), List.<OrderBookLevel>of()), LocalDateTime.now());
    }

    private DailyPriceRangeResponse dailyPriceRange() {
        return new DailyPriceRangeResponse("PR", null, new BigDecimal("3000.0000"),
            new BigDecimal("500.0000"), "USD");
    }
}
