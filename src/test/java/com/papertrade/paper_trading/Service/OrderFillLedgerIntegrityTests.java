package com.papertrade.paper_trading.Service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.papertrade.paper_trading.Dto.OrderBookLevel;
import com.papertrade.paper_trading.Dto.OrderBookResponse;
import com.papertrade.paper_trading.Dto.OrderBookResult;
import com.papertrade.paper_trading.Entity.Account;
import com.papertrade.paper_trading.Entity.Holding;
import com.papertrade.paper_trading.Entity.Order;
import com.papertrade.paper_trading.Entity.Stock;
import com.papertrade.paper_trading.Enum.AccountStatus;
import com.papertrade.paper_trading.Enum.OrderSide;
import com.papertrade.paper_trading.Enum.OrderStatus;
import com.papertrade.paper_trading.Enum.OrderType;
import com.papertrade.paper_trading.Repository.AccountRepository;
import com.papertrade.paper_trading.Repository.ExecutionRepository;
import com.papertrade.paper_trading.Repository.HoldingRepository;
import com.papertrade.paper_trading.Repository.LedgerEntryRepository;
import com.papertrade.paper_trading.Repository.LedgerTransactionRepository;
import com.papertrade.paper_trading.Repository.OrderRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 체결 실패 경로의 원장 무결성.
 *
 * <p>핵심은 <b>루프 중간에 예외를 던지지 않는 것</b>이다. 예외는 같은 트랜잭션에서 이미 성사된 체결까지
 * 롤백시키고, 재시도해도 같은 자리에서 또 막히므로 체결 가능한 물량마저 영영 체결되지 않는다.
 * 그래서 잔고·보유 제약은 체결을 시도한 뒤 예외로 발견하는 게 아니라 수량을 정하기 전에 캡으로 씌운다.
 */
class OrderFillLedgerIntegrityTests {

