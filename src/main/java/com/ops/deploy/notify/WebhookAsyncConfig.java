package com.ops.deploy.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * webhook 告警专用线程池（不复用部署线程池 deployRunnerExecutor，避免告警 HTTP 占用部署线程）。
 * 核心/最大各 2、队列 100：告警频率低（仅失败/翻转事件），队列满时丢弃并记日志。
 *
 * <p>{@code @EnableAsync} 已由 {@code DeployAsyncConfig} 全局开启，本类仅提供 bean 定义。
 */
@Configuration
public class WebhookAsyncConfig {

    @Bean(name = "webhookExecutor")
    public ThreadPoolTaskExecutor webhookExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(100);
        ex.setThreadNamePrefix("webhook-notify-");
        ex.setRejectedExecutionHandler((r, executor) ->
                LoggerFactory.getLogger(WebhookAsyncConfig.class)
                        .warn("webhook executor queue full, drop notify task"));
        ex.initialize();
        return ex;
    }
}
