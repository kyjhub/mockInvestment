package com.papertrade.paper_trading.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.papertrade.paper_trading.Dto.DailyPriceRangeResponse;
import com.papertrade.paper_trading.Dto.OrderBookResponse;
import com.papertrade.paper_trading.Dto.OrderBookResult;
import com.papertrade.paper_trading.Entity.Account;
import com.papertrade.paper_trading.Entity.Holding;
import com.papertrade.paper_trading.Entity.Order;
import com.papertrade.paper_trading.Entity.Stock;
import com.papertrade.paper_trading.Entity.User;
import com.papertrade.paper_trading.Enum.AccountStatus;
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
import com.papertrade.paper_trading.Repository.HoldingRepository;
import com.papertrade.paper_trading.Repository.OrderRepository;
import com.papertrade.paper_trading.Repository.StockRepository;
import com.papertrade.paper_trading.Repository.UserRepository;
import com.papertrade.paper_trading.Service.LedgerPostingService;
import com.papertrade.paper_trading.Service.MatchingEngineTransactionService;
import com.papertrade.paper_trading.Service.LedgerPostingService.Posting;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 복식부기 쓰기 비용 측정 — 부하 테스트 후보 ④.
 *
 * <p>재는 것은 <b>체결 1건의 회계 기록을 남기는 비용</b>이다. 매칭 로직은 네 변형 모두 건드리지
 * 않는다. 모두 동기이고 모두 트랜잭션 안에서 쓴다. 다른 것은 <b>행 수</b>와 <b>DB 왕복 횟수</b>뿐이다.
 *
 * <ul>
 *   <li>SINGLE      — 단식 1행. 삭제된 {@code cash_transactions} 구조를 같은 모양의 표로 재현한다</li>
 *   <li>DOUBLE_JPA  — 현재 운영 경로. {@code LedgerPostingService.post()}. 거래 1행 + 분개 5행</li>
 *   <li>DOUBLE_JDBC — 같은 6행을 JDBC로 한 건씩. JPA 오버헤드를 걷어내고 왕복 횟수만 남긴다</li>
 *   <li>DOUBLE_BATCH— 같은 6행을 거래 1왕복 + 분개 배치 1왕복으로. SEQUENCE + batch_size 전환의 상한</li>
 * </ul>
 *
 * <p>비교의 의미:
 * <ul>
 *   <li>SINGLE vs DOUBLE_JDBC — 복식부기 자체의 값. 행이 1개에서 6개로 늘어난 대가</li>
 *   <li>DOUBLE_JPA vs DOUBLE_JDBC — 영속성 컨텍스트 오버헤드</li>
 *   <li>DOUBLE_JDBC vs DOUBLE_BATCH — <b>배치의 효과.</b> 이번 결정의 근거</li>
 *   <li>DOUBLE_JPA vs DOUBLE_BATCH — 지금 코드에서 배치로 전환했을 때 기대 개선</li>
 * </ul>
 *
 * <p>현재 {@code LedgerTransaction}·{@code LedgerEntry}는 {@code GenerationType.IDENTITY}라
 * Hibernate가 생성 키를 즉시 받아야 해서 <b>JDBC 배치가 비활성화</b>된다. 그래서 DOUBLE_BATCH는
 * 엔티티를 고치기 전에 그 전환이 값어치가 있는지부터 재기 위해 JDBC로 흉내 낸다.
 *
 * <p>실행 전제: {@code POSTGRES_PORT=55432 REDIS_PORT=56379 docker compose up -d postgres redis}
 */
abstract class LedgerWriteCostBenchmarkSupport {

    /** 한 회차에 기록할 체결 수. */
    private static final int FILL_COUNT = 500;

    /** 내부 체결 1건의 분개 수. 매수 현금·증권, 매도 현금·증권, 실현손익 = 5줄(수수료 0). */
    private static final int ENTRIES_PER_FILL = 5;

    private static final BigDecimal AMOUNT = new BigDecimal("1000.00");

    @Autowired private LedgerPostingService ledgerPostingService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StockRepository stockRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private HoldingRepository holdingRepository;
    @Autowired private MatchingEngineTransactionService matchingService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;
    @PersistenceContext private EntityManager entityManager;

