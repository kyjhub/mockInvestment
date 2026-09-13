package com.papertrade.paper_trading.benchmark;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 복식부기 쓰기 비용 측정 — 부하 테스트 후보 ④. */
@SpringBootTest
@ActiveProfiles("benchmark")
class LedgerWriteCostBenchmarkTest extends LedgerWriteCostBenchmarkSupport {

    @Test
    void compareLedgerWriteVariants() throws Exception {
        printComparisonTable();
        assertRowsWereWritten();
    }
}
