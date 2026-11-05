package com.ops.deploy.deploy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DeploymentMetrics 单测：终态计数与耗时 Timer 的 tag/数值正确性。
 */
class DeploymentMetricsTest {

    @Test
    void record_shouldIncrementCounterWithTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DeploymentMetrics metrics = new DeploymentMetrics(registry);

        metrics.record("SUCCESS", Duration.ofSeconds(30));
        metrics.record("SUCCESS", Duration.ofSeconds(40));
        metrics.record("FAILED", Duration.ofSeconds(5));

        assertThat(registry.get("deployment_terminal_total")
                .tags("status", "SUCCESS").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("deployment_terminal_total")
                .tags("status", "FAILED").counter().count()).isEqualTo(1.0);
    }

    @Test
    void record_shouldRecordDurationInTimer() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DeploymentMetrics metrics = new DeploymentMetrics(registry);

        metrics.record("SUCCESS", Duration.ofSeconds(30));

        assertThat(registry.get("deployment_duration")
                .tags("status", "SUCCESS").timer().count()).isEqualTo(1);
        assertThat(registry.get("deployment_duration")
                .tags("status", "SUCCESS").timer()
                .totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(30);
    }

    @Test
    void record_nullStatusOrDuration_shouldNotThrow() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DeploymentMetrics metrics = new DeploymentMetrics(registry);

        metrics.record(null, Duration.ofSeconds(1));
        metrics.record("  ", null);
        metrics.record("FAILED", null); // 无耗时的终态也允许（仅计数）

        assertThat(registry.get("deployment_terminal_total")
                .tags("status", "FAILED").counter().count()).isEqualTo(1.0);
    }
}
