package com.ops.deploy.deploy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 部署业务指标（Prometheus 采集）：
 * <ul>
 *   <li>{@code deployment_terminal_total}：终态计数 Counter（tag: status=SUCCESS/FAILED/...）；</li>
 *   <li>{@code deployment_duration}：部署耗时 Timer（tag: status），单位秒。</li>
 * </ul>
 *
 * <p>JVM / HTTP 请求等基础指标由 Micrometer 自动采集（actuator + micrometer-registry-prometheus），
 * 此处只补平台核心业务指标。在部署进入终态时调用 {@link #record}。
 */
@Component
public class DeploymentMetrics {

    private final MeterRegistry registry;
    /** 按 status 维度缓存 meter：同名同 tag 的 builder 在 registry 内去重，map 仅作句柄复用。 */
    private final Map<String, Counter> terminalCounters = new ConcurrentHashMap<>();
    private final Map<String, Timer> durationTimers = new ConcurrentHashMap<>();

    public DeploymentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 记录一次部署终态：终态计数 +1，同时把耗时计入 Timer。 */
    public void record(String status, Duration duration) {
        if (status == null || status.isBlank()) {
            return;
        }
        terminalCounters.computeIfAbsent(status, s ->
                        Counter.builder("deployment_terminal_total")
                                .description("部署进入终态的累计次数")
                                .tag("status", s)
                                .register(registry))
                .increment();
        if (duration != null) {
            // 部署耗时通常分钟级，桶从 5s 到 30min
            durationTimers.computeIfAbsent(status, s ->
                            Timer.builder("deployment_duration")
                                    .description("部署任务从开始到终态的耗时")
                                    .tag("status", s)
                                    .publishPercentiles(0.5, 0.95)
                                    .maximumExpectedValue(Duration.ofMinutes(30))
                                    .minimumExpectedValue(Duration.ofSeconds(5))
                                    .register(registry))
                    .record(Math.max(duration.toNanos(), 0), TimeUnit.NANOSECONDS);
        }
    }
}
