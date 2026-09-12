package com.papertrade.paper_trading.Ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
