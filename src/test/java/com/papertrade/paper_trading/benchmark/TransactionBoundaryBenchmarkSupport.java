package com.papertrade.paper_trading.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.papertrade.paper_trading.Dto.DailyPriceRangeResponse;
import com.papertrade.paper_trading.Dto.OrderBookLevel;
import com.papertrade.paper_trading.Dto.OrderBookResponse;
import com.papertrade.paper_trading.Dto.OrderBookResult;
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
import com.papertrade.paper_trading.Repository.AccountRepository;
import com.papertrade.paper_trading.Repository.OrderRepository;
import com.papertrade.paper_trading.Repository.StockRepository;
import com.papertrade.paper_trading.Repository.UserRepository;
import com.papertrade.paper_trading.Service.MatchingEngineTransactionService;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 외부 API 호출을 트랜잭션 안에서 하느냐 밖에서 하느냐가 처리량에 미치는 영향을 측정한다.
 *
 * <p>비교 대상은 매칭 로직이 아니라 <b>외부 호출의 위치</b>다. 두 변형 모두 같은
 * {@link MatchingEngineTransactionService#matchOrder}를 호출하고, 외부 호출을 흉내 낸 sleep의
 * 위치만 다르다.
 *
 * <ul>
 *   <li>INSIDE  — 커밋 45f18af 시점의 순서. 주문 row에 락을 건 뒤 외부 응답을 기다리므로
 *       기다리는 동안 DB 커넥션과 row 락을 함께 붙잡는다.</li>
 *   <li>OUTSIDE — 현재 코드의 순서. 외부 응답을 먼저 받아둔 뒤 트랜잭션을 연다.</li>
 * </ul>
 *
 * <p>실행 전제: {@code POSTGRES_PORT=55432 REDIS_PORT=56379 docker compose up -d postgres redis}
 */
abstract class TransactionBoundaryBenchmarkSupport {

    /** 한 회차에 처리할 주문 수. 변형·동시성과 무관하게 고정한다. */
    private static final int ORDER_COUNT = 50;

    /** 외부 API 한 번의 응답 시간. 프로젝트 문서에 기록된 토스 REST 실측치(약 150ms)의 두 배로 잡았다. */
    private static final long EXTERNAL_DELAY_MS = 300;

    private static final BigDecimal ORDER_PRICE = new BigDecimal("100.0000");
    private static final long ORDER_QUANTITY = 10L;

    @Autowired
    private MatchingEngineTransactionService matchingService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    /** 동시성을 올려 가며 두 변형을 번갈아 측정하고 표로 출력한다. */
    protected void printComparisonTable() throws Exception {
        int poolSize = ((HikariDataSource) dataSource).getMaximumPoolSize();
        String runId = UUID.randomUUID().toString().substring(0, 4);

        // JIT·커넥션 풀 워밍업. 결과에 포함하지 않는다.
        run(false, 8, seed(runId + "w"));

        List<long[]> rows = new ArrayList<>();
        for (int threads : List.of(5, 10, 25, 50, 100)) {
            Result inside = run(true, threads, seed(runId + "i" + threads));
            Result outside = run(false, threads, seed(runId + "o" + threads));
            rows.add(new long[] {threads, inside.totalMs(), outside.totalMs()});

            System.out.printf(
                "pool=%-2d threads=%-3d | INSIDE  total=%5dms p50=%5dms p95=%5dms | OUTSIDE total=%5dms p50=%5dms p95=%5dms | %.2fx%n",
                poolSize, threads,
                inside.totalMs(), inside.p50Ms(), inside.p95Ms(),
                outside.totalMs(), outside.p50Ms(), outside.p95Ms(),
                inside.totalMs() / (double) outside.totalMs()
            );
        }

        System.out.println();
        System.out.printf(
            "== 주문 %d건 / 외부 호출 %dms / HikariCP maximum-pool-size=%d ==%n",
            ORDER_COUNT, EXTERNAL_DELAY_MS, poolSize
        );
        System.out.println("threads\tINSIDE(ms)\tOUTSIDE(ms)\tINSIDE 처리량(건/초)");
        for (long[] row : rows) {
            System.out.printf(
                "%d\t%d\t%d\t%.1f%n",
                row[0], row[1], row[2], ORDER_COUNT * 1000.0 / row[1]
            );
        }
        System.out.println();
    }

    private record Target(Long orderId, String symbol) {
    }

    private record Result(long totalMs, long p50Ms, long p95Ms) {
    }

    private Result run(boolean externalCallInsideTransaction, int threads, List<Target> targets) throws Exception {
        DailyPriceRangeResponse dailyRange = dailyRange();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();

        for (Target target : targets) {
            futures.add(pool.submit(() -> {
                start.await();
                long began = System.nanoTime();
                if (externalCallInsideTransaction) {
                    matchWithExternalCallInsideTransaction(target, dailyRange);
                } else {
                    matchWithExternalCallOutsideTransaction(target, dailyRange);
                }
                return (System.nanoTime() - began) / 1_000_000;
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
        assertAllFilled(targets);

        Collections.sort(latencies);
        return new Result(totalMs, latencies.get(latencies.size() / 2), latencies.get((int) (latencies.size() * 0.95)));
    }

    /** 커밋 45f18af 시점의 순서: 락을 먼저 잡고 트랜잭션 안에서 외부 응답을 기다린다. */
    private void matchWithExternalCallInsideTransaction(Target target, DailyPriceRangeResponse dailyRange) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            orderRepository.findByIdForUpdate(target.orderId());
            OrderBookResponse orderBook = fetchExternalOrderBook();
            matchingService.matchOrder(target.orderId(), orderBook, dailyRange);
        });
    }

    /** 현재 코드의 순서: 외부 응답을 먼저 받아둔 뒤 트랜잭션을 연다. */
    private void matchWithExternalCallOutsideTransaction(Target target, DailyPriceRangeResponse dailyRange) {
        OrderBookResponse orderBook = fetchExternalOrderBook();
        matchingService.matchOrder(target.orderId(), orderBook, dailyRange);
    }

    /** 외부 API 호출을 흉내 낸다. 네트워크를 타지 않으므로 지연 시간이 변동 없이 고정된다. */
    private OrderBookResponse fetchExternalOrderBook() {
        try {
            Thread.sleep(EXTERNAL_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return new OrderBookResponse(
            new OrderBookResult(
                OffsetDateTime.now(),
                "USD",
                List.of(new OrderBookLevel(ORDER_PRICE, ORDER_QUANTITY)),
                List.of(new OrderBookLevel(ORDER_PRICE, ORDER_QUANTITY))
            ),
            LocalDateTime.now()
        );
    }

    /** 체결가가 고가·저가 사이라 updateWithExecutionPrice()가 캐시를 건드리지 않고 즉시 반환한다. */
    private DailyPriceRangeResponse dailyRange() {
        return new DailyPriceRangeResponse(
            "BENCH",
            OffsetDateTime.now(),
            new BigDecimal("1000.0000"),
            new BigDecimal("1.0000"),
            "USD"
        );
    }

    /**
     * 회차마다 새 데이터를 만든다. 계좌·종목을 주문마다 따로 두어야 계좌 row 락이 병목이 되지 않고
     * 커넥션 풀의 영향만 남는다.
     */
    private List<Target> seed(String tag) {
        List<Target> targets = new ArrayList<>();
        for (int i = 0; i < ORDER_COUNT; i++) {
            String suffix = tag + "-" + i;
            User user = userRepository.save(User.builder()
                .email("bench-" + suffix + "@example.com")
                .passwordHash("bench")
                .nickname("bench-" + suffix)
                .status(Status.ACTIVE)
                .role(Role.USER)
                .build());
            Account account = accountRepository.save(Account.builder()
                .user(user)
                .accountNumber("ACC-" + suffix)
                .cashBalance(new BigDecimal("100000000.00"))
                .initialBalance(new BigDecimal("100000000.00"))
                .totalAssetValue(new BigDecimal("100000000.00"))
                .status(AccountStatus.ACTIVE)
                .build());
            Stock stock = stockRepository.save(Stock.builder()
                .symbol("B" + suffix)
                .name("bench " + suffix)
                .market(Market.NASDAQ)
                .securityType(SecurityType.STOCK)
                .isCommonShare(true)
                .status(StockStatus.ACTIVE)
                .currency("USD")
                .build());
            Order order = orderRepository.save(Order.create(
                account,
                stock,
                null,
                OrderSide.BUY,
                OrderType.LIMIT,
                ORDER_PRICE,
                ORDER_QUANTITY,
                ORDER_PRICE
            ));
            targets.add(new Target(order.getId(), stock.getSymbol()));
        }
        return targets;
    }

    /** 측정 구간에서 실제로 체결이 일어났는지 확인한다. 그러지 않으면 빈 루프를 잰 것이 된다. */
    private void assertAllFilled(List<Target> targets) {
        for (Target target : targets) {
            assertThat(orderRepository.findById(target.orderId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.FILLED);
        }
    }
}