    private enum Variant { SINGLE, DOUBLE_JPA_IDENTITY, DOUBLE_JPA, DOUBLE_JDBC, DOUBLE_BATCH }

    protected void printComparisonTable() throws Exception {
        createSingleEntryTable();
        String runId = UUID.randomUUID().toString().substring(0, 4);
        Fixture fixture = seed(runId);

        // JIT·커넥션 풀 워밍업. 결과에 넣지 않는다.
        for (Variant variant : Variant.values()) {
            measure(variant, fixture, 1, 50, runId + "w");
        }

        java.util.Map<Variant, Result> sequential = new java.util.EnumMap<>(Variant.class);
        for (int threads : List.of(1, 10)) {
            System.out.printf("%n== 체결 %d건 / 분개 %d줄 / threads=%d ==%n",
                FILL_COUNT, ENTRIES_PER_FILL, threads);
            System.out.println("변형\t\t총시간(ms)\t건당p50(us)\t건당p95(us)\t처리량(건/초)");

            Result baseline = null;
            for (Variant variant : Variant.values()) {
                Result result = measure(variant, fixture, threads, FILL_COUNT, runId + threads);
                if (baseline == null) {
                    baseline = result;
                }
                if (threads == 1) {
                    sequential.put(variant, result);
                }
                System.out.printf("%-14s\t%d\t\t%d\t\t%d\t\t%.0f  (SINGLE 대비 %.2fx)%n",
                    variant, result.totalMs(), result.p50Us(), result.p95Us(),
                    FILL_COUNT * 1000.0 / result.totalMs(),
                    result.totalMs() / (double) baseline.totalMs());
            }
        }

        long fillUs = measureFullFill();
        long single = sequential.get(Variant.SINGLE).p50Us();
        long jpa = sequential.get(Variant.DOUBLE_JPA).p50Us();
        long batch = sequential.get(Variant.DOUBLE_BATCH).p50Us();
        long identity = sequential.get(Variant.DOUBLE_JPA_IDENTITY).p50Us();

        System.out.printf("%n== 체결 1건 전체(내부 체결, 외부 호출 없음) 중앙값 = %d us ==%n", fillUs);
        System.out.println("변형\t\t쓰기(us)\t체결 1건 중 비중");
        for (Variant variant : Variant.values()) {
            long write = sequential.get(variant).p50Us();
            System.out.printf("%-14s\t%d\t\t%.1f%%%n", variant, write, write * 100.0 / fillUs);
        }
        System.out.printf("%n복식부기의 순수 대가 (SINGLE→DOUBLE_JPA) = %+d us  체결의 %.1f%%%n",
            jpa - single, (jpa - single) * 100.0 / fillUs);
        System.out.printf("%n원장 쓰기  IDENTITY %d us → SEQUENCE+배치 %d us  (%.1f%% 단축, %.2f배)%n",
            identity, jpa, (identity - jpa) * 100.0 / identity, identity / (double) jpa);
        System.out.printf("체결 1건  %d us → %d us  (처리량 %.2fx)%n",
            fillUs, fillUs - (identity - jpa), fillUs / (double) (fillUs - (identity - jpa)));
        System.out.printf("남은 JDBC 대비 여지 = %d us (체결의 %.1f%%)%n",
            jpa - batch, (jpa - batch) * 100.0 / fillUs);
        System.out.println();
    }

    /**
     * 원장 쓰기 비용을 비율로 말하려면 분모가 필요하다. 내부 체결 1건을 실제 매칭 경로로 돌려
     * 전체 소요시간을 잰다. 외부 호가는 비워 내부 체결만 일어나게 한다.
     */
    private long measureFullFill() {
        int fills = 100;
        List<Long> latencies = new ArrayList<>();
        String tag = UUID.randomUUID().toString().substring(0, 4);
        DailyPriceRangeResponse dailyRange = new DailyPriceRangeResponse(
            "LBF", OffsetDateTime.now(),
            new BigDecimal("1000.0000"), new BigDecimal("1.0000"), "USD");
        OrderBookResponse emptyBook = new OrderBookResponse(
            new OrderBookResult(OffsetDateTime.now(), "USD", List.of(), List.of()),
            java.time.LocalDateTime.now());

        for (int i = 0; i < fills; i++) {
            Long buyOrderId = seedMatchablePair(tag + "-" + i);
            long began = System.nanoTime();
            matchingService.matchOrder(buyOrderId, emptyBook, dailyRange);
            latencies.add((System.nanoTime() - began) / 1_000);
        }
        Collections.sort(latencies);
        return latencies.get(latencies.size() / 2);
    }

