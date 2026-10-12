package com.papertrade.paper_trading.Ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.papertrade.paper_trading.Dto.LedgerCacheRebuildResult;
import com.papertrade.paper_trading.Dto.LedgerReconciliationReport;
import com.papertrade.paper_trading.Dto.LedgerReversalResult;
import com.papertrade.paper_trading.Dto.OrderBookLevel;
import com.papertrade.paper_trading.Dto.OrderBookResponse;
import com.papertrade.paper_trading.Dto.OrderBookResult;
import com.papertrade.paper_trading.Entity.Account;
import com.papertrade.paper_trading.Entity.Holding;
import com.papertrade.paper_trading.Entity.LedgerEntry;
import com.papertrade.paper_trading.Entity.LedgerTransaction;
import com.papertrade.paper_trading.Entity.Order;
import com.papertrade.paper_trading.Entity.Stock;
import com.papertrade.paper_trading.Entity.User;
import com.papertrade.paper_trading.Enum.LedgerAccount;
import com.papertrade.paper_trading.Enum.LedgerTransactionType;
import com.papertrade.paper_trading.Enum.Market;
import com.papertrade.paper_trading.Enum.OrderSide;
import com.papertrade.paper_trading.Enum.OrderStatus;
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
import com.papertrade.paper_trading.Service.LedgerCorrectionService;
import com.papertrade.paper_trading.Service.LedgerPostingService;
import com.papertrade.paper_trading.Service.LedgerPostingService.Posting;
import com.papertrade.paper_trading.Service.LedgerReconciliationService;
import com.papertrade.paper_trading.Service.MatchingEngineTransactionService;
import com.papertrade.paper_trading.support.ApplicationIntegrationTest;
import com.papertrade.paper_trading.support.IntegrationTestContainers;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 대사가 보고한 불일치를 정정하는 수단.
 *
 * <p>정정이 맞았는지의 기준은 대사다. 일부러 깨뜨리고, 정정한 뒤 대사가 그 계좌를 더 이상 불일치로
 * 보고하지 않는지 본다. 그리고 어느 정정도 과거 원장 기록을 수정하거나 지우지 않아야 한다.
 */
@ApplicationIntegrationTest
@AutoConfigureMockMvc
class LedgerCorrectionIntegrationTest extends IntegrationTestContainers {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Long ADMIN_ID = 1L;

    @Autowired private LedgerCorrectionService correctionService;
    @Autowired private LedgerReconciliationService reconciliationService;
    @Autowired private LedgerPostingService ledgerPostingService;
    @Autowired private AccountOpeningService accountOpeningService;
    @Autowired private MatchingEngineTransactionService matchingEngine;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StockRepository stockRepository;
    @Autowired private HoldingRepository holdingRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ExecutionRepository executionRepository;
    @Autowired private LedgerTransactionRepository ledgerTransactionRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MockMvc mockMvc;

    // --- 캐시 재계산: 원장은 맞고 캐시가 틀렸을 때 ---

    @Test
    void rebuildRestoresCachesThatDriftedFromTheLedger() {
        Account account = openAccount("1000000.00");
        Stock stock = persistStock();
        buy(account, stock, "100000.0000", 3L);
        Long holdingId = holdingOf(account, stock).getId();

        // 체결 코드의 버그가 만들 수 있는 상태. 원장은 그대로이고 캐시만 어긋난다.
        tamper(account, "1.00", "5.00", holdingId, 9L, "1.00");
        LedgerReconciliationReport broken = reconciliationService.reconcile();
        assertThat(broken.cashBalanceMismatchAccountIds()).contains(account.getId());
        assertThat(broken.holdingQuantityMismatchIds()).contains(holdingId);

        LedgerCacheRebuildResult result = correctionService.rebuildCaches(account.getId(), ADMIN_ID, "체결 캐시 버그");

        assertThat(result.changed()).isTrue();
        assertThat(result.cashBalanceBefore()).isEqualByComparingTo("1.00");
        assertThat(result.cashBalanceAfter()).isEqualByComparingTo("700000.00");
        assertThat(result.changedHoldings()).singleElement()
            .satisfies(change -> assertThat(change.quantityAfter()).isEqualTo(3L));

        Account reloaded = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(reloaded.getCashBalance()).isEqualByComparingTo("700000.00");
        assertThat(reloaded.getRealizedProfit()).isEqualByComparingTo("0.00");
        Holding holding = holdingRepository.findById(holdingId).orElseThrow();
        assertThat(holding.getQuantity()).isEqualTo(3L);
        assertThat(holding.getTotalPurchaseAmount()).isEqualByComparingTo("300000.00");
        assertThat(holding.getAveragePrice()).isEqualByComparingTo("100000.0000");

        assertNoLongerReported(account, holdingId);
    }

