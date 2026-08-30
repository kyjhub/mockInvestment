package com.papertrade.paper_trading.benchmark;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 커넥션 풀만 25로 키운 대조군.
 *
 * <p>INSIDE 변형의 한계선이 풀 크기를 따라 움직이면, 병목의 원인이 커넥션 풀이라는 것이 확정된다.
 * 스레드 수를 아무리 올려도 한계선이 그대로라면 원인은 다른 곳에 있다는 뜻이다.
 */
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=25")
@ActiveProfiles("benchmark")
class LargerPoolTransactionBoundaryBenchmarkTest extends TransactionBoundaryBenchmarkSupport {

    @Test
    void compareTransactionBoundaries() throws Exception {
        printComparisonTable();
    }
}
