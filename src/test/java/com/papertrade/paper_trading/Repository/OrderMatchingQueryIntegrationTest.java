package com.papertrade.paper_trading.Repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.papertrade.paper_trading.Entity.Account;
import com.papertrade.paper_trading.Entity.Order;
import com.papertrade.paper_trading.Entity.Stock;
import com.papertrade.paper_trading.Entity.User;
import com.papertrade.paper_trading.Enum.AccountStatus;
import com.papertrade.paper_trading.Enum.Market;
import com.papertrade.paper_trading.Enum.OrderSide;
import com.papertrade.paper_trading.Enum.OrderStatus;
import com.papertrade.paper_trading.Enum.OrderType;
import com.papertrade.paper_trading.Enum.Role;
import com.papertrade.paper_trading.Enum.SecurityType;
import com.papertrade.paper_trading.Enum.Status;
import com.papertrade.paper_trading.Enum.StockStatus;
import com.papertrade.paper_trading.Repository.OrderRepository.MatchableOrder;
import com.papertrade.paper_trading.support.IntegrationTestContainers;
import com.papertrade.paper_trading.support.JpaIntegrationTest;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * 매칭 후보 조회는 JPQL과 DB 제약에 기대고 있어 mock으로는 아무것도 증명하지 못한다.
 *
 * <p>특히 {@code excludedOrderIds}는 08번에서 {@code o.id <> :incomingOrderId}를 대체한 것인데,
 * {@code not in}에 collection을 넘기는 형태라 실제 SQL로 돌려보지 않으면 동작을 확신할 수 없다.
 */
@JpaIntegrationTest
class OrderMatchingQueryIntegrationTest extends IntegrationTestContainers {

    private static final Pageable FIRST = PageRequest.of(0, 1);
    private static final List<OrderStatus> MATCHABLE =
        List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);
    private static final AtomicLong SEQUENCE = new AtomicLong();
    /** 자전거래 차단 때문에 후보 조회는 "다른 계좌" 기준이어야 한다. */
    private static final Long OTHER_ACCOUNT_ID = -999L;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private EntityManager entityManager;

    private Account account;
    private Stock stock;

    @BeforeEach
    void setUp() {
        account = persistAccount();
        stock = persistStock();
    }

    @Test
    void excludedOrderIdsActuallyFiltersCandidates() {
        Order cheapest = persistOrder(OrderSide.SELL, "100.0000", 10L);
        Order nextCheapest = persistOrder(OrderSide.SELL, "110.0000", 10L);

        List<Order> withoutExclusion = orderRepository.findMatchableSellOrders(
            Set.of(-1L), lockedAccountIds(), OTHER_ACCOUNT_ID, stock.getId(), new BigDecimal("200.0000"), MATCHABLE, FIRST);
        assertThat(withoutExclusion).extracting(Order::getId).containsExactly(cheapest.getId());

        // 체결 불가로 판명된 상대를 제외하면 다음 후보가 나와야 한다. 무한 loop를 막는 장치다.
        List<Order> withExclusion = orderRepository.findMatchableSellOrders(
            Set.of(cheapest.getId()), lockedAccountIds(), OTHER_ACCOUNT_ID, stock.getId(), new BigDecimal("200.0000"), MATCHABLE, FIRST);
        assertThat(withExclusion).extracting(Order::getId).containsExactly(nextCheapest.getId());
    }

    @Test
    void sellCandidatesComeBackCheapestFirstAndRespectTheLimitPrice() {
        persistOrder(OrderSide.SELL, "130.0000", 10L);
        Order cheapest = persistOrder(OrderSide.SELL, "100.0000", 10L);

        // 지정가 120이면 130짜리는 후보가 아니다.
        List<Order> candidates = orderRepository.findMatchableSellOrders(
            Set.of(-1L), lockedAccountIds(), OTHER_ACCOUNT_ID, stock.getId(), new BigDecimal("120.0000"), MATCHABLE, PageRequest.of(0, 10));

        assertThat(candidates).extracting(Order::getId).containsExactly(cheapest.getId());
    }

    @Test
    void buyCandidatesComeBackHighestFirst() {
        Order highest = persistOrder(OrderSide.BUY, "130.0000", 10L);
        persistOrder(OrderSide.BUY, "100.0000", 10L);

        List<Order> candidates = orderRepository.findMatchableBuyOrders(
            Set.of(-1L), lockedAccountIds(), OTHER_ACCOUNT_ID, stock.getId(), new BigDecimal("90.0000"), MATCHABLE, FIRST);

        assertThat(candidates).extracting(Order::getId).containsExactly(highest.getId());
    }

    @Test
    void symbolSweepPutsBuyOrdersBeforeSellOrders() {
        persistOrder(OrderSide.SELL, "100.0000", 10L);
        Order buy = persistOrder(OrderSide.BUY, "100.0000", 10L);

        List<MatchableOrder> sweep = orderRepository.findMatchableOrdersBySymbol(stock.getSymbol(), MATCHABLE);

        assertThat(sweep).hasSize(2);
        assertThat(sweep.get(0).id()).isEqualTo(buy.getId());
    }

    @Test
    void databaseRejectsAnOrderWhoseQuantitiesDoNotAddUp() {
        // 애플리케이션 버그로 체결 수량이 어긋나면 DB가 마지막 방어선이 되어야 한다.
        Order broken = Order.builder()
            .account(account)
            .stock(stock)
            .orderSide(OrderSide.BUY)
            .orderType(OrderType.LIMIT)
            .orderPrice(new BigDecimal("100.0000"))
            .orderQuantity(10L)
            .filledQuantity(3L)
            .remainingQuantity(9L)          // 3 + 9 != 10
            .status(OrderStatus.PENDING)
            .build();

        assertThatThrownBy(() -> {
            orderRepository.save(broken);
            entityManager.flush();
        }).isInstanceOf(Exception.class);
    }

    /** 매칭은 미리 잠근 계좌로만 후보를 좁힌다. 이 test는 자기 계좌 하나뿐이다. */
    private java.util.Collection<Long> lockedAccountIds() {
        return Set.of(account.getId());
    }

    private Order persistOrder(OrderSide side, String price, long quantity) {
        BigDecimal unitPrice = new BigDecimal(price);
        Order order = orderRepository.save(Order.create(
            account, stock, null, side, OrderType.LIMIT, unitPrice, quantity,
            side == OrderSide.BUY ? unitPrice : null));
        entityManager.flush();
        return order;
    }

    private Account persistAccount() {
        long suffix = SEQUENCE.incrementAndGet();
        User user = entityManager.merge(User.builder()
            .email("ledger-" + suffix + "@example.com")
            .passwordHash("test")
            .nickname("ledger-" + suffix)
            .status(Status.ACTIVE)
            .role(Role.USER)
            .build());
        return entityManager.merge(Account.builder()
            .user(user)
            .accountNumber("ACC-" + suffix)
            .cashBalance(new BigDecimal("1000000.00"))
            .initialBalance(new BigDecimal("1000000.00"))
            .totalAssetValue(new BigDecimal("1000000.00"))
            .status(AccountStatus.ACTIVE)
            .build());
    }

    private Stock persistStock() {
        long suffix = SEQUENCE.incrementAndGet();
        return entityManager.merge(Stock.builder()
            .symbol("TST" + suffix)
            .name("test " + suffix)
            .market(Market.NASDAQ)
            .securityType(SecurityType.STOCK)
            .isCommonShare(true)
            .status(StockStatus.ACTIVE)
            .currency("USD")
            .build());
    }
}
