package com.papertrade.paper_trading.Service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.papertrade.paper_trading.Dto.AccountBalanceResponse;
import com.papertrade.paper_trading.Dto.DailyPriceRangeResponse;
import com.papertrade.paper_trading.Dto.OrderPlaceRequest;
import com.papertrade.paper_trading.Entity.Account;
import com.papertrade.paper_trading.Entity.Holding;
import com.papertrade.paper_trading.Entity.Order;
import com.papertrade.paper_trading.Entity.Stock;
import com.papertrade.paper_trading.Entity.User;
import com.papertrade.paper_trading.Enum.AccountStatus;
import com.papertrade.paper_trading.Enum.Market;
import com.papertrade.paper_trading.Enum.OrderSide;
import com.papertrade.paper_trading.Enum.OrderStatus;
import com.papertrade.paper_trading.Enum.OrderType;
import com.papertrade.paper_trading.Repository.AccountRepository;
import com.papertrade.paper_trading.Repository.HoldingRepository;
import com.papertrade.paper_trading.Repository.OrderRepository;
import com.papertrade.paper_trading.Repository.StockRepository;
import com.papertrade.paper_trading.Repository.UserRepository;
import com.papertrade.paper_trading.Enum.Role;
import com.papertrade.paper_trading.Enum.SecurityType;
import com.papertrade.paper_trading.Enum.Status;
import com.papertrade.paper_trading.Enum.StockStatus;
import com.papertrade.paper_trading.support.ApplicationIntegrationTest;
import com.papertrade.paper_trading.support.IntegrationTestContainers;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 예수금을 넘는 주문은 접수되지 않아야 한다.
 *
 * <p>이 명제는 단위 테스트로 증명할 수 없다. 핵심이 "동시에 들어와도" 이기 때문이고, 그건 실제
 * transaction과 row lock이 있어야 재현된다. 그래서 이 class는 통합 테스트다.
 *
 * <p>가용잔고를 컬럼이 아니라 주문에서 파생하므로, 취소·거절 후 회복에 해제 코드가 없다는 점도
 * 함께 고정한다 — 해제를 빠뜨릴 코드 자체가 없다는 것이 이 설계의 요지다.
 */
