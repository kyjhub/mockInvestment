package com.papertrade.paper_trading.Expiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.papertrade.paper_trading.Dto.MarketBusinessDay;
import com.papertrade.paper_trading.Dto.MarketCalendarResponse;
import com.papertrade.paper_trading.Dto.MarketCalendarResult;
import com.papertrade.paper_trading.Dto.MarketSession;
import com.papertrade.paper_trading.Entity.Account;
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
import com.papertrade.paper_trading.Repository.OrderRepository;
import com.papertrade.paper_trading.Repository.StockRepository;
import com.papertrade.paper_trading.Repository.UserRepository;
import com.papertrade.paper_trading.Service.AccountOpeningService;
import com.papertrade.paper_trading.Service.AccountQueryService;
import com.papertrade.paper_trading.Service.DayOrderExpiryScheduler;
import com.papertrade.paper_trading.Service.MarketCalendarService;
import com.papertrade.paper_trading.support.ApplicationIntegrationTest;
import com.papertrade.paper_trading.support.IntegrationTestContainers;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 당일 유효 주문의 실효.
 *
 * <p>기준은 정규장 마감이 아니라 애프터마켓 종료다. 토스는 한 거래일을 주간거래 → 프리마켓 →
 * 정규장 → 애프터마켓으로 운영하므로 정규장이 끝나도 두 시간 더 거래가 가능하다.
 *
 * <p>그리고 <b>종료 이후 접수된 주문은 살아남아야 한다</b> — 다음 거래일 주문(예약주문)이다.
 */
@ApplicationIntegrationTest
class DayOrderExpiryIntegrationTest extends IntegrationTestContainers {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired private AccountOpeningService accountOpeningService;
    @Autowired private AccountQueryService accountQueryService;
    @Autowired private DayOrderExpiryScheduler expiryScheduler;
    @Autowired private UserRepository userRepository;
    @Autowired private StockRepository stockRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private MarketCalendarService marketCalendarService;

    @Test
    void anUnfilledOrderSubmittedBeforeTheCloseIsExpired() {
        OffsetDateTime closedAt = OffsetDateTime.now().minusHours(1);
        givenTradingDayEndingAt(closedAt);
        Order order = persistOrderSubmittedAt(closedAt.minusHours(3));

        expiryScheduler.expireDayOrders();

        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(reloaded.getCloseReason()).isEqualTo("거래일 종료로 실효되었습니다.");
        assertThat(reloaded.getCanceledAt()).isNotNull();
    }

