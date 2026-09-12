package com.papertrade.paper_trading.Ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.papertrade.paper_trading.Dto.DailyPriceRangeResponse;
import com.papertrade.paper_trading.Dto.OrderBookResponse;
import com.papertrade.paper_trading.Dto.OrderBookResult;
import com.papertrade.paper_trading.Entity.Account;
import com.papertrade.paper_trading.Entity.Holding;
import com.papertrade.paper_trading.Entity.Order;
import com.papertrade.paper_trading.Entity.Stock;
import com.papertrade.paper_trading.Entity.User;
import com.papertrade.paper_trading.Enum.Market;
import com.papertrade.paper_trading.Enum.OrderSide;
import com.papertrade.paper_trading.Enum.OrderStatus;
import com.papertrade.paper_trading.Enum.OrderType;
import com.papertrade.paper_trading.Enum.Role;
import com.papertrade.paper_trading.Enum.SecurityType;
import com.papertrade.paper_trading.Enum.Status;
import com.papertrade.paper_trading.Enum.StockStatus;
import com.papertrade.paper_trading.Repository.AccountRepository;
import com.papertrade.paper_trading.Repository.HoldingRepository;
import com.papertrade.paper_trading.Repository.OrderRepository;
import com.papertrade.paper_trading.Repository.StockRepository;
import com.papertrade.paper_trading.Repository.UserRepository;
import com.papertrade.paper_trading.Service.AccountOpeningService;
import com.papertrade.paper_trading.Service.MatchingEngineTransactionService;
import com.papertrade.paper_trading.Service.OrderBookService;
import com.papertrade.paper_trading.support.ApplicationIntegrationTest;
import com.papertrade.paper_trading.support.IntegrationTestContainers;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자전거래 차단과 DB 방어선.
 *
 * <p>자기 자신과 체결하면 현금은 나갔다 들어와 순변동이 0인데 실현손익은 그대로 적립된다.
 * 가격을 스스로 정할 수 있으므로 원하는 만큼 손익을 만들어낼 수 있는 경로였다.
 */
@ApplicationIntegrationTest
class SelfTradeAndConstraintIntegrationTest extends IntegrationTestContainers {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    @Autowired private AccountOpeningService accountOpeningService;
    @Autowired private MatchingEngineTransactionService matchingEngine;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StockRepository stockRepository;
    @Autowired private HoldingRepository holdingRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private EntityManager entityManager;

    /** 이 test는 내부 주문끼리의 체결만 본다. 외부 호가는 없다. */
    @MockitoBean private OrderBookService orderBookService;

    @Test
    void anAccountCannotTradeWithItself() {
        Account account = openAccount("10000000.00");
        Stock stock = persistStock();
        holdingRepository.save(holdingOf(account, stock, 10L, "1000.0000"));

        // 같은 계좌가 양쪽에 선다. 매도가를 취득원가의 100배로 잡아 손익을 만들어내려는 시도다.
        persistOrder(account, stock, OrderSide.SELL, "100000.0000", 10L);
        Order buyOrder = persistOrder(account, stock, OrderSide.BUY, "100000.0000", 10L);

        matchingEngine.matchOrder(buyOrder.getId(), emptyOrderBook(), dailyPriceRange());

        Order reloaded = orderRepository.findById(buyOrder.getId()).orElseThrow();
        assertThat(reloaded.getFilledQuantity()).isZero();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PENDING);