@ApplicationIntegrationTest
class OrderPlacementReservationIntegrationTest extends IntegrationTestContainers {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final List<OrderStatus> LIVE = List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);

    @Autowired
    private OrderTradingService orderTradingService;

    @Autowired
    private AccountQueryService accountQueryService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private HoldingRepository holdingRepository;

    @Autowired
    private OrderRepository orderRepository;

    /** 시장가 구속 단가의 근거. 외부 호출 없이 값을 고정한다. */
    @MockitoBean
    private DailyPriceRangeService dailyPriceRangeService;

    private User user;
    private Account account;
    private Stock stock;

    @BeforeEach
    void setUp() {
        long suffix = SEQUENCE.incrementAndGet();
        user = userRepository.save(User.builder()
            .email("reserve-" + suffix + "@example.com")
            .passwordHash("test")
            .nickname("reserve-" + suffix)
            .status(Status.ACTIVE)
            .role(Role.USER)
            .build());
        account = accountRepository.save(Account.builder()
            .user(user)
            .accountNumber("ACC-R-" + suffix)
            .cashBalance(new BigDecimal("1000000.00"))
            .initialBalance(new BigDecimal("1000000.00"))
            .totalAssetValue(new BigDecimal("1000000.00"))
            .status(AccountStatus.ACTIVE)
            .build());
        stock = stockRepository.save(Stock.builder()
            .symbol("RSV" + suffix)
            .name("reserve " + suffix)
            .market(Market.NASDAQ)
            .securityType(SecurityType.STOCK)
            .isCommonShare(true)
            .status(StockStatus.ACTIVE)
            .currency("USD")
            .build());
    }

    @Test
    void secondOrderIsRejectedBecauseTheFirstOneAlreadyReservedTheCash() {
        // 예수금 100만. 100만짜리 매수 주문은 하나만 받아들여야 한다.
        orderTradingService.placeOrder(user, buyLimit("1000000.0000", 1L));

        assertThatThrownBy(() -> orderTradingService.placeOrder(user, buyLimit("1000000.0000", 1L)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("주문가능금액이 부족합니다.");

        assertThat(acceptedOrderCount()).isEqualTo(1L);
    }

    @Test
    void concurrentPlacementsCannotBothPassTheSameOrderableAmount() throws Exception {
        // 계좌 row를 잠그지 않으면 두 요청이 같은 가용잔고를 읽고 둘 다 통과한다.
        // 이 테스트가 그 lock을 지킨다.
        int attempts = 4;
        CyclicBarrier barrier = new CyclicBarrier(attempts);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);

        try {
            List<Future<Boolean>> results = pool.invokeAll(java.util.Collections.nCopies(
                attempts,
                (Callable<Boolean>) () -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        orderTradingService.placeOrder(user, buyLimit("1000000.0000", 1L));
                        return true;
                    } catch (IllegalArgumentException expected) {
                        return false;
                    }
                }
            ));

            long accepted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }
            assertThat(accepted).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }

        assertThat(acceptedOrderCount()).isEqualTo(1L);
    }

    /** test 간 데이터가 남으므로(@SpringBootTest는 롤백하지 않는다) 이 test가 만든 종목 기준으로 센다. */
    private long acceptedOrderCount() {
        return orderRepository.findMatchableOrdersBySymbol(stock.getSymbol(), LIVE).size();
    }

    @Test
    void cancelingAnOrderRestoresTheOrderableAmountWithoutAnyReleaseCode() {
        var accepted = orderTradingService.placeOrder(user, buyLimit("1000000.0000", 1L));
        assertThat(accountQueryService.getBalance(user).orderableAmount()).isEqualByComparingTo("0.00");

        orderTradingService.cancelOrder(user, accepted.orderId());

        // 구속을 주문에서 파생하므로 취소가 status를 바꾸는 것만으로 합계에서 빠진다.
        AccountBalanceResponse balance = accountQueryService.getBalance(user);
        assertThat(balance.reservedCash()).isEqualByComparingTo("0.00");
        assertThat(balance.orderableAmount()).isEqualByComparingTo("1000000.00");
    }

    @Test
    void balanceSeparatesDepositFromOrderableAmount() {
        orderTradingService.placeOrder(user, buyLimit("400000.0000", 1L));

        AccountBalanceResponse balance = accountQueryService.getBalance(user);

        // 예수금은 그대로다. 미체결 주문은 돈이 나간 게 아니라 묶여 있을 뿐이다.
        assertThat(balance.cashBalance()).isEqualByComparingTo("1000000.00");
        assertThat(balance.reservedCash()).isEqualByComparingTo("400000.00");
        assertThat(balance.orderableAmount()).isEqualByComparingTo("600000.00");
    }

    @Test
    void marketBuyReservesAtTheDailyHighPrice() {
        when(dailyPriceRangeService.getDailyPriceRange(anyString()))
            .thenReturn(new DailyPriceRangeResponse(stock.getSymbol(), null, new BigDecimal("600000.0000"), null, "USD"));

        orderTradingService.placeOrder(user, marketBuy(1L));

        assertThat(accountQueryService.getBalance(user).reservedCash()).isEqualByComparingTo("600000.00");
        // 같은 기준으로 두 번째는 막힌다 (600,000 × 2 > 1,000,000).
        assertThatThrownBy(() -> orderTradingService.placeOrder(user, marketBuy(1L)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limitOrderFarOutsideTheDailyRangeIsRejected() {
        // 가격을 스스로 정해 손익을 만들어내는 경로를 좁힌다. 같은 계좌끼리는 매칭에서 막히지만,
        // 계정을 여러 개 만들어 터무니없는 가격에 맞붙이는 것은 이 검증이 막는다.
        when(dailyPriceRangeService.getDailyPriceRange(anyString())).thenReturn(
            new DailyPriceRangeResponse(stock.getSymbol(), null,
                new BigDecimal("110.0000"), new BigDecimal("90.0000"), "USD"));

        assertThatThrownBy(() -> orderTradingService.placeOrder(user, buyLimit("100000.0000", 1L)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("주문가격이 허용 범위를 벗어났습니다.");
    }

    @Test
    void limitOrderInsideTheBandIsAccepted() {
        // 저가 매수를 걸어 두는 것은 정상 거래다. 밴드가 이걸 막으면 안 된다.
        when(dailyPriceRangeService.getDailyPriceRange(anyString())).thenReturn(
            new DailyPriceRangeResponse(stock.getSymbol(), null,
                new BigDecimal("110.0000"), new BigDecimal("90.0000"), "USD"));

        // 하한은 90 × 0.7 = 63
        orderTradingService.placeOrder(user, buyLimit("70.0000", 1L));

        assertThat(acceptedOrderCount()).isEqualTo(1L);
    }

    @Test
    void priceBandIsSkippedWhenTheDailyRangeIsUnavailable() {
        // 외부 시세가 잠깐 막혔다고 정상 주문까지 거절하면 손해가 더 크다.
        when(dailyPriceRangeService.getDailyPriceRange(anyString())).thenReturn(
            new DailyPriceRangeResponse(stock.getSymbol(), null, null, null, "USD"));

        orderTradingService.placeOrder(user, buyLimit("100000.0000", 1L));

        assertThat(acceptedOrderCount()).isEqualTo(1L);
    }

    @Test
    void marketBuyIsRejectedWhenTheDailyHighPriceIsUnknown() {
        when(dailyPriceRangeService.getDailyPriceRange(anyString()))
            .thenReturn(new DailyPriceRangeResponse(stock.getSymbol(), null, null, null, "USD"));

        assertThatThrownBy(() -> orderTradingService.placeOrder(user, marketBuy(1L)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("시장가 주문을 받을 수 없습니다");
    }

    @Test
    void sellOrdersAreCappedByHeldQuantityAcrossOrders() {
        holdingRepository.save(holdingWith(5L));

        orderTradingService.placeOrder(user, sellLimit("100.0000", 5L));

        assertThatThrownBy(() -> orderTradingService.placeOrder(user, sellLimit("100.0000", 5L)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("매도가능수량이 부족합니다.");
    }

    @Test
    void sellOrderWithoutAnyHoldingIsRejected() {
        assertThatThrownBy(() -> orderTradingService.placeOrder(user, sellLimit("100.0000", 1L)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("매도가능수량이 부족합니다.");
    }

    @Test
    void reservationShrinksWithTheRemainingQuantityAfterAPartialFill() {
        var accepted = orderTradingService.placeOrder(user, buyLimit("100000.0000", 10L));
        assertThat(accountQueryService.getBalance(user).reservedCash()).isEqualByComparingTo("1000000.00");

        Order order = orderRepository.findById(accepted.orderId()).orElseThrow();
        order.fill(6L);
        orderRepository.saveAndFlush(order);

        // 잔여 4주만 묶여 있어야 한다. 구속액을 줄이는 별도 코드는 없다.
        assertThat(accountQueryService.getBalance(user).reservedCash()).isEqualByComparingTo("400000.00");
    }

    private OrderPlaceRequest buyLimit(String price, long quantity) {
        return new OrderPlaceRequest(null, stock.getSymbol(), OrderSide.BUY, OrderType.LIMIT,
            new BigDecimal(price), quantity);
    }

    private OrderPlaceRequest sellLimit(String price, long quantity) {
        return new OrderPlaceRequest(null, stock.getSymbol(), OrderSide.SELL, OrderType.LIMIT,
            new BigDecimal(price), quantity);
    }

    private OrderPlaceRequest marketBuy(long quantity) {
        return new OrderPlaceRequest(null, stock.getSymbol(), OrderSide.BUY, OrderType.MARKET, null, quantity);
    }

    private Holding holdingWith(long quantity) {
        Holding holding = Holding.create(account, stock);
        holding.buy(quantity, new BigDecimal("100.0000"));
        return holding;
    }
}
