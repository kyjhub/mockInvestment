package com.papertrade.paper_trading.benchmark;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 기본 커넥션 풀(HikariCP 기본값 10)에서의 측정. */
@SpringBootTest
@ActiveProfiles("benchmark")
class DefaultPoolTransactionBoundaryBenchmarkTest extends TransactionBoundaryBenchmarkSupport {

    @Test
    void compareTransactionBoundaries() throws Exception {
        printComparisonTable();
    }
}
