package com.papertrade.paper_trading.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 기본 스케줄러 풀 하나에 모든 @Scheduled를 태우면, 오래 걸리는 작업이 지연에 민감한 작업을 막는다.
 * 호가 REST 폴링 한 번이 수 초 걸리면 그동안 WebSocket 구독 재선언(250ms)과 스트림 소비(100ms)가 함께 멈춘다.
 * 그래서 블로킹 I/O와 매칭 작업은 전용 풀로 분리한다.
 */
@Configuration
public class SchedulingConfig {

    public static final String MARKET_DATA_POLLING_SCHEDULER = "marketDataPollingScheduler";
    public static final String DIRTY_DRAIN_SCHEDULER = "dirtyDrainScheduler";
    public static final String WEBSOCKET_SCHEDULER = "webSocketScheduler";
    public static final String VALUATION_SCHEDULER = "valuationScheduler";

    /**
     * {@link org.springframework.scheduling.config.TaskSchedulerRouter}가 스케줄러를 찾을 때 쓰는 기본 빈 이름.
     * 이 이름의 빈이 없으면 단일 스레드 로컬 실행기로 폴백한다.
     */
    private static final String DEFAULT_TASK_SCHEDULER = "taskScheduler";

    @Value("${spring.task.scheduling.pool.size:4}")
    private int defaultPoolSize;

    @Value("${market-data.polling.scheduler.pool-size:3}")
    private int marketDataPollingPoolSize;

    @Value("${matching-engine.dirty-drain.pool-size:2}")
    private int dirtyDrainPoolSize;

    @Value("${valuation.scheduler.pool-size:1}")
    private int valuationPoolSize;

    /**
     * 기본 스케줄러를 직접 정의한다.
     *
     * <p>아래 전용 스케줄러 빈이 존재하는 순간 Spring Boot의 {@code TaskSchedulingAutoConfiguration}이
     * {@code @ConditionalOnMissingBean(TaskScheduler.class)} 때문에 물러난다. 그러면 {@code TaskScheduler}
     * 빈이 여러 개라 타입 조회가 모호해지고, {@code taskScheduler} 이름의 빈도 없어서
     * <b>단일 스레드로 폴백한다</b> — 풀을 나눈 의미가 사라진다.
     */
    @Bean(DEFAULT_TASK_SCHEDULER)
    public TaskScheduler taskScheduler() {
        return threadPoolTaskScheduler(defaultPoolSize, "scheduled-");
    }

    /** 외부 API 호출로 수 초씩 걸릴 수 있는 시세 폴링 전용. */
    @Bean(MARKET_DATA_POLLING_SCHEDULER)
    public TaskScheduler marketDataPollingScheduler() {
        return threadPoolTaskScheduler(marketDataPollingPoolSize, "market-poll-");
    }

    /**
     * 매칭을 수행하므로 DB 커넥션을 점유한다.
     *
     * <p>드레인은 {@code fixedDelay} 작업 하나뿐이고 같은 작업은 자기 자신과 겹치지 않으므로,
     * 풀을 키워도 한 인스턴스에서 드레인이 동시에 두 개 돌지는 <b>않는다</b>. 이 풀의 목적은
     * 병렬 처리가 아니라 <b>다른 스케줄 작업과의 격리</b>이며, 그래서 1이면 충분하다.
     * 실제 병렬 매칭이 필요해지면 드레인이 SPOP만 하고 종목별 작업을 별도 executor에 넘기는 구조로 바꿔야 한다.
     */
    @Bean(DIRTY_DRAIN_SCHEDULER)
    public TaskScheduler dirtyDrainScheduler() {
        return threadPoolTaskScheduler(dirtyDrainPoolSize, "dirty-drain-");
    }

    /**
     * WebSocket 슬롯 관리와 구독 갱신 전용.
     *
     * <p>{@code TossOrderBookWebSocketManager}는 연결 생성·해제와 구독 선언이 직렬로 일어난다고 보고 짜여 있다.
     * 기본 풀(4스레드)에 두면 {@code manageSlots()}와 {@code refreshSubscriptions()}가 동시에 돌아서,
     * 슬롯을 잃어 연결을 닫는 도중 다른 스레드가 같은 연결에 접속을 시작하는 경합이 생긴다.
     * 그래서 <b>반드시 단일 스레드</b>여야 한다.
     */
    @Bean(WEBSOCKET_SCHEDULER)
    public TaskScheduler webSocketScheduler() {
        return threadPoolTaskScheduler(1, "toss-ws-");
    }

    /**
     * 평가는 계좌 수에 비례하는 작업이라 기본 풀에 두면 다른 주기 작업을 밀어낸다.
     * 병렬 처리가 목적이 아니라 격리가 목적이므로 1이면 충분하다.
     */
    @Bean(VALUATION_SCHEDULER)
    public TaskScheduler valuationScheduler() {
        return threadPoolTaskScheduler(valuationPoolSize, "valuation-");
    }

    private TaskScheduler threadPoolTaskScheduler(int poolSize, String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
