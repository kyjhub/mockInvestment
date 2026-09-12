package com.papertrade.paper_trading.Ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.papertrade.paper_trading.Dto.DailyPriceRangeResponse;
import com.papertrade.paper_trading.Dto.OrderBookLevel;
import com.papertrade.paper_trading.Dto.OrderBookResponse;
import com.papertrade.paper_trading.Dto.OrderBookResult;
import com.papertrade.paper_trading.Entity.Account;
import com.papertrade.paper_trading.Entity.Execution;
import com.papertrade.paper_trading.Entity.Holding;
import com.papertrade.paper_trading.Entity.LedgerEntry;
import com.papertrade.paper_trading.Entity.Order;
import com.papertrade.paper_trading.Entity.Stock;
import com.papertrade.paper_trading.Entity.User;
import com.papertrade.paper_trading.Enum.LedgerAccount;
import com.papertrade.paper_trading.Enum.LedgerTransactionType;
import com.papertrade.paper_trading.Enum.Market;
import com.papertrade.paper_trading.Enum.OrderSide;
import com.papertrade.paper_trading.Enum.OrderType;
import com.papertrade.paper_trading.Enum.Role;
import com.papertrade.paper_trading.Enum.SecurityType;
import com.papertrade.paper_trading.Enum.Status;
import com.papertrade.paper_trading.Enum.StockStatus;
import com.papertrade.paper_trading.Repository.AccountRepository;
import com.papertrade.paper_trading.Repository.ExecutionRepository;
import com.papertrade.paper_trading.Repository.HoldingRepository;
import com.papertrade.paper_trading.Repository.LedgerEntryRepository;
import com.papertrade.paper_trading.Repository.LedgerTransactionRepository;
import com.papertrade.paper_trading.Repository.OrderRepository;
import com.papertrade.paper_trading.Repository.StockRepository;
import com.papertrade.paper_trading.Repository.UserRepository;
import com.papertrade.paper_trading.Service.AccountOpeningService;
import com.papertrade.paper_trading.Service.LedgerPostingService;
import com.papertrade.paper_trading.Service.LedgerPostingService.Posting;
import com.papertrade.paper_trading.Service.MatchingEngineTransactionService;
import com.papertrade.paper_trading.support.ApplicationIntegrationTest;
import com.papertrade.paper_trading.support.IntegrationTestContainers;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 원장 시스템인지 판정하는 시험.
 *
 * <p>기준은 하나다 — <b>잔고를 원장에서 재구성할 수 있는가.</b> 재구성이 안 되면 그건 원장이 아니라
 * 그냥 로그다. 그리고 복식부기의 값어치는 계정이 여럿이라는 데 있지 않고 전역 합계가 0이라는
 * 불변식에 있다. 이 불변식이 "버그로 돈이 생기거나 사라졌다"를 탐지한다.
 */
@ApplicationIntegrationTest
class LedgerIntegrityIntegrationTest extends IntegrationTestContainers {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    @Autowired private AccountOpeningService accountOpeningService;
    @Autowired private MatchingEngineTransactionService matchingEngine;
    @Autowired private LedgerPostingService ledgerPostingService;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private LedgerTransactionRepository ledgerTransactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StockRepository stockRepository;
    @Autowired private HoldingRepository holdingRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ExecutionRepository executionRepository;

    @Test
    void openingAnAccountRecordsTheOpeningEntries() {
        Account account = openAccount("1000000.00");

        List<LedgerEntry> entries = entriesOf(account);
        assertThat(entries).hasSize(2);
        assertThat(sumOf(entries)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(account, LedgerAccount.CASH)).isEqualByComparingTo("1000000.00");
        assertThat(balanceOf(account, LedgerAccount.EQUITY_FUNDING)).isEqualByComparingTo("-1000000.00");
    }

    @Test
    void cashBalanceCanBeRebuiltFromTheLedgerAfterAnExternalBuy() {
        // 이게 "원장 시스템인가"의 최종 판정이다. 잔고 컬럼을 지우고 원장으로 다시 만들 수 있어야 한다.
        Account account = openAccount("1000000.00");
        Stock stock = persistStock();
        Order buyOrder = persistOrder(account, stock, OrderSide.BUY, "100000.0000", 3L);

        matchingEngine.matchOrder(buyOrder.getId(), orderBookWithAsk("100000.0000", 3L), dailyPriceRange());

        Account reloaded = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(reloaded.getCashBalance()).isEqualByComparingTo("700000.00");
        assertThat(balanceOf(account, LedgerAccount.CASH)).isEqualByComparingTo(reloaded.getCashBalance());

        Holding holding = holdingRepository.findAll().stream()
            .filter(h -> h.getAccount().getId().equals(account.getId()))
            .findFirst()
            .orElseThrow();
        assertThat(holding.getTotalPurchaseAmount())
            .isEqualByComparingTo(balanceOf(account, LedgerAccount.SECURITIES));
        assertThat(holding.getQuantity()).isEqualTo(3L);

        assertThat(sumOf(entriesOf(account))).isEqualByComparingTo("0.00");
    }

    @Test
    void executionIsLinkedToTheLedgerTransactionThatRecordedIt() {
        Account account = openAccount("1000000.00");
        Stock stock = persistStock();
        Order buyOrder = persistOrder(account, stock, OrderSide.BUY, "100000.0000", 1L);

        matchingEngine.matchOrder(buyOrder.getId(), orderBookWithAsk("100000.0000", 1L), dailyPriceRange());

        Execution execution = executionRepository.findByOrderIdOrderByIdAsc(buyOrder.getId()).get(0);
        assertThat(execution.getTradeId()).isNotBlank();
        assertThat(execution.getLedgerTransaction()).isNotNull();

        // 체결의 tradeId가 원장 거래의 멱등키와 이어져야 재처리 시 중복 계상이 막힌다.
        assertThat(ledgerTransactionRepository.findByIdempotencyKey("FILL:" + execution.getTradeId()))
            .isPresent()
            .get()
            .extracting(transaction -> transaction.getId())
            .isEqualTo(execution.getLedgerTransaction().getId());
    }

