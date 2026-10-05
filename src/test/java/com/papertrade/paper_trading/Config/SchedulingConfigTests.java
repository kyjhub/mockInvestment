package com.papertrade.paper_trading.Config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * 벤치마크 프로파일은 {@code scheduling.enabled=false}로 스케줄 작업을 전부 끈다.
 *
 * <p>주기를 늘리는 방식으로는 기동 직후의 첫 실행을 막지 못했다. 그래서 작업 등록 자체가 일어나지 않는지를 본다.
 */
class SchedulingConfigTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(SchedulingConfig.class, ScheduledJob.class);

    @Test
    void registersScheduledJobsByDefault() {
        contextRunner.run(context ->
            assertThat(scheduledTaskCount(context.getBean(ScheduledTaskHolder.class))).isEqualTo(1));
    }

    @Test
    void registersNoScheduledJobWhenDisabled() {
        contextRunner.withPropertyValues("scheduling.enabled=false").run(context ->
            assertThat(context.getBeansOfType(ScheduledTaskHolder.class)).isEmpty());
    }

    @Test
    void keepsTheSchedulerPoolsWhenDisabled() {
        // 작업만 등록되지 않을 뿐, 풀 빈을 주입받는 코드가 생겨도 깨지지 않아야 한다.
        contextRunner.withPropertyValues("scheduling.enabled=false").run(context ->
            assertThat(context.getBeansOfType(TaskScheduler.class)).containsKeys(
                "taskScheduler",
                SchedulingConfig.MARKET_DATA_POLLING_SCHEDULER,
                SchedulingConfig.DIRTY_DRAIN_SCHEDULER,
                SchedulingConfig.WEBSOCKET_SCHEDULER,
                SchedulingConfig.VALUATION_SCHEDULER));
    }

    private int scheduledTaskCount(ScheduledTaskHolder holder) {
        return holder.getScheduledTasks().size();
    }

    @Configuration(proxyBeanMethods = false)
    static class ScheduledJob {

        @Bean
        Job job() {
            return new Job();
        }
    }

    static class Job {

        @Scheduled(fixedDelay = 3_600_000)
        void run() {
        }
    }
}
