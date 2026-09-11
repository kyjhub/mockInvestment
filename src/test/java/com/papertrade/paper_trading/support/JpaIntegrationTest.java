package com.papertrade.paper_trading.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * JPA·SQL·제약·row lock을 검증하는 층.
 *
 * <p>{@code @DataJpaTest}는 기본적으로 DataSource를 embedded DB로 바꿔치기하므로
 * {@code replace = NONE}으로 막고 {@link IntegrationTestContainers}가 띄운 PostgreSQL을 그대로 쓴다.
 *
 * <p>Redis를 쓰는 경로(매칭 트리거, 심볼 락)는 이 slice에서 잘려 나간다. 서비스 경계까지 필요하면
 * {@link ApplicationIntegrationTest}를 쓴다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("integration")
public @interface JpaIntegrationTest {
}
