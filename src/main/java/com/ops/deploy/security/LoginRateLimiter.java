package com.ops.deploy.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录失败限流器（防爆破）：同一 (IP, 用户名) 连续失败达到阈值后，
 * 锁定一段时间内的全部登录尝试（无论对错密码），到期自动解锁。
 *
 * <p>实现要点：
 * <ul>
 *   <li>进程内内存计数（单实例部署）；重启后计数清零，可接受（爆破需重新累计）。</li>
 *   <li>key = "ip|username"，username 统一小写归一（避免大小写变体绕过计数）。</li>
 *   <li>后台定期清理（默认每 10 分钟）：移除锁定期已过且无失败计数、且空闲超过清理间隔的 key，
 *       防止内存无限增长。</li>
 *   <li>所有并发操作基于 {@link ConcurrentHashMap#compute} 对同一 key 串行化，无锁竞争问题。</li>
 * </ul>
 *
 * <p>使用位置：{@link AuthController#login} 入口处 {@link #checkLock}（被锁 → 403），
 * 凭证校验失败后 {@link #recordFailure}，成功后 {@link #recordSuccess}（清零计数）。
 */
@Component
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    /** 连续失败达到该次数即锁定。 */
    private final int maxFailures;
    /** 锁定时长。 */
    private final Duration lockDuration;
    /** 过期 key 清理间隔（同时用作空闲判定阈值）。 */
    private final Duration cleanInterval;

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    public LoginRateLimiter(
            @Value("${deploy.login.max-failures:5}") int maxFailures,
            @Value("${deploy.login.lock-duration:15m}") Duration lockDuration,
            @Value("${deploy.login.clean-delay-ms:600000}") long cleanDelayMs) {
        this.maxFailures = maxFailures;
        this.lockDuration = lockDuration;
        this.cleanInterval = Duration.ofMillis(cleanDelayMs);
    }

    /** 仅供单测构造（固定默认值：5 次 / 15 分钟锁定 / 10 分钟清理间隔）。 */
    public LoginRateLimiter() {
        this(5, Duration.ofMinutes(15), Duration.ofMinutes(10).toMillis());
    }

    /**
     * 检查 (ip, username) 是否处于锁定状态。
     *
     * @return 未锁定返回 null；已锁定返回剩余锁定秒数（&gt;0）
     */
    public Long checkLock(String ip, String username) {
        Attempt a = attempts.get(key(ip, username));
        if (a == null || a.lockUntil == 0) {
            return null;
        }
        long remaining = a.lockUntil - System.currentTimeMillis();
        if (remaining <= 0) {
            // 到期自动解锁（顺手清零，避免残留）
            attempts.computeIfPresent(key(ip, username), (k, v) -> {
                if (v.lockUntil > 0 && v.lockUntil <= System.currentTimeMillis()) {
                    v.failCount.set(0);
                    v.lockUntil = 0;
                }
                return v;
            });
            return null;
        }
        return (remaining + 999) / 1000;
    }

    /** 记录一次登录失败：计数 +1；达到阈值时锁定并清零计数（锁定期满后可重新累计）。 */
    public void recordFailure(String ip, String username) {
        attempts.compute(key(ip, username), (k, v) -> {
            Attempt a = (v == null) ? new Attempt() : v;
            a.lastActiveAt = System.currentTimeMillis();
            int n = a.failCount.incrementAndGet();
            if (n >= maxFailures) {
                a.lockUntil = a.lastActiveAt + lockDuration.toMillis();
                a.failCount.set(0);
                log.warn("login locked for {}s: ip={}, username={}",
                        lockDuration.toSeconds(), ip, username);
            }
            return a;
        });
    }

    /** 登录成功：清零该 (ip, username) 的失败计数（锁定状态不动，锁满自动解锁）。 */
    public void recordSuccess(String ip, String username) {
        attempts.computeIfPresent(key(ip, username), (k, v) -> {
            v.failCount.set(0);
            v.lastActiveAt = System.currentTimeMillis();
            return v;
        });
    }

    /** 定期清理过期 key（单实例部署假设）。 */
    @Scheduled(fixedDelayString = "${deploy.login.clean-delay-ms:600000}")
    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        long idleMs = cleanInterval.toMillis();
        Iterator<Map.Entry<String, Attempt>> it = attempts.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Attempt> e = it.next();
            Attempt a = e.getValue();
            boolean lockExpired = a.lockUntil > 0 && a.lockUntil <= now;
            boolean idle = a.lastActiveAt < now - idleMs;
            if ((lockExpired && a.failCount.get() == 0) || (a.lockUntil == 0 && idle)) {
                it.remove();
            }
        }
    }

    /** 测试观测：当前缓存的 key 数。 */
    public int size() {
        return attempts.size();
    }

    static String key(String ip, String username) {
        String u = username == null ? "" : username.trim().toLowerCase();
        return (ip == null ? "" : ip) + "|" + u;
    }

    private static final class Attempt {
        final AtomicInteger failCount = new AtomicInteger();
        volatile long lockUntil = 0;
        volatile long lastActiveAt = System.currentTimeMillis();
    }
}