    /** 같은 종목·같은 가격의 매수/매도를 서로 다른 계좌에 하나씩. 자전거래 차단을 피해야 체결된다. */
    private Long seedMatchablePair(String tag) {
        BigDecimal price = new BigDecimal("100.0000");
        Stock stock = stockRepository.save(Stock.builder()
            .symbol("LF" + tag).name("fill bench " + tag).market(Market.NASDAQ)
            .securityType(SecurityType.STOCK).isCommonShare(true)
            .status(StockStatus.ACTIVE).currency("USD").build());
        Account buyer = seedAccount("fb-" + tag);
        Account seller = seedAccount("fs-" + tag);
        holdingFor(seller, stock);
        Order sellOrder = orderRepository.save(Order.create(
            seller, stock, null, OrderSide.SELL, OrderType.LIMIT, price, 10L, price));
        Order buyOrder = orderRepository.save(Order.create(
            buyer, stock, null, OrderSide.BUY, OrderType.LIMIT, price, 10L, price));
        assertThat(sellOrder.getId()).isNotNull();
        return buyOrder.getId();
    }

    /** 매도자가 팔 수량을 미리 들고 있어야 체결이 성립한다. */
    private void holdingFor(Account seller, Stock stock) {
        Holding holding = Holding.create(seller, stock);
        holding.buy(10L, new BigDecimal("100.0000"));
        holdingRepository.save(holding);
    }

    private Account seedAccount(String tag) {
        User user = userRepository.save(User.builder()
            .email(tag + "@example.com").passwordHash("bench").nickname(tag)
            .status(Status.ACTIVE).role(Role.USER).build());
        return accountRepository.save(Account.builder()
            .user(user).accountNumber("LB-" + tag)
            .cashBalance(new BigDecimal("100000000.00"))
            .initialBalance(new BigDecimal("100000000.00"))
            .totalAssetValue(new BigDecimal("100000000.00"))
            .status(AccountStatus.ACTIVE).build());
    }

    private record Result(long totalMs, long p50Us, long p95Us) {
    }

    private Result measure(Variant variant, Fixture fixture, int threads, int fills, String tag)
        throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger sequence = new AtomicInteger();
        List<Future<Long>> futures = new ArrayList<>();
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        for (int i = 0; i < fills; i++) {
            futures.add(pool.submit(() -> {
                int index = sequence.getAndIncrement();
                String key = variant + ":" + tag + ":" + index;
                start.await();
                long began = System.nanoTime();
                transactionTemplate.executeWithoutResult(ignored -> write(variant, fixture, key));
                return (System.nanoTime() - began) / 1_000;
            }));
        }

        long startedAt = System.nanoTime();
        start.countDown();
        List<Long> latencies = new ArrayList<>();
        for (Future<Long> future : futures) {
            latencies.add(future.get(5, TimeUnit.MINUTES));
        }
        long totalMs = (System.nanoTime() - startedAt) / 1_000_000;
        pool.shutdown();