    @Test
    void internalTradeRecordsBothAccountsInOneTransaction() {
        // 지금까지는 매수·매도 기록이 서로 연결되지 않아 "같은 체결"이라는 사실이 데이터에 없었다.
        Account buyer = openAccount("1000000.00");
        Account seller = openAccount("1000000.00");
        Stock stock = persistStock();
        holdingRepository.save(holdingOf(seller, stock, 2L, "90000.0000"));

        Order sellOrder = persistOrder(seller, stock, OrderSide.SELL, "100000.0000", 2L);
        Order buyOrder = persistOrder(buyer, stock, OrderSide.BUY, "100000.0000", 2L);

        matchingEngine.matchOrder(buyOrder.getId(), emptyOrderBook(), dailyPriceRange());

        Execution buyExecution = executionRepository.findByOrderIdOrderByIdAsc(buyOrder.getId()).get(0);
        Execution sellExecution = executionRepository.findByOrderIdOrderByIdAsc(sellOrder.getId()).get(0);
        assertThat(buyExecution.getLedgerTransaction().getId())
            .isEqualTo(sellExecution.getLedgerTransaction().getId());

        List<LedgerEntry> tradeEntries = ledgerEntryRepository.findAll().stream()
            .filter(e -> e.getTransaction().getId().equals(buyExecution.getLedgerTransaction().getId()))
            .toList();
        // 매수자 현금·유가증권, 매도자 현금·유가증권·실현손익
        assertThat(tradeEntries).hasSize(5);
        assertThat(sumOf(tradeEntries)).isEqualByComparingTo("0.00");

        // 90,000에 산 것을 100,000에 팔았으니 2주에 20,000. 수익은 대변이므로 부호가 음수다.
        assertThat(balanceOf(seller, LedgerAccount.REALIZED_PNL)).isEqualByComparingTo("-20000.00");
    }

    @Test
    void everyLedgerEntryEverWrittenSumsToZero() {
        // 복식부기를 쓰는 이유 그 자체. 단식부기로는 이 검증이 원리적으로 불가능하다.
        assertThat(ledgerEntryRepository.sumAllAmounts()).isEqualByComparingTo("0.00");
    }

    @Test
    void unbalancedPostingIsRejectedBeforeItReachesTheDatabase() {
        Account account = openAccount("1000000.00");

        assertThatThrownBy(() -> ledgerPostingService.post(
            LedgerTransactionType.ADJUSTMENT,
            "TEST:" + UUID.randomUUID(),
            "불균형 분개",
            List.of(
                Posting.cash(account, new BigDecimal("100.00")),
                Posting.of(account, LedgerAccount.EQUITY_FUNDING, new BigDecimal("-90.00"))
            )
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("분개 합계가 0이 아닙니다");
    }

    @Test
    void duplicateIdempotencyKeyIsRejected() {
        Account account = openAccount("1000000.00");
        String key = "TEST:" + UUID.randomUUID();
        List<Posting> postings = List.of(
            Posting.cash(account, new BigDecimal("100.00")),
            Posting.of(account, LedgerAccount.EQUITY_FUNDING, new BigDecimal("-100.00"))
        );
        ledgerPostingService.post(LedgerTransactionType.ADJUSTMENT, key, "첫 기록", postings);

        assertThatThrownBy(() ->
            ledgerPostingService.post(LedgerTransactionType.ADJUSTMENT, key, "재처리", postings))
            .isInstanceOf(Exception.class);
    }

    // --- helpers ---

    private BigDecimal sumOf(List<LedgerEntry> entries) {
        return entries.stream().map(LedgerEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal balanceOf(Account account, LedgerAccount ledgerAccount) {
        return ledgerEntryRepository.sumAmountByAccount(account.getId(), ledgerAccount);
    }

    private List<LedgerEntry> entriesOf(Account account) {
        return ledgerEntryRepository.findAll().stream()
            .filter(e -> e.getAccount().getId().equals(account.getId()))
            .toList();
    }

    private Account openAccount(String initialAmount) {
        long suffix = SEQUENCE.incrementAndGet();
        User user = userRepository.save(User.builder()
            .email("ledger-it-" + suffix + "@example.com")
            .passwordHash("test")
            .nickname("ledger-it-" + suffix)
            .status(Status.ACTIVE)
            .role(Role.USER)
            .build());
        return accountOpeningService.open(user, new BigDecimal(initialAmount));
    }

    private Stock persistStock() {
        long suffix = SEQUENCE.incrementAndGet();
        return stockRepository.save(Stock.builder()
            .symbol("LG" + suffix)
            .name("ledger " + suffix)
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

    private OrderBookResponse orderBookWithAsk(String price, long volume) {
        return new OrderBookResponse(new OrderBookResult(null, "USD",
            List.of(new OrderBookLevel(new BigDecimal(price), volume)), List.of()), LocalDateTime.now());
    }

    private OrderBookResponse emptyOrderBook() {
        return new OrderBookResponse(new OrderBookResult(null, "USD", List.of(), List.of()), LocalDateTime.now());
    }

    private DailyPriceRangeResponse dailyPriceRange() {
        return new DailyPriceRangeResponse("LG", null, new BigDecimal("200000.0000"),
            new BigDecimal("1000.0000"), "USD");
    }
}