    private static final String SYMBOL = "AAPL";
    private static final Stock STOCK = Stock.builder().id(10L).symbol(SYMBOL).build();

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);
    private final HoldingRepository holdingRepository = mock(HoldingRepository.class);
    /** 진짜 posting service를 쓴다. 분개 합계가 0이 아니면 모든 체결 테스트가 바로 깨진다. */
    private final LedgerPostingService ledgerPostingService = new LedgerPostingService(
        savingMock(LedgerTransactionRepository.class),
        savingMock(LedgerEntryRepository.class)
    );
    private final OrderBookService orderBookService = mock(OrderBookService.class);
    private CommissionCalculator commissionCalculator = RateCommissionCalculator.free();

    private final Map<Long, Account> accounts = new HashMap<>();
    private final Map<Long, Holding> holdings = new HashMap<>();

    private MatchingEngineTransactionService service;

    @BeforeEach
    void setUp() {
        service = newService();

        when(accountRepository.findByIdForUpdate(anyLong()))
            .thenAnswer(call -> Optional.ofNullable(accounts.get(call.getArgument(0, Long.class))));
        when(holdingRepository.findByAccountIdAndStockId(anyLong(), anyLong()))
            .thenAnswer(call -> Optional.ofNullable(holdings.get(call.getArgument(0, Long.class))));
        when(holdingRepository.findForJudgementByAccountIdAndStockId(anyLong(), anyLong()))
            .thenAnswer(call -> Optional.ofNullable(holdings.get(call.getArgument(0, Long.class))));
        when(holdingRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(executionRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        // 기본값은 "내부 상대 없음". 필요한 테스트에서만 덮어쓴다.
        when(orderRepository.findMatchableSellOrders(anyCollection(), anyLong(), anyLong(), any(), anyList(), any()))
            .thenReturn(List.of());
        when(orderRepository.findMatchableBuyOrders(anyCollection(), anyLong(), anyLong(), any(), anyList(), any()))
            .thenReturn(List.of());
    }

    @Test
    void partialFillSurvivesWhenCashRunsOutMidSweep() {
        // 잔고 100은 60짜리 한 주만 감당한다. 두 번째 level에서 예외를 던지면 첫 체결까지 롤백되어
        // 재시도해도 영원히 0주로 남는다. 감당 가능한 1주는 반드시 살아남아야 한다.
        Account buyer = account(1L, "100.00");
        Order buyOrder = buyOrder(100L, buyer, "60.0000", 2L);
        givenOrder(buyOrder);

        service.matchOrder(100L, orderBook(ask("60.0000", 1L), ask("60.0000", 1L)));

        assertThat(buyOrder.getFilledQuantity()).isEqualTo(1L);
        assertThat(buyer.getCashBalance()).isEqualByComparingTo("40.00");
        // 체결 이력이 있으므로 REJECTED가 아니라 잔량 취소로 종료한다.
        assertThat(buyOrder.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(buyOrder.getCloseReason()).isEqualTo("주문 가능 금액이 부족합니다.");
    }

    @Test
    void internalSellIsCappedByTheSellersHolding() {
        // 보유 5주 / 매도 주문 10주 / 내부 매수 10주. 캡이 없으면 예외가 나고 전량 롤백된다.
        Account seller = account(1L, "0.00");
        Account buyer = account(2L, "10000.00");
        holding(seller, 5L, "50.0000");
        Order sellOrder = sellOrder(100L, seller, "50.0000", 10L);
        Order internalBuy = buyOrder(200L, buyer, "50.0000", 10L);
        givenOrder(sellOrder);
        givenInternalBuyOrders(internalBuy);

        service.matchOrder(100L, orderBook());

        assertThat(sellOrder.getFilledQuantity()).isEqualTo(5L);
        assertThat(internalBuy.getFilledQuantity()).isEqualTo(5L);
        assertThat(seller.getCashBalance()).isEqualByComparingTo("250.00");
        // 보유가 0이 되었고 체결 이력이 있으므로 잔량 취소로 종료한다.
        assertThat(sellOrder.getStatus()).isEqualTo(OrderStatus.CANCELED);
    }

    @Test
    void counterpartyWithoutHoldingIsSkippedInsteadOfBlockingTheOrder() {
        // 보유가 없는 매도 주문이 후보로 잡혀도, 그 하나 때문에 매수 주문이 막히면 안 된다.
        // 제외 목록에 넣고 다음 후보(여기서는 외부 호가)로 넘어가야 한다.
        Account buyer = account(1L, "10000.00");
        Account emptySeller = account(2L, "0.00");
        Order buyOrder = buyOrder(100L, buyer, "60.0000", 2L);
        Order phantomSell = sellOrder(200L, emptySeller, "50.0000", 2L);
        givenOrder(buyOrder);
        givenInternalSellOrders(phantomSell);

        service.matchOrder(100L, orderBook(ask("55.0000", 2L)));

        assertThat(buyOrder.getFilledQuantity()).isEqualTo(2L);
        assertThat(buyOrder.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(buyer.getCashBalance()).isEqualByComparingTo("9890.00");
    }

    @Test
    void buyOrderWithNoCashIsRejectedInsteadOfRetryingForever() {
        Account buyer = account(1L, "10.00");
        Order buyOrder = buyOrder(100L, buyer, "60.0000", 1L);
        givenOrder(buyOrder);

        service.matchOrder(100L, orderBook(ask("60.0000", 1L)));

        assertThat(buyOrder.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(buyOrder.getCloseReason()).isEqualTo("주문 가능 금액이 부족합니다.");
        assertThat(buyOrder.getFilledQuantity()).isZero();
    }

    @Test
    void sellOrderWithoutHoldingIsRejected() {
        Account seller = account(1L, "0.00");
        Order sellOrder = sellOrder(100L, seller, "50.0000", 1L);
        givenOrder(sellOrder);

        service.matchOrder(100L, orderBook());

        assertThat(sellOrder.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(sellOrder.getCloseReason()).isEqualTo("보유 수량이 부족합니다.");
    }

    @Test
    void orderWithNoCounterpartyStaysPendingInsteadOfBeingRejected() {
        // 유동성이 없어서 체결이 안 된 것은 계좌 문제가 아니다. 거절하면 호가가 잠깐 빈 종목의
        // 정상 주문이 전부 종료된다.
        Account buyer = account(1L, "1.00");
        Order buyOrder = buyOrder(100L, buyer, "60.0000", 1L);
        givenOrder(buyOrder);

        service.matchOrder(100L, orderBook());

        assertThat(buyOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(buyOrder.getCloseReason()).isNull();
    }

    @Test
    void buyIsCappedSoThatTheFeeStillFitsInTheCash() {
        // 1% 수수료. 10주는 1,000 + 10 = 1,010이라 예수금 1,000을 넘는다. 체결대금만으로 캡을 씌우면
        // cash_balance >= 0 제약에 걸려 체결 트랜잭션이 통째로 실패하고 매번 같은 자리에서 실패한다.
        chargeFees("0.01");
        Account buyer = account(1L, "1000.00");
        Order buyOrder = buyOrder(100L, buyer, "100.0000", 20L);
        givenOrder(buyOrder);

        service.matchOrder(100L, orderBook(ask("100.0000", 20L)));

        assertThat(buyOrder.getFilledQuantity()).isEqualTo(9L);
        // 1,000 − 900 − 수수료 9
        assertThat(buyer.getCashBalance()).isEqualByComparingTo("91.00");
    }

    @Test
    void buyThatCoversThePriceButNotTheFeeIsRejected() {
        chargeFees("0.01");
        Account buyer = account(1L, "100.00");
        Order buyOrder = buyOrder(100L, buyer, "100.0000", 1L);
        givenOrder(buyOrder);

        service.matchOrder(100L, orderBook(ask("100.0000", 1L)));

        assertThat(buyOrder.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(buyOrder.getCloseReason()).isEqualTo("주문 가능 금액이 부족합니다.");
        assertThat(buyer.getCashBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void internalBuyerIsCappedByFeesAndTheSellerPaysFeesFromTheProceeds() {
        chargeFees("0.01");
        Account seller = account(1L, "0.00");
        Account buyer = account(2L, "1000.00");
        holding(seller, 20L, "50.0000");
        Order buyOrder = buyOrder(100L, buyer, "100.0000", 20L);
        Order internalSell = sellOrder(200L, seller, "100.0000", 20L);
        givenOrder(buyOrder);
        givenInternalSellOrders(internalSell);

        service.matchOrder(100L, orderBook());

        assertThat(buyOrder.getFilledQuantity()).isEqualTo(9L);
        assertThat(buyer.getCashBalance()).isEqualByComparingTo("91.00");
        // 매도자는 받은 체결대금 900에서 수수료 9를 낸다.
        assertThat(seller.getCashBalance()).isEqualByComparingTo("891.00");
    }

    @Test
    void sameTradeAmountCostsTheSameFeeWhetherFilledAtOnceOrInParts() {
        // 시중 증권사처럼 수수료는 체결금액에만 의존해야 한다. 0.25%면 30달러는 0.075 → 0.08이다.
        // 체결 건마다 반올림하면 10달러씩 세 번은 0.025 → 0.03 × 3 = 0.09가 되어 달라진다.
        chargeFees("0.0025");
        Account atOnce = account(1L, "100.00");
        Order wholeOrder = buyOrder(100L, atOnce, "10.0000", 3L);
        givenOrder(wholeOrder);
        service.matchOrder(100L, orderBook(ask("10.0000", 3L)));

        Account inParts = account(2L, "100.00");
        Order splitOrder = buyOrder(200L, inParts, "10.0000", 3L);
        givenOrder(splitOrder);
        service.matchOrder(200L, orderBook(ask("10.0000", 1L), ask("10.0000", 1L), ask("10.0000", 1L)));

        assertThat(wholeOrder.getChargedCommission()).isEqualByComparingTo("0.08");
        assertThat(splitOrder.getChargedCommission()).isEqualByComparingTo("0.08");
        // 100 − 30 − 0.08
        assertThat(atOnce.getCashBalance()).isEqualByComparingTo("69.92");
        assertThat(inParts.getCashBalance()).isEqualByComparingTo("69.92");
    }

    private MatchingEngineTransactionService newService() {
        return new MatchingEngineTransactionService(
            accountRepository,
            orderRepository,
            executionRepository,
            holdingRepository,
            ledgerPostingService,
            orderBookService,
            new TradingFees(commissionCalculator),
            passThroughTransactionManager()
        );
    }

    /** 운영은 수수료 0이지만 체결 경로는 수수료가 있다고 가정하고 동작해야 한다. */
    private void chargeFees(String commissionRate) {
        commissionCalculator = new RateCommissionCalculator(new BigDecimal(commissionRate), BigDecimal.ZERO);
        service = newService();
    }

    /** save()가 인자를 그대로 돌려주는 repository mock. */
    private static <T> T savingMock(Class<T> type) {
        T repository = mock(type);
        when(((org.springframework.data.repository.CrudRepository<?, ?>) repository).save(any()))
            .thenAnswer(call -> call.getArgument(0));
        return repository;
    }

    /** 체결 단위 트랜잭션을 그대로 실행만 시킨다. 경계 동작이 아니라 체결 로직을 보는 test다. */
    private PlatformTransactionManager passThroughTransactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public org.springframework.transaction.TransactionStatus getTransaction(
                org.springframework.transaction.TransactionDefinition definition) {
                return new org.springframework.transaction.support.SimpleTransactionStatus();
            }

            @Override
            public void commit(org.springframework.transaction.TransactionStatus status) {
            }

            @Override
            public void rollback(org.springframework.transaction.TransactionStatus status) {
            }
        };
    }

    // --- fixtures ---

    private Account account(Long id, String cash) {
        Account account = Account.builder()
            .id(id)
            .accountNumber("ACC-" + id)
            .cashBalance(new BigDecimal(cash))
            .initialBalance(new BigDecimal(cash))
            .totalAssetValue(new BigDecimal(cash))
            .status(AccountStatus.ACTIVE)
            .build();
        accounts.put(id, account);
        return account;
    }

    private void holding(Account account, long quantity, String averagePrice) {
        holdings.put(account.getId(), Holding.builder()
            .account(account)
            .stock(STOCK)
            .quantity(quantity)
            .averagePrice(new BigDecimal(averagePrice))
            .totalPurchaseAmount(new BigDecimal(averagePrice)
                .multiply(BigDecimal.valueOf(quantity))
                .setScale(2, RoundingMode.HALF_UP))
            .build());
    }

    private Order buyOrder(Long id, Account account, String price, long quantity) {
        return order(id, account, OrderSide.BUY, price, quantity);
    }

    private Order sellOrder(Long id, Account account, String price, long quantity) {
        return order(id, account, OrderSide.SELL, price, quantity);
    }

    private Order order(Long id, Account account, OrderSide side, String price, long quantity) {
        return Order.builder()
            .id(id)
            .account(account)
            .stock(STOCK)
            .orderSide(side)
            .orderType(OrderType.LIMIT)
            .orderPrice(new BigDecimal(price))
            .orderQuantity(quantity)
            .filledQuantity(0L)
            .remainingQuantity(quantity)
            .status(OrderStatus.PENDING)
            .build();
    }

    private void givenOrder(Order order) {
        when(orderRepository.findByIdForUpdate(eq(order.getId()))).thenReturn(Optional.of(order));
    }

    private void givenInternalSellOrders(Order... orders) {
        when(orderRepository.findMatchableSellOrders(anyCollection(), anyLong(), anyLong(), any(), anyList(), any()))
            .thenAnswer(call -> matchable(call.getArgument(0), orders));
    }

    private void givenInternalBuyOrders(Order... orders) {
        when(orderRepository.findMatchableBuyOrders(anyCollection(), anyLong(), anyLong(), any(), anyList(), any()))
            .thenAnswer(call -> matchable(call.getArgument(0), orders));
    }

    /** 제외 목록과 잔여 수량을 실제 쿼리처럼 반영해야 무한 루프 회귀를 잡을 수 있다. */
    private List<Order> matchable(Collection<Long> excludedOrderIds, Order... orders) {
        return List.of(orders).stream()
            .filter(order -> !excludedOrderIds.contains(order.getId()))
            .filter(order -> order.getRemainingQuantity() > 0)
            .limit(1)
            .toList();
    }

    private OrderBookLevel ask(String price, long volume) {
        return new OrderBookLevel(new BigDecimal(price), volume);
    }

    private OrderBookResponse orderBook(OrderBookLevel... asks) {
        return new OrderBookResponse(new OrderBookResult(null, "USD", List.of(asks), List.of()), null);
    }

}
