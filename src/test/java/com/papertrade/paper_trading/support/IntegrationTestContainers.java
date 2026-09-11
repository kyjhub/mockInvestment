package com.papertrade.paper_trading.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트가 공유하는 PostgreSQL·Redis 컨테이너.
 *
 * <p>H2나 embedded DB를 쓰지 않는 이유는 이 프로젝트가 검증해야 할 것이 대부분 PostgreSQL 고유
 * 동작이기 때문이다 — {@code PESSIMISTIC_WRITE}의 실제 차단, {@code @Check}·unique 제약의 위반 시점,
 * 데드락 감지. 운영과 같은 엔진이 아니면 "동시에 들어와도 예수금을 넘지 못한다" 같은 명제를 증명할 수 없다.
 *
 * <p>컨테이너는 {@code static} 초기화로 JVM당 한 번만 뜬다. Spring이 관리하는 bean으로 두면 test
 * context마다 뜨고 지므로 기동 비용이 곱절이 된다. 정리는 Testcontainers의 Ryuk가 JVM 종료 시 맡는다.
 *
 * <p>통합 테스트는 이 클래스를 상속하고 {@link JpaIntegrationTest} 또는
 * {@link ApplicationIntegrationTest}를 붙인다. {@code @DynamicPropertySource}는 상속 계층에서
 * 수집되므로 하위 클래스가 따로 선언하지 않아도 된다.
 */
public abstract class IntegrationTestContainers {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    private static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
