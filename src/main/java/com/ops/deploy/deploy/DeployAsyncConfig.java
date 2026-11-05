package com.ops.deploy.deploy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 部署异步执行线程池配置（Task 6）。
 *
 * <p>{@code @EnableAsync} 使 {@link DeploymentRunner} 的 {@code @Async} 生效；
 * 专用 {@link ThreadPoolTaskExecutor}（bean 名 {@code deployRunnerExecutor}）按
 * {@code deploy.runner.*} 配置，默认 core=2 / max=4 / queue=20，线程名前缀
 * {@code deploy-runner-}，拒绝策略 {@link ThreadPoolExecutor.CallerRunsPolicy}
 * （队列满时回落到调用线程，避免部署任务被直接丢弃）。
 */
@Configuration
@EnableAsync
public class DeployAsyncConfig {

    @Bean(name = "deployRunnerExecutor")
    public AsyncTaskExecutor deployRunnerExecutor(
            @Value("${deploy.runner.core-pool-size:2}") int core,
            @Value("${deploy.runner.max-pool-size:4}") int max,
            @Value("${deploy.runner.queue-capacity:20}") int queueCapacity) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(core);
        ex.setMaxPoolSize(max);
        ex.setQueueCapacity(queueCapacity);
        ex.setThreadNamePrefix("deploy-runner-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        ex.initialize();
        return ex;
    }
}
