package com.papertrade.paper_trading.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 서비스 경계까지 올라가는 층. 주문 접수 검증, 동시 접수 경합, 체결 transaction이 대상이다.
 *
 * <p>context 전체가 뜨므로 Redis도 필요하다 — {@code MatchingEngineStreamConsumer}가
 * {@code @PostConstruct}에서 consumer group을 만든다.
 *
 * <p>{@code integration} profile이 스케줄 작업 주기를 사실상 무한대로 두어 background 작업이
 * 테스트에 끼어들지 않게 한다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@ActiveProfiles("integration")
public @interface ApplicationIntegrationTest {
}