    @Test
    void anOrderSubmittedAfterTheCloseSurvives() {
        // 예약주문이다. 여기가 깨지면 장 마감 후 주문을 아예 받을 수 없게 된다.
        OffsetDateTime closedAt = OffsetDateTime.now().minusHours(1);
        givenTradingDayEndingAt(closedAt);
        Order order = persistOrderSubmittedAt(closedAt.plusMinutes(10));

        expiryScheduler.expireDayOrders();

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void anOrderSurvivesTheRegularCloseWhileTheAfterMarketIsStillRunning() {
        // 거래일의 끝은 정규장 마감이 아니라 애프터마켓 종료다. 토스는 정규장이 끝난 뒤에도
        // 두 시간을 더 거래시키므로, 정규장 마감을 기준으로 실효시키면 아직 체결될 수 있는
        // 주문을 죽이게 된다. 기준을 regularMarket.endTime으로 되돌리면 이 테스트가 깨진다.
        OffsetDateTime afterMarketEnd = OffsetDateTime.now().plusHours(1);  // 정규장은 1시간 전에 끝났다
        givenTradingDayEndingAt(afterMarketEnd);
        Order order = persistOrderSubmittedAt(afterMarketEnd.minusHours(5));

        expiryScheduler.expireDayOrders();

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void anOrderFromAnEarlierTradingDayExpiresEvenWhileTodayIsOpen() {
        // 오늘 장이 열려 있어도 어제 주문은 이미 실효 대상이다.
        OffsetDateTime afterMarketEnd = OffsetDateTime.now().plusHours(1);
        givenTradingDayEndingAt(afterMarketEnd);
        Order order = persistOrderSubmittedAt(afterMarketEnd.minusHours(30));

        expiryScheduler.expireDayOrders();

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.EXPIRED);
    }

    @Test
    void repeatedRunsDoNotChangeTheResult() {
        // 분 단위로 도는 batch라 멱등해야 한다. 그래야 "이미 처리함" 상태를 들고 다니지 않아도 된다.
        OffsetDateTime closedAt = OffsetDateTime.now().minusHours(1);
        givenTradingDayEndingAt(closedAt);
        Order expiring = persistOrderSubmittedAt(closedAt.minusHours(3));
        Order surviving = persistOrderSubmittedAt(closedAt.plusMinutes(10));

        for (int i = 0; i < 5; i++) {
            expiryScheduler.expireDayOrders();
        }

        assertThat(orderRepository.findById(expiring.getId()).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.EXPIRED);
        assertThat(orderRepository.findById(surviving.getId()).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void expiryReleasesTheReservedCashWithoutAnyReleaseCode() {
        OffsetDateTime closedAt = OffsetDateTime.now().minusHours(1);
        givenTradingDayEndingAt(closedAt);
        User user = persistUser();
        Account account = accountOpeningService.open(user, new BigDecimal("1000000.00"));
        persistOrderSubmittedAt(account, closedAt.minusHours(3), 100L);

        assertThat(accountQueryService.getBalance(user).orderableAmount()).isEqualByComparingTo("900000.00");

        expiryScheduler.expireDayOrders();

        // 구속액을 주문에서 파생하므로 status가 바뀌는 것만으로 합계에서 빠진다.
        assertThat(accountQueryService.getBalance(user).orderableAmount()).isEqualByComparingTo("1000000.00");
    }

    @Test
    void aPartiallyFilledOrderKeepsItsExecutionHistory() {
        OffsetDateTime closedAt = OffsetDateTime.now().minusHours(1);
        givenTradingDayEndingAt(closedAt);
        Account account = accountOpeningService.open(persistUser(), new BigDecimal("1000000.00"));
        Order order = persistOrderSubmittedAt(account, closedAt.minusHours(3), 10L);
        inTransaction(() -> orderRepository.findById(order.getId()).orElseThrow().fill(3L));

        expiryScheduler.expireDayOrders();

        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(reloaded.getFilledQuantity()).isEqualTo(3L);
        assertThat(reloaded.getRemainingQuantity()).isEqualTo(7L);
    }

    @Test
    void onAHolidayTheCutoffFallsBackToThePreviousBusinessDay() {
        // 휴장일 응답은 today가 null이 아니라, today는 있고 네 세션이 전부 null이다.
        // 직전 영업일의 애프터마켓 종료(2026-07-03T07:00+09:00)가 기준이 되어야 한다.
        givenHolidayCalendar();
        Order duringPreviousSession = persistOrderSubmittedAt(offset("2026-07-02T23:00:00+09:00"));
        Order afterPreviousSession = persistOrderSubmittedAt(offset("2026-07-03T08:00:00+09:00"));

        expiryScheduler.expireDayOrders();

        assertThat(orderRepository.findById(duringPreviousSession.getId()).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.EXPIRED);
        // 휴장일에 접수된 주문은 다음 영업일 주문이다. 장이 안 열렸다고 죽이면 안 된다.
        assertThat(orderRepository.findById(afterPreviousSession.getId()).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void nothingExpiresWhenNoTradingDayHasEndedYet() {
        // 방어적 경로. 연휴가 길어 직전 영업일조차 응답에 없으면 기준 시각이 없다.
        when(marketCalendarService.getUsMarketCalendar(any())).thenReturn(
            new MarketCalendarResponse(new MarketCalendarResult(null, null, null)));
        Order order = persistOrderSubmittedAt(OffsetDateTime.now().minusDays(3));

        expiryScheduler.expireDayOrders();

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void nothingExpiresWhenTheCalendarCallFails() {
        // 마감 시각을 모르는 채로 실효시키면 되돌릴 방법이 없다. 다음 주기로 미룬다.
        when(marketCalendarService.getUsMarketCalendar(any()))
            .thenThrow(new IllegalStateException("calendar unavailable"));
        Order order = persistOrderSubmittedAt(OffsetDateTime.now().minusDays(3));

        expiryScheduler.expireDayOrders();

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.PENDING);
    }

    // --- fixtures ---

    /**
     * 오늘 거래일의 애프터마켓이 {@code todayAfterMarketEnd}에 끝나는 달력을 만든다.
     *
     * <p>세션 간격은 토스 응답과 같은 모양으로 둔다: 주간거래 8시간 → 프리마켓 6시간 →
     * 정규장 6시간 → 애프터마켓 2시간. 정규장 종료와 애프터마켓 종료 사이가 2시간 벌어지는 것이
     * 이 테스트들이 구분하려는 지점이다.
     */
    private void givenTradingDayEndingAt(OffsetDateTime todayAfterMarketEnd) {
        when(marketCalendarService.getUsMarketCalendar(any())).thenReturn(
            new MarketCalendarResponse(new MarketCalendarResult(
                businessDay(todayAfterMarketEnd),
                businessDay(todayAfterMarketEnd.minusDays(1)),
                null)));
    }

    private MarketBusinessDay businessDay(OffsetDateTime afterMarketEnd) {
        return new MarketBusinessDay(
            afterMarketEnd.toLocalDate(),
            new MarketSession(afterMarketEnd.minusHours(22), afterMarketEnd.minusHours(14)),
            new MarketSession(afterMarketEnd.minusHours(14), afterMarketEnd.minusHours(8)),
            new MarketSession(afterMarketEnd.minusHours(8), afterMarketEnd.minusHours(2)),
            new MarketSession(afterMarketEnd.minusHours(2), afterMarketEnd));
    }

    /** 실제 휴장일 응답(2026-07-03, 독립기념일 연휴)의 모양 그대로. today는 있고 세션만 비어 있다. */
    private void givenHolidayCalendar() {
        MarketBusinessDay holiday = new MarketBusinessDay(
            LocalDate.parse("2026-07-03"), null, null, null, null);
        MarketBusinessDay previous = new MarketBusinessDay(
            LocalDate.parse("2026-07-02"),
            new MarketSession(offset("2026-07-02T09:00:00+09:00"), offset("2026-07-02T16:50:00+09:00")),
            new MarketSession(offset("2026-07-02T17:00:00+09:00"), offset("2026-07-02T22:30:00+09:00")),
            new MarketSession(offset("2026-07-02T22:30:00+09:00"), offset("2026-07-03T05:00:00+09:00")),
            new MarketSession(offset("2026-07-03T05:00:00+09:00"), offset("2026-07-03T07:00:00+09:00")));
        MarketBusinessDay next = new MarketBusinessDay(
            LocalDate.parse("2026-07-06"),
            new MarketSession(offset("2026-07-06T09:00:00+09:00"), offset("2026-07-06T16:50:00+09:00")),
            new MarketSession(offset("2026-07-06T17:00:00+09:00"), offset("2026-07-06T22:30:00+09:00")),
            new MarketSession(offset("2026-07-06T22:30:00+09:00"), offset("2026-07-07T05:00:00+09:00")),
            new MarketSession(offset("2026-07-07T05:00:00+09:00"), offset("2026-07-07T07:00:00+09:00")));
        when(marketCalendarService.getUsMarketCalendar(any())).thenReturn(
            new MarketCalendarResponse(new MarketCalendarResult(holiday, previous, next)));
    }

    private OffsetDateTime offset(String text) {
        return OffsetDateTime.parse(text);
    }

    private Order persistOrderSubmittedAt(OffsetDateTime submittedAt) {
        return persistOrderSubmittedAt(
            accountOpeningService.open(persistUser(), new BigDecimal("1000000.00")), submittedAt, 1L);
    }

    private Order persistOrderSubmittedAt(Account account, OffsetDateTime submittedAt, long quantity) {
        Stock stock = persistStock();
        BigDecimal price = new BigDecimal("1000.0000");
        Order order = orderRepository.saveAndFlush(Order.create(
            account, stock, null, OrderSide.BUY, OrderType.LIMIT, price, quantity, price));
        // submitted_at은 @CreationTimestamp라 저장 후 bulk update로만 과거로 되돌릴 수 있다.
        LocalDateTime submittedAtLocal =
            submittedAt.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        inTransaction(() -> entityManager
            .createQuery("update Order o set o.submittedAt = :at where o.id = :id")
            .setParameter("at", submittedAtLocal)
            .setParameter("id", order.getId())
            .executeUpdate());
        return orderRepository.findById(order.getId()).orElseThrow();
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> work.run());
    }

    private User persistUser() {
        long suffix = SEQUENCE.incrementAndGet();
        return userRepository.save(User.builder()
            .email("expiry-" + suffix + "@example.com").passwordHash("test")
            .nickname("expiry-" + suffix).status(Status.ACTIVE).role(Role.USER).build());
    }

    private Stock persistStock() {
        long suffix = SEQUENCE.incrementAndGet();
        return stockRepository.save(Stock.builder()
            .symbol("EXP" + suffix).name("expiry " + suffix).market(Market.NASDAQ)
            .securityType(SecurityType.STOCK).isCommonShare(true)
            .status(StockStatus.ACTIVE).currency("USD").build());
    }
}