    @Test
    void rebuildDoesNotTouchTheLedger() {
        Account account = openAccount("1000000.00");
        tamper(account, "1.00", "0.00", null, 0L, null);
        long transactionsBefore = ledgerTransactionRepository.count();

        correctionService.rebuildCaches(account.getId(), ADMIN_ID, "캐시만 틀림");

        // 원장이 원본이다. 캐시를 맞추려고 분개를 추가하면 원장이 캐시에 끌려가는 것이 된다.
        assertThat(ledgerTransactionRepository.count()).isEqualTo(transactionsBefore);
    }

    @Test
    void rebuildReportsNothingWhenCachesAlreadyMatch() {
        Account account = openAccount("1000000.00");

        LedgerCacheRebuildResult result = correctionService.rebuildCaches(account.getId(), ADMIN_ID, "점검");

        assertThat(result.changed()).isFalse();
    }

    // --- 반대분개: 원장 자체에 잘못된 거래가 있을 때 ---

    @Test
    void reversalOffsetsTheTradeAndKeepsTheOriginalUntouched() {
        Account account = openAccount("1000000.00");
        Stock stock = persistStock();
        Order order = buy(account, stock, "100000.0000", 3L);
        LedgerTransaction trade = tradeTransactionOf(order);
        List<LedgerEntry> originalEntries = ledgerEntryRepository.findByTransactionIdOrderByIdAsc(trade.getId());

        LedgerReversalResult result = correctionService.reverse(trade.getId(), ADMIN_ID, "잘못된 체결가");

        // 원거래와 그 분개는 그대로 남는다. 고치지도 지우지도 않는다.
        assertThat(ledgerTransactionRepository.findById(trade.getId())).isPresent();
        assertThat(ledgerEntryRepository.findByTransactionIdOrderByIdAsc(trade.getId()))
            .extracting(LedgerEntry::getAmount)
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactlyElementsOf(originalEntries.stream().map(LedgerEntry::getAmount).toList());

        LedgerTransaction reversal = ledgerTransactionRepository.findById(result.reversalTransactionId()).orElseThrow();
        assertThat(reversal.getTransactionType()).isEqualTo(LedgerTransactionType.ADJUSTMENT);
        assertThat(reversal.getIdempotencyKey()).isEqualTo("REVERSAL:" + trade.getId());
        assertThat(reversal.getDescription()).contains("잘못된 체결가");
        List<LedgerEntry> reversalEntries = ledgerEntryRepository.findByTransactionIdOrderByIdAsc(reversal.getId());
        assertThat(reversalEntries).extracting(LedgerEntry::getAmount)
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactlyElementsOf(originalEntries.stream().map(e -> e.getAmount().negate()).toList());

        // 새 분개가 잔고에 반영된다.
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getCashBalance())
            .isEqualByComparingTo("1000000.00");
        Holding holding = holdingOf(account, stock);
        assertThat(holding.getQuantity()).isZero();
        assertThat(holding.getTotalPurchaseAmount()).isEqualByComparingTo("0.00");

        // 주문과 체결 기록은 손대지 않는다.
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(executionRepository.findByOrderIdOrderByIdAsc(order.getId())).hasSize(1);