        Account afterMatching = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(afterMatching.getRealizedProfit()).isEqualByComparingTo("0.00");
    }

    @Test
    void differentAccountsStillMatchNormally() {
        // 자전거래 차단이 정상 내부 체결까지 막으면 안 된다.
        Account seller = openAccount("1000000.00");
        Account buyer = openAccount("1000000.00");
        Stock stock = persistStock();
        holdingRepository.save(holdingOf(seller, stock, 5L, "1000.0000"));

        persistOrder(seller, stock, OrderSide.SELL, "1000.0000", 5L);
        Order buyOrder = persistOrder(buyer, stock, OrderSide.BUY, "1000.0000", 5L);

        matchingEngine.matchOrder(buyOrder.getId(), emptyOrderBook(), dailyPriceRange());

        assertThat(orderRepository.findById(buyOrder.getId()).orElseThrow().getFilledQuantity())
            .isEqualTo(5L);
    }

    @Test
    void crossSymbolMatchingOnTheSameTwoAccountsDoesNotDeadlock() throws Exception {
        // 데드락이 성립하던 모양을 그대로 만든다.
        // 종목 A: 계좌1이 매수(계좌2가 매도) / 종목 B: 계좌2가 매수(계좌1이 매도)
        // 예전에는 매칭이 "자기 계좌 먼저, 상대 계좌 나중"으로 잡아 두 스레드가 서로를 기다렸다.
        Account account1 = openAccount("10000000.00");
        Account account2 = openAccount("10000000.00");
        Stock stockA = persistStock();
        Stock stockB = persistStock();
        holdingRepository.save(holdingOf(account2, stockA, 100L, "1000.0000"));
        holdingRepository.save(holdingOf(account1, stockB, 100L, "1000.0000"));

        persistOrder(account2, stockA, OrderSide.SELL, "1000.0000", 100L);
        Order buyOnA = persistOrder(account1, stockA, OrderSide.BUY, "1000.0000", 100L);
        persistOrder(account1, stockB, OrderSide.SELL, "1000.0000", 100L);
        Order buyOnB = persistOrder(account2, stockB, OrderSide.BUY, "1000.0000", 100L);

        int rounds = 8;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < rounds; round++) {
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<?> first = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    matchingEngine.matchOrder(buyOnA.getId(), emptyOrderBook(), dailyPriceRange());
                    return null;
                });
                Future<?> second = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    matchingEngine.matchOrder(buyOnB.getId(), emptyOrderBook(), dailyPriceRange());
                    return null;
                });
                // 데드락이면 PostgreSQL이 한쪽을 abort시켜 예외가 올라온다.
                first.get(30, TimeUnit.SECONDS);
                second.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(orderRepository.findById(buyOnA.getId()).orElseThrow().getFilledQuantity()).isEqualTo(100L);
        assertThat(orderRepository.findById(buyOnB.getId()).orElseThrow().getFilledQuantity()).isEqualTo(100L);
    }

    @Test
    void bestPricedCounterpartyWinsEvenWhenItsAccountIdIsHighest() {
        // 매칭은 시작 시점에 상대 계좌를 상한(10)까지만 미리 잠근다. 그 상한에 걸려 잘려나가는 것은
        // 반드시 "가격이 나쁜 쪽"이어야 한다. 계좌 id 순으로 고르면 가장 싼 매도자가 id가 높다는
        // 이유로 제외되어 체결 우선순위가 계좌 id에 좌우된다.
        Account buyer = openAccount("100000000.00");
        Stock stock = persistStock();

        // 비싼 매도자를 먼저 만들어 낮은 id를 갖게 한다. 가장 싼 매도자가 마지막 = 가장 높은 id다.
        int expensiveSellers = 12;
        for (int i = 0; i < expensiveSellers; i++) {
            Account seller = openAccount("1000.00");
            holdingRepository.save(holdingOf(seller, stock, 1L, "1000.0000"));
            persistOrder(seller, stock, OrderSide.SELL, "9000.0000", 1L);
        }
        Account cheapestSeller = openAccount("1000.00");
        holdingRepository.save(holdingOf(cheapestSeller, stock, 1L, "1000.0000"));
        persistOrder(cheapestSeller, stock, OrderSide.SELL, "1000.0000", 1L);

        Order buyOrder = persistOrder(buyer, stock, OrderSide.BUY, "9000.0000", 1L);
        matchingEngine.matchOrder(buyOrder.getId(), emptyOrderBook(), dailyPriceRange());

        // 가장 싼 1,000에 체결되어야 한다. 9,000에 체결됐다면 상한이 가격 우선순위를 뒤집은 것이다.
        Account settled = accountRepository.findById(buyer.getId()).orElseThrow();
        assertThat(settled.getCashBalance()).isEqualByComparingTo("99999000.00");
    }

    @Test
    void anOrderNeedingMoreCounterpartiesThanTheLockLimitStillFillsInOneSweep() {
        // 한 트랜잭션이 잠그는 상대 계좌는 10개까지인데 25개가 필요한 주문이다.
        // 그래도 한 스윕에서 다 채워져야 한다 — matchSymbol()이 그 종목의 미체결 주문을 전부
        // 순회하므로, 상한에 밀린 매도자들이 각자 자기 차례에 이 매수 주문을 상대로 체결한다.
        // 계좌 상한은 "한 트랜잭션이 잠그는 범위"를 정할 뿐 체결 범위를 정하지 않는다.
        Account buyer = openAccount("100000000.00");
        Stock stock = persistStock();

        int sellers = 25;
        for (int i = 0; i < sellers; i++) {
            Account seller = openAccount("1000.00");
            holdingRepository.save(holdingOf(seller, stock, 4L, "1000.0000"));
            persistOrder(seller, stock, OrderSide.SELL, "1000.0000", 4L);
        }

        Order buyOrder = persistOrder(buyer, stock, OrderSide.BUY, "1000.0000", 100L);
        matchSymbolFor(stock);

        // 25계좌 × 4주 = 100주. 한 번의 스윕에서 전부 체결되어야 한다.
        assertThat(orderRepository.findById(buyOrder.getId()).orElseThrow().getFilledQuantity())
            .isEqualTo(100L);
    }

    @Test
    @Transactional
    void databaseRejectsANegativeCashBalance() {
        // 체결 시점 캡이 유일한 방어선이면, 캡 계산이 깨지는 순간 음수 잔고가 조용히 저장된다.
        Account account = openAccount("1000.00");

        assertThatThrownBy(() -> {
            entityManager.createQuery("update Account a set a.cashBalance = :balance where a.id = :id")
                .setParameter("balance", new BigDecimal("-1.00"))
                .setParameter("id", account.getId())
                .executeUpdate();
            entityManager.flush();
        }).isInstanceOf(Exception.class);
    }

    @Test
    @Transactional
    void databaseRejectsANegativeHoldingQuantity() {
        Account account = openAccount("1000000.00");
        Stock stock = persistStock();
        Holding holding = holdingRepository.save(holdingOf(account, stock, 1L, "1000.0000"));
        entityManager.flush();

        assertThatThrownBy(() -> {
            entityManager.createQuery("update Holding h set h.quantity = -1 where h.id = :id")
                .setParameter("id", holding.getId())
                .executeUpdate();
            entityManager.flush();
        }).isInstanceOf(Exception.class);
    }

    /** 종목 전체 스윕. matchOrder를 직접 부르면 계좌 상한까지만 체결된다. */
    private void matchSymbolFor(Stock stock) {
        when(orderBookService.getOrderBookForMatching(anyString(), any())).thenReturn(emptyOrderBook());
        matchingEngine.matchSymbol(stock.getSymbol(), dailyPriceRange());
    }

    // --- fixtures ---

    private Account openAccount(String initialAmount) {
        long suffix = SEQUENCE.incrementAndGet();
        User user = userRepository.save(User.builder()
            .email("selftrade-" + suffix + "@example.com")
            .passwordHash("test")
            .nickname("selftrade-" + suffix)
            .status(Status.ACTIVE)
            .role(Role.USER)
            .build());
        return accountOpeningService.open(user, new BigDecimal(initialAmount));
    }

    private Stock persistStock() {
        long suffix = SEQUENCE.incrementAndGet();
        return stockRepository.save(Stock.builder()
            .symbol("ST" + suffix)
            .name("selftrade " + suffix)
            .market(Market.NASDAQ)
            .securityType(SecurityType.STOCK)
            .isCommonShare(true)
            .status(StockStatus.ACTIVE)
            .currency("USD")
            .build());
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
        return new OrderBookResponse(new OrderBookResult(null, "USD", List.of(), List.of()), LocalDateTime.now());
    }

    private DailyPriceRangeResponse dailyPriceRange() {
        return new DailyPriceRangeResponse("ST", null, new BigDecimal("200000.0000"),
            new BigDecimal("100.0000"), "USD");
    }
}
