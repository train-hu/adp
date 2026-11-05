package com.ops.deploy.deploy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SSE 日志推送执行器：缓存线程池（daemon 线程），
 * 供 {@link ServiceInstanceController#logs} 异步推送 journalctl -f 实时日志。
 */
@Configuration
public class LogSseConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService logSseExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "log-sse");
            t.setDaemon(true);
            return t;
        });
    }
}