        assertNoLongerReported(account, holding.getId());
        LedgerReconciliationReport report = reconciliationService.reconcile();
        assertThat(report.unbalancedTransactionIds()).doesNotContain(trade.getId(), reversal.getId());
    }

    @Test
    void aTransactionCanBeReversedOnlyOnce() {
        Account account = openAccount("1000000.00");
        LedgerTransaction trade = tradeTransactionOf(buy(account, persistStock(), "100000.0000", 1L));
        correctionService.reverse(trade.getId(), ADMIN_ID, "첫 정정");

        assertThatThrownBy(() -> correctionService.reverse(trade.getId(), ADMIN_ID, "두 번째"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("이미 반대분개된 거래입니다.");
    }

    @Test
    void aReversalCannotItselfBeReversed() {
        Account account = openAccount("1000000.00");
        LedgerTransaction trade = tradeTransactionOf(buy(account, persistStock(), "100000.0000", 1L));
        LedgerReversalResult reversal = correctionService.reverse(trade.getId(), ADMIN_ID, "정정");

        // 뒤집으면 원거래가 되살아난다. 그게 필요하면 올바른 거래를 새로 기록해야 한다.
        assertThatThrownBy(() -> correctionService.reverse(reversal.reversalTransactionId(), ADMIN_ID, "되돌리기"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("반대분개 거래는 다시 반대분개할 수 없습니다.");
    }

    @Test
    void reversalThatWouldMakeCashNegativeIsRejectedAndWritesNothing() {
        // 개시 입금 100만 중 30만을 이미 썼다. 개시 입금을 상쇄하면 예수금이 −30만이 된다.
        Account account = openAccount("1000000.00");
        buy(account, persistStock(), "100000.0000", 3L);
        LedgerTransaction opening = ledgerTransactionRepository.findByIdempotencyKey("OPEN:" + account.getId())
            .orElseThrow();

        assertThatThrownBy(() -> correctionService.reverse(opening.getId(), ADMIN_ID, "개시 금액 오류"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("예수금이 음수가 됩니다");

        assertThat(ledgerTransactionRepository.findByIdempotencyKey("REVERSAL:" + opening.getId())).isEmpty();
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getCashBalance())
            .isEqualByComparingTo("700000.00");
    }

    @Test
    void theDatabaseRejectsASecondReversalEvenIfTheServiceCheckIsBypassed() {
        // 동시에 들어온 두 정정 요청은 서비스의 "이미 정정됨" 검사를 함께 통과할 수 있다. 마지막 방어선은 DB다.
        Account account = openAccount("1000000.00");
        LedgerTransaction opening = ledgerTransactionRepository.findByIdempotencyKey("OPEN:" + account.getId())
            .orElseThrow();
        List<Posting> balanced = List.of(
            Posting.of(account, LedgerAccount.EQUITY_FUNDING, BigDecimal.ONE),
            Posting.of(account, LedgerAccount.EQUITY_FUNDING, BigDecimal.ONE.negate()));
        inTransaction(() -> ledgerPostingService.postReversal(opening, "BYPASS-1:" + opening.getId(), "우회 1", balanced));

        assertThatThrownBy(() -> inTransaction(() ->
            ledgerPostingService.postReversal(opening, "BYPASS-2:" + opening.getId(), "우회 2", balanced)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- 관리자 API ---

    @Test
    void adminCanRebuildCachesThroughTheApi() throws Exception {
        Account account = openAccount("1000000.00");
        tamper(account, "1.00", "0.00", null, 0L, null);

        mockMvc.perform(post("/api/v1/admin/ledger/accounts/{id}/cache-rebuild", account.getId())
                .with(authentication(as(persistUser(Role.ADMIN), "ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"대사 불일치 정정\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cashBalanceAfter").value(1000000.00));
    }

    @Test
    void regularUsersCannotCorrectTheLedger() throws Exception {
        Account account = openAccount("1000000.00");

        mockMvc.perform(post("/api/v1/admin/ledger/accounts/{id}/cache-rebuild", account.getId())
                .with(authentication(as(persistUser(Role.USER), "ROLE_USER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"권한 없음\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void aReasonIsRequired() throws Exception {
        Account account = openAccount("1000000.00");

        mockMvc.perform(post("/api/v1/admin/ledger/accounts/{id}/cache-rebuild", account.getId())
                .with(authentication(as(persistUser(Role.ADMIN), "ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\" \"}"))
            .andExpect(status().isBadRequest());
    }

    // --- fixtures ---

    /** 대사는 전역이라 다른 test가 심은 계좌도 잡는다. 이 test의 계좌와 보유만 본다. */
    private void assertNoLongerReported(Account account, Long holdingId) {
        LedgerReconciliationReport report = reconciliationService.reconcile();
        assertThat(report.cashBalanceMismatchAccountIds()).doesNotContain(account.getId());
        assertThat(report.realizedProfitMismatchAccountIds()).doesNotContain(account.getId());
        assertThat(report.holdingQuantityMismatchIds()).doesNotContain(holdingId);
        assertThat(report.holdingCostMismatchIds()).doesNotContain(holdingId);
    }

    private void tamper(Account account, String cash, String realized, Long holdingId, long quantity, String cost) {
        inTransaction(() -> {
            entityManager.createQuery("update Account a set a.cashBalance = :cash, a.realizedProfit = :realized where a.id = :id")
                .setParameter("cash", new BigDecimal(cash))
                .setParameter("realized", new BigDecimal(realized))
                .setParameter("id", account.getId())
                .executeUpdate();
            if (holdingId != null) {
                entityManager.createQuery("update Holding h set h.quantity = :quantity, h.totalPurchaseAmount = :cost where h.id = :id")
                    .setParameter("quantity", quantity)
                    .setParameter("cost", new BigDecimal(cost))
                    .setParameter("id", holdingId)
                    .executeUpdate();
            }
            return null;
        });
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(ignored -> work.get());
    }

    private UsernamePasswordAuthenticationToken as(User user, String role) {
        return new UsernamePasswordAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority(role)));
    }

    private Order buy(Account account, Stock stock, String price, long quantity) {
        BigDecimal unitPrice = new BigDecimal(price);
        Order order = orderRepository.saveAndFlush(Order.create(
            account, stock, null, OrderSide.BUY, OrderType.LIMIT, unitPrice, quantity, unitPrice));
        matchingEngine.matchOrder(order.getId(), new OrderBookResponse(new OrderBookResult(null, "USD",
            List.of(new OrderBookLevel(unitPrice, quantity)), List.of()), LocalDateTime.now()));
        return order;
    }

    private LedgerTransaction tradeTransactionOf(Order order) {
        Long transactionId = inTransaction(() ->
            executionRepository.findByOrderIdOrderByIdAsc(order.getId()).get(0).getLedgerTransaction().getId());
        return ledgerTransactionRepository.findById(transactionId).orElseThrow();
    }

    private Holding holdingOf(Account account, Stock stock) {
        return holdingRepository.findAll().stream()
            .filter(h -> h.getAccount().getId().equals(account.getId()) && h.getStock().getId().equals(stock.getId()))
            .findFirst()
            .orElseThrow();
    }

    private Account openAccount(String initialAmount) {
        return accountOpeningService.open(persistUser(Role.USER), new BigDecimal(initialAmount));
    }

    private User persistUser(Role role) {
        long suffix = SEQUENCE.incrementAndGet();
        return userRepository.save(User.builder()
            .email("correction-it-" + suffix + "@example.com")
            .passwordHash("test")
            .nickname("correction-it-" + suffix)
            .status(Status.ACTIVE)
            .role(role)
            .build());
    }

    private Stock persistStock() {
        long suffix = SEQUENCE.incrementAndGet();
        return stockRepository.save(Stock.builder()
            .symbol("LC" + suffix)
            .name("correction " + suffix)
            .market(Market.NASDAQ)
            .securityType(SecurityType.STOCK)
            .isCommonShare(true)
            .status(StockStatus.ACTIVE)
            .currency("USD")
            .build());
    }
}