        Collections.sort(latencies);
        return new Result(totalMs, latencies.get(latencies.size() / 2),
            latencies.get((int) (latencies.size() * 0.95)));
    }

    private void write(Variant variant, Fixture fixture, String key) {
        switch (variant) {
            case SINGLE -> writeSingleEntry(fixture);
            case DOUBLE_JPA -> writeDoubleViaJpa(fixture, key);
            case DOUBLE_JPA_IDENTITY -> writeDoubleViaJpaWithIdentity(fixture, key);
            case DOUBLE_JDBC -> writeDoubleViaJdbc(fixture, key, false);
            case DOUBLE_BATCH -> writeDoubleViaJdbc(fixture, key, true);
        }
    }

    /** 옛 단식 구조: 현금 이동 1행. */
    private void writeSingleEntry(Fixture fixture) {
        withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "insert into bench_single_entry"
                    + " (account_id, transaction_type, amount, balance_after, created_at)"
                    + " values (?, ?, ?, ?, now())")) {
                statement.setLong(1, fixture.accountId());
                statement.setString(2, "TRADE");
                statement.setBigDecimal(3, AMOUNT);
                statement.setBigDecimal(4, AMOUNT);
                statement.executeUpdate();
            }
        });
    }

    /**
     * 현재 운영 경로. IDENTITY라 6행이 6번의 개별 INSERT 왕복이 된다.
     *
     * <p>Account·Stock을 {@code findById}로 조회하지 않고 {@code getReference}로 받는다. 실제 체결
     * 경로에서는 두 엔티티가 이미 락과 함께 로드되어 있어 <b>원장 쓰기의 비용이 아니다.</b> 여기서
     * 조회하면 SELECT 2번이 복식부기 몫으로 잘못 계상되어 JDBC 변형과의 비교가 어긋난다.
     *
     * <p>같은 이유로 {@code Posting.cash()} 팩터리 대신 record를 직접 만든다. 팩터리는
     * {@code account.getCashBalance()}를 읽는데, 프록시에 그걸 물으면 지연 로딩 SELECT가 뜬다.
     * 운영에서는 이미 로드된 값을 읽으므로 리터럴을 넣는 쪽이 오히려 실제에 가깝다.
     */
    private void writeDoubleViaJpa(Fixture fixture, String key) {
        Account account = entityManager.getReference(Account.class, fixture.accountId());
        Stock stock = entityManager.getReference(Stock.class, fixture.stockId());
        ledgerPostingService.post(
            LedgerTransactionType.TRADE,
            key,
            "benchmark",
            List.of(
                new Posting(account, LedgerAccount.CASH, null, AMOUNT.negate(), null, AMOUNT),
                new Posting(account, LedgerAccount.SECURITIES, stock, AMOUNT, 10L, AMOUNT),
                new Posting(account, LedgerAccount.CASH, null, AMOUNT, null, AMOUNT),
                new Posting(account, LedgerAccount.SECURITIES, stock, AMOUNT.negate(), -10L, AMOUNT),
                new Posting(account, LedgerAccount.REALIZED_PNL, null, BigDecimal.ZERO, null, null)
            )
        );
    }

    /**
     * <b>옛 구조 대조군.</b> ID 전략만 {@code IDENTITY}인 엔티티에 같은 6행을 쓴다.
     *
     * <p>{@code IDENTITY}면 Hibernate가 생성 키를 INSERT 직후 받아야 해서 배치를 못 하고 6행이
     * 6번의 왕복이 된다. 운영 엔티티를 {@code SEQUENCE}로 전환한 뒤에도 전후 비교를 이어가려고
     * 남겨 둔 변형이다.
     */
    private void writeDoubleViaJpaWithIdentity(Fixture fixture, String key) {
        Account account = entityManager.getReference(Account.class, fixture.accountId());
        Stock stock = entityManager.getReference(Stock.class, fixture.stockId());
        BenchLedgerTransaction transaction = new BenchLedgerTransaction("TRADE", key, "benchmark");
        entityManager.persist(transaction);
        entityManager.persist(new BenchLedgerEntry(
            transaction, account, "CASH", null, AMOUNT.negate(), null, AMOUNT));
        entityManager.persist(new BenchLedgerEntry(
            transaction, account, "SECURITIES", stock, AMOUNT, 10L, AMOUNT));
        entityManager.persist(new BenchLedgerEntry(
            transaction, account, "CASH", null, AMOUNT, null, AMOUNT));
        entityManager.persist(new BenchLedgerEntry(
            transaction, account, "SECURITIES", stock, AMOUNT.negate(), -10L, AMOUNT));
        entityManager.persist(new BenchLedgerEntry(
            transaction, account, "REALIZED_PNL", null, BigDecimal.ZERO, null, null));
    }

    /** 같은 6행을 JDBC로. {@code batched}면 분개 5행을 한 번의 왕복으로 보낸다. */
    private void writeDoubleViaJdbc(Fixture fixture, String key, boolean batched) {
        withConnection(connection -> {
            long transactionId;
            try (PreparedStatement statement = connection.prepareStatement(
                "insert into ledger_transactions"
                    + " (id, transaction_type, idempotency_key, occurred_at, description)"
                    + " values (nextval('bench_jdbc_tx_seq'), ?, ?, now(), ?) returning id")) {
                statement.setString(1, "TRADE");
                statement.setString(2, key);
                statement.setString(3, "benchmark");
                try (ResultSet keys = statement.executeQuery()) {
                    keys.next();
                    transactionId = keys.getLong(1);
                }
            }

            try (PreparedStatement statement = connection.prepareStatement(
                "insert into ledger_entries"
                    + " (id, transaction_id, account_id, ledger_account, stock_id, amount,"
                    + "  quantity, balance_after, created_at)"
                    + " values (nextval('bench_jdbc_entry_seq'), ?, ?, ?, ?, ?, ?, ?, now())")) {
                for (int i = 0; i < ENTRIES_PER_FILL; i++) {
                    statement.setLong(1, transactionId);
                    statement.setLong(2, fixture.accountId());
                    statement.setString(3, i % 2 == 0 ? "CASH" : "SECURITIES");
                    if (i % 2 == 0) {
                        statement.setNull(4, java.sql.Types.BIGINT);
                        statement.setNull(6, java.sql.Types.BIGINT);
                    } else {
                        statement.setLong(4, fixture.stockId());
                        statement.setLong(6, 10L);
                    }
                    statement.setBigDecimal(5, AMOUNT);
                    statement.setBigDecimal(7, AMOUNT);
                    if (batched) {
                        statement.addBatch();
                    } else {
                        statement.executeUpdate();
                    }
                }
                if (batched) {
                    statement.executeBatch();
                }
            }
        });
    }

    private interface ConnectionWork {
        void run(Connection connection) throws SQLException;
    }

    /** 트랜잭션에 참여 중인 커넥션을 그대로 쓴다. 새로 열면 트랜잭션 비용을 빼먹게 된다. */
    private void withConnection(ConnectionWork work) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            work.run(connection);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private void createSingleEntryTable() {
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored ->
            withConnection(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("""
                        create table if not exists bench_single_entry (
                            id bigserial primary key,
                            account_id bigint not null,
                            transaction_type varchar(30) not null,
                            amount numeric(19,2) not null,
                            balance_after numeric(19,2),
                            created_at timestamp not null
                        )""");
                    statement.execute(
                        "create index if not exists idx_bench_single_entry_account"
                            + " on bench_single_entry (account_id)");
                    // 원장 id가 SEQUENCE로 바뀌면서 컬럼 DEFAULT가 사라졌다. JDBC 변형은 id를
                    // 직접 채워야 한다. Hibernate pooled optimizer가 선점한 블록과 겹치지 않도록
                    // 충분히 높은 값에서 시작하는 전용 시퀀스를 쓴다.
                    statement.execute("create sequence if not exists bench_jdbc_tx_seq"
                        + " start with 1000000000 increment by 1");
                    statement.execute("create sequence if not exists bench_jdbc_entry_seq"
                        + " start with 1000000000 increment by 1");
                }
            }));
    }

    private record Fixture(Long accountId, Long stockId) {
    }

    private Fixture seed(String tag) {
        Account account = seedAccount("ledger-bench-" + tag);
        Stock stock = stockRepository.save(Stock.builder()
            .symbol("LB" + tag).name("ledger bench " + tag).market(Market.NASDAQ)
            .securityType(SecurityType.STOCK).isCommonShare(true)
            .status(StockStatus.ACTIVE).currency("USD").build());
        return new Fixture(account.getId(), stock.getId());
    }

    /** 측정 구간에서 실제로 행이 쌓였는지 확인한다. 그러지 않으면 빈 루프를 잰 것이 된다. */
    protected void assertRowsWereWritten() {
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored ->
            withConnection(connection -> {
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery(
                         "select count(*) from ledger_entries")) {
                    rows.next();
                    assertThat(rows.getLong(1)).isPositive();
                }
            }));
    }
}
