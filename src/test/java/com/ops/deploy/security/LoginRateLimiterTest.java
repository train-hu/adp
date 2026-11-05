package com.ops.deploy.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LoginRateLimiter 单测：阈值锁定 / 成功清零 / 到期解锁 / 隔离性 / 过期清理。
 * 纯逻辑，不依赖 Spring 容器。
 */
class LoginRateLimiterTest {

    private static final String IP = "10.1.1.1";

    @Test
    void checkLock_beforeThreshold_shouldReturnNull() {
        LoginRateLimiter limiter = new LoginRateLimiter(5, Duration.ofMinutes(15), 600_000L);
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure(IP, "alice");
            assertThat(limiter.checkLock(IP, "alice")).isNull();
        }
    }

    @Test
    void checkLock_afterThreshold_shouldReturnRemainingSeconds() {
        LoginRateLimiter limiter = new LoginRateLimiter(5, Duration.ofMinutes(15), 600_000L);
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure(IP, "alice");
        }
        Long remaining = limiter.checkLock(IP, "alice");
        assertThat(remaining).isNotNull();
        // 锁 15 分钟：剩余应略小于 900s、大于 800s
        assertThat(remaining).isLessThanOrEqualTo(900L);
        assertThat(remaining).isGreaterThan(800L);
    }

    @Test
    void recordSuccess_shouldResetFailureCount() {
        LoginRateLimiter limiter = new LoginRateLimiter(5, Duration.ofMinutes(15), 600_000L);
        // 先失败 4 次
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure(IP, "alice");
        }
        limiter.recordSuccess(IP, "alice");
        // 成功后再失败 4 次不应触发锁定（计数已清零）
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure(IP, "alice");
        }
        assertThat(limiter.checkLock(IP, "alice")).isNull();
    }

    @Test
    void isolation_byIpAndByUsername() {
        LoginRateLimiter limiter = new LoginRateLimiter(3, Duration.ofMinutes(15), 600_000L);
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure(IP, "alice");
        }
        // (IP, alice) 已锁，但其他组合不受影响
        assertThat(limiter.checkLock(IP, "alice")).isNotNull();
        assertThat(limiter.checkLock("10.2.2.2", "alice")).isNull();
        assertThat(limiter.checkLock(IP, "bob")).isNull();
    }

    @Test
    void usernameCaseInsensitive() {
        LoginRateLimiter limiter = new LoginRateLimiter(2, Duration.ofMinutes(15), 600_000L);
        limiter.recordFailure(IP, "Alice");
        limiter.recordFailure(IP, "alice");
        // 大小写变体归一到同一计数 → 第 2 次即锁
        assertThat(limiter.checkLock(IP, "ALICE")).isNotNull();
    }

    @Test
    void lock_shouldAutoExpireAfterDuration() throws InterruptedException {
        LoginRateLimiter limiter = new LoginRateLimiter(1, Duration.ofSeconds(1), 600_000L);
        limiter.recordFailure(IP, "alice");
        assertThat(limiter.checkLock(IP, "alice")).isNotNull();
        Thread.sleep(1_200);
        assertThat(limiter.checkLock(IP, "alice")).isNull();
        // 解锁后可重新累计失败
        limiter.recordFailure(IP, "alice");
        assertThat(limiter.checkLock(IP, "alice")).isNotNull();
    }

    @Test
    void cleanupExpired_shouldRemoveStaleEntries() {
        // 锁定时长 1s + 清理/空闲阈值 1s：两者过期后条目均可被清理
        LoginRateLimiter limiter = new LoginRateLimiter(1, Duration.ofSeconds(1), 1_000L);
        limiter.recordFailure(IP, "alice");
        limiter.recordFailure(IP, "bob");
        limiter.recordSuccess(IP, "bob"); // 有 key 但无失败、无锁定（成功清零）
        assertThat(limiter.size()).isEqualTo(2);
        try {
            Thread.sleep(1_200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        limiter.cleanupExpired();
        // alice：锁已到期且计数 0 → 移除；bob：空闲超过清理间隔 → 移除
        assertThat(limiter.size()).isZero();
    }

    @Test
    void cleanupExpired_shouldKeepActiveEntries() {
        LoginRateLimiter limiter = new LoginRateLimiter(5, Duration.ofMinutes(15), 600_000L);
        limiter.recordFailure(IP, "alice");
        limiter.cleanupExpired();
        assertThat(limiter.size()).isEqualTo(1);
    }
}
