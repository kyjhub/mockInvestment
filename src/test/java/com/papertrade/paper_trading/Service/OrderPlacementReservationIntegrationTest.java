package com.papertrade.paper_trading.Service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.papertrade.paper_trading.Dto.AccountBalanceResponse;
import com.papertrade.paper_trading.Dto.OrderPlaceRequest;
import com.papertrade.paper_trading.Dto.OrderResponse;
import com.papertrade.paper_trading.Dto.PriceResponse;
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
import java.util.Optional;
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
 *
 * <p>현재가는 기본적으로 <b>모르는 상태</b>다. 현재가가 필요한 test만 {@link #givenCurrentPrice}로 정한다.
 * 모르는 상태에서 접수된 주문은 접수 검증 대기({@code AWAITING_PRICE})가 된다.
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

    /** 주문 접수에 쓰는 현재가. 외부 호출 없이 값을 고정한다. */
    @MockitoBean
    private PriceService priceService;

    /** 운영은 수수료 0이다. 수수료가 필요한 test만 {@link #givenCommissionRate}로 정한다. */
    @MockitoBean
    private CommissionCalculator commissionCalculator;

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
        // 잔고 화면의 평가용 시세. 이 class는 평가를 검증하지 않으므로 비워 둔다.
        when(priceService.getPrices(anyList())).thenReturn(new PriceResponse(List.of()));
        givenCommissionRate("0");
        when(commissionCalculator.tax(any())).thenReturn(BigDecimal.ZERO);
    }

    @Test
    void secondOrderIsRejectedBecauseTheFirstOneAlreadyReservedTheCash() {
        // 예수금 100만. 100만짜리 매수 주문은 하나만 받아들여야 한다.
        givenCurrentPrice("1000000.0000");
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
        givenCurrentPrice("1000000.0000");
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
        givenCurrentPrice("1000000.0000");
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
        givenCurrentPrice("400000.0000");
        orderTradingService.placeOrder(user, buyLimit("400000.0000", 1L));

        AccountBalanceResponse balance = accountQueryService.getBalance(user);

        // 예수금은 그대로다. 미체결 주문은 돈이 나간 게 아니라 묶여 있을 뿐이다.
        assertThat(balance.cashBalance()).isEqualByComparingTo("1000000.00");
        assertThat(balance.reservedCash()).isEqualByComparingTo("400000.00");
        assertThat(balance.orderableAmount()).isEqualByComparingTo("600000.00");
    }

    @Test
    void marketBuyBecomesALimitOrderTenPercentAboveTheCurrentPrice() {
        // 미국 거래소에는 그대로 낼 수 있는 시장가가 없어 국내 증권사는 직전 체결가 +10% 지정가로 낸다.
        givenCurrentPrice("100.0000");

        OrderResponse accepted = orderTradingService.placeOrder(user, marketBuy(1L));

        assertThat(accepted.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(accepted.orderPrice()).isEqualByComparingTo("110.0000");
        // 구속 단가는 바뀐 지정가 그 자체다. 그보다 비싸게 체결될 수 없으므로 정확하다.
        assertThat(accountQueryService.getBalance(user).reservedCash()).isEqualByComparingTo("110.00");
    }

    @Test
    void marketSellBecomesALimitOrderTenPercentBelowTheCurrentPrice() {
        holdingRepository.save(holdingWith(5L));
        givenCurrentPrice("100.0000");

        OrderResponse accepted = orderTradingService.placeOrder(user, marketSell(1L));

        assertThat(accepted.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(accepted.orderPrice()).isEqualByComparingTo("90.0000");
    }

    @Test
    void marketBuyIsCheckedAgainstTheOrderableAmountAtTheConvertedPrice() {
        // 500,000 × 1.1 = 550,000. 두 번째는 1,100,000이 되어 예수금 100만을 넘는다.
        givenCurrentPrice("500000.0000");
        orderTradingService.placeOrder(user, marketBuy(1L));

        assertThatThrownBy(() -> orderTradingService.placeOrder(user, marketBuy(1L)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("주문가능금액이 부족합니다.");
    }

    @Test
    void limitOrderFarOutsideTheBandIsRejected() {
        // 가격을 스스로 정해 손익을 만들어내는 경로를 좁힌다. 같은 계좌끼리는 매칭에서 막히지만,
        // 계정을 여러 개 만들어 터무니없는 가격에 맞붙이는 것은 이 검증이 막는다.
        givenCurrentPrice("100.0000");

        // 상한은 100 × 1.5 = 150
        assertThatThrownBy(() -> orderTradingService.placeOrder(user, buyLimit("151.0000", 1L)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("주문가격이 허용 범위를 벗어났습니다.");
    }

    @Test
    void limitOrderInsideTheBandIsAccepted() {
        // 저가 매수를 걸어 두는 것은 정상 거래다. 밴드가 이걸 막으면 안 된다.
        givenCurrentPrice("100.0000");

        // 하한은 100 × 0.5 = 50
        orderTradingService.placeOrder(user, buyLimit("50.0000", 1L));

        assertThat(acceptedOrderCount()).isEqualTo(1L);
    }

    @Test
    void limitOrderWithoutACurrentPriceIsAcceptedButNotMatchedUntilTheBandIsChecked() {
        // 현재가를 몰라도 주문은 받는다. 다만 밴드 검증을 거치지 않은 가격이 체결에 쓰이면 안 된다.
        OrderResponse accepted = orderTradingService.placeOrder(user, buyLimit("100000.0000", 1L));

        assertThat(accepted.status()).isEqualTo(OrderStatus.AWAITING_PRICE);
        assertThat(acceptedOrderCount()).isZero();
        // 지정가 매수는 구속 단가를 이미 알므로 대기 중에도 예수금을 묶는다.
        assertThat(accountQueryService.getBalance(user).reservedCash()).isEqualByComparingTo("100000.00");
    }

    @Test
    void awaitingLimitOrderOutsideTheBandIsRejectedOnceThePriceIsKnown() {
        OrderResponse accepted = orderTradingService.placeOrder(user, buyLimit("100000.0000", 1L));

        boolean confirmed = orderTradingService.confirmAwaitingPrice(accepted.orderId(), new BigDecimal("100.0000"));

        assertThat(confirmed).isFalse();
        Order order = orderRepository.findById(accepted.orderId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getCloseReason()).isEqualTo("주문가격이 허용 범위를 벗어났습니다.");
        assertThat(accountQueryService.getBalance(user).reservedCash()).isEqualByComparingTo("0.00");
    }

    @Test
    void awaitingLimitOrderInsideTheBandBecomesMatchable() {
        OrderResponse accepted = orderTradingService.placeOrder(user, buyLimit("100.0000", 1L));

        boolean confirmed = orderTradingService.confirmAwaitingPrice(accepted.orderId(), new BigDecimal("100.0000"));

        assertThat(confirmed).isTrue();
        assertThat(acceptedOrderCount()).isEqualTo(1L);
    }

    @Test
    void marketBuyWithoutACurrentPriceIsPricedWhenThePriceArrives() {
        OrderResponse accepted = orderTradingService.placeOrder(user, marketBuy(1L));

        // 가격이 없으니 구속액도 아직 모른다. 0으로 두고 검증은 가격을 알게 됐을 때 한다.
        assertThat(accepted.status()).isEqualTo(OrderStatus.AWAITING_PRICE);
        assertThat(accepted.orderPrice()).isNull();
        assertThat(accountQueryService.getBalance(user).reservedCash()).isEqualByComparingTo("0.00");

        assertThat(orderTradingService.confirmAwaitingPrice(accepted.orderId(), new BigDecimal("100.0000"))).isTrue();

        Order order = orderRepository.findById(accepted.orderId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getOrderPrice()).isEqualByComparingTo("110.0000");
        assertThat(accountQueryService.getBalance(user).reservedCash()).isEqualByComparingTo("110.00");
    }

    @Test
    void awaitingMarketBuyIsRejectedIfTheCashWasUsedUpWhileItWaited() {
        // 대기 중에는 구속액이 0이라 그사이 다른 주문이 예수금을 다 쓸 수 있다. 가격을 정할 때 다시 검증한다.
        OrderResponse awaiting = orderTradingService.placeOrder(user, marketBuy(1L));
        givenCurrentPrice("1000000.0000");
        orderTradingService.placeOrder(user, buyLimit("1000000.0000", 1L));

        boolean confirmed = orderTradingService.confirmAwaitingPrice(awaiting.orderId(), new BigDecimal("100.0000"));

        assertThat(confirmed).isFalse();
        Order order = orderRepository.findById(awaiting.orderId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getCloseReason()).isEqualTo("주문가능금액이 부족합니다.");
    }

    @Test
    void awaitingOrderCanBeCanceledAndIsNotConfirmedAfterwards() {
        OrderResponse awaiting = orderTradingService.placeOrder(user, marketBuy(1L));

        orderTradingService.cancelOrder(user, awaiting.orderId());

        // 취소와 검증이 엇갈려도 취소된 주문이 되살아나면 안 된다.
        assertThat(orderTradingService.confirmAwaitingPrice(awaiting.orderId(), new BigDecimal("100.0000"))).isFalse();
        assertThat(orderRepository.findById(awaiting.orderId()).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.CANCELED);
    }

    @Test
    void awaitingSellOrdersStillHoldTheirShares() {
        // 대기 중인 매도도 수량을 묶어야 같은 주식을 두 번 팔지 못한다.
        holdingRepository.save(holdingWith(5L));
        orderTradingService.placeOrder(user, marketSell(5L));

        assertThatThrownBy(() -> orderTradingService.placeOrder(user, marketSell(1L)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("매도가능수량이 부족합니다.");
    }

    @Test
    void feesOfOpenOrdersStayReservedSoTheNextOrderCannotUseThem() {
        // 1% 수수료. 첫 주문은 495,000 + 4,950을 묶는다. 수수료를 빼고 체결대금만 묶으면
        // 주문가능금액이 505,000으로 보여 두 번째 주문(500,000 + 5,000)이 통과하고, 예수금을 4,950 넘긴다.
        givenCommissionRate("0.01");
        givenCurrentPrice("495000.0000");
        orderTradingService.placeOrder(user, buyLimit("495000.0000", 1L));

        AccountBalanceResponse balance = accountQueryService.getBalance(user);
        assertThat(balance.reservedCash()).isEqualByComparingTo("499950.00");
        assertThat(balance.orderableAmount()).isEqualByComparingTo("500050.00");

        givenCurrentPrice("500000.0000");
        assertThatThrownBy(() -> orderTradingService.placeOrder(user, buyLimit("500000.0000", 1L)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("주문가능금액이 부족합니다.");
    }

    @Test
    void sellOrdersAreCappedByHeldQuantityAcrossOrders() {
        givenCurrentPrice("100.0000");
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
        givenCurrentPrice("100000.0000");
        var accepted = orderTradingService.placeOrder(user, buyLimit("100000.0000", 10L));
        assertThat(accountQueryService.getBalance(user).reservedCash()).isEqualByComparingTo("1000000.00");

        Order order = orderRepository.findById(accepted.orderId()).orElseThrow();
        order.fill(6L, new BigDecimal("600000.0000"), BigDecimal.ZERO, BigDecimal.ZERO);
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

    private OrderPlaceRequest marketSell(long quantity) {
        return new OrderPlaceRequest(null, stock.getSymbol(), OrderSide.SELL, OrderType.MARKET, null, quantity);
    }

    private void givenCommissionRate(String rate) {
        BigDecimal commissionRate = new BigDecimal(rate);
        // doAnswer 형태여야 한다. when(...)으로 다시 스텁하면 이전 answer가 null 인자로 한 번 실행된다.
        doAnswer(call -> call.<BigDecimal>getArgument(0).multiply(commissionRate))
            .when(commissionCalculator).commission(any());
    }

    private void givenCurrentPrice(String price) {
        when(priceService.findPriceForOrder(anyString())).thenReturn(Optional.of(new BigDecimal(price)));
    }

    private Holding holdingWith(long quantity) {
        Holding holding = Holding.create(account, stock);
        holding.buy(quantity, new BigDecimal("100.0000"));
        return holding;
    }
}
