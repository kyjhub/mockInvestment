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
     * 풀 크기는 HikariCP 최대 커넥션 수를 넘지 않도록 커넥션 풀 설정과 함께 조정해야 한다.
     */
    @Bean(DIRTY_DRAIN_SCHEDULER)
    public TaskScheduler dirtyDrainScheduler() {
        return threadPoolTaskScheduler(dirtyDrainPoolSize, "dirty-drain-");
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
