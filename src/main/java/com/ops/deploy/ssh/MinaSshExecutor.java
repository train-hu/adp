package com.ops.deploy.ssh;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Apache MINA SSHD 的 {@link SshExecutor} 实现。
 *
 * <p>核心设计：
 * <ul>
 *   <li><b>连接缓存</b>：以 {@code Long serverId} 为 key 缓存 {@link ClientConnection}，
 *       同一 server 的多次命令复用 TCP+认证开销；连接断开时（{@link ClientConnection#isOpen()}
 *       为 false）自动重建。缓存只存连接对象，不存凭证。
 *       另带<b>空闲回收</b>：每条缓存记录 lastUsed 时间戳，每次成功取用后更新；
 *       {@link #cleanupIdleConnections()} 定时任务（默认每 5 分钟）将空闲超过
 *       {@code deploy.ssh.connection-idle-timeout-minutes}（默认 30 分钟）的连接
 *       条件移除并关闭，避免空闲连接永久挂占远端会话；正在使用中的连接
 *       lastUsed 较新，不会被误回收。</li>
 *   <li><b>凭证安全</b>：本类及 {@link MinaConnectionFactory} 的日志只引用
 *       {@link SshTarget#toString()}（密码恒为 {@code ***}），绝不输出明文密码。</li>
 *   <li><b>重试</b>：仅 {@code BizException(SSH_ERROR)} 与超时结果（{@code timedOut=true}）
 *       触发指数退避重试（500ms/1000ms/...，单次上限 5000ms），其他业务错误立即上抛、
 *       不消耗重试预算。超时是断线最常见表现，按可重试失败处理。</li>
 *   <li><b>上传校验</b>：SFTP 传临时文件 → 本地 SHA-256 → 远端 {@code sha256sum} 对比
 *       → 一致 {@code mv -f} 为正式名；不一致 {@code rm -f} 清理并抛 SSH_ERROR。</li>
 * </ul>
 *
 * <p>线程安全：连接获取/建连合并进一次 {@link ConcurrentHashMap#compute}
 * （对同一 key 串行化），同一 serverId 的并发首次调用只创建一条连接，
 * 输掉竞态的落败方不产生未关闭连接泄漏。
 */
@Component
public class MinaSshExecutor implements SshExecutor, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MinaSshExecutor.class);

    /** 重试退避单次延迟上限（毫秒）。 */
    static final long MAX_BACKOFF_DELAY_MS = 5_000L;

    private final ConnectionFactory connectionFactory;
    private final Map<Long, CachedConnection> connections = new ConcurrentHashMap<>();

    /** 空闲回收阈值（毫秒）：lastUsed 超过该时长未被取用的缓存连接会被定时清理。 */
    private final long idleTimeoutMs;

    /** Spring 注入构造：空闲超时可经配置覆盖（清理间隔由 @Scheduled 的 fixedDelayString 控制）。 */
    @Autowired
    public MinaSshExecutor(ConnectionFactory connectionFactory,
                           @Value("${deploy.ssh.connection-idle-timeout-minutes:30}") long idleTimeoutMinutes) {
        this.connectionFactory = connectionFactory;
        this.idleTimeoutMs = idleTimeoutMinutes * 60_000L;
    }

    /** 便捷构造：空闲超时默认 30 分钟（单测 / 集成测试沿用单参用法）。 */
    public MinaSshExecutor(ConnectionFactory connectionFactory) {
        this(connectionFactory, 30L);
    }

    @PreDestroy
    public void shutdown() {
        closeAll();
    }

    // ---------- 连接缓存 ----------

    /**
     * 缓存条目：连接 + 最近一次成功取用的时间戳（毫秒）。
     * lastUsedAt 每次成功取用后刷新，空闲清理据此判断连接是否已空闲过久。
     */
    static final class CachedConnection {
        final ClientConnection conn;
        volatile long lastUsedAt;

        CachedConnection(ClientConnection conn, long lastUsedAt) {
            this.conn = conn;
            this.lastUsedAt = lastUsedAt;
        }

        /** 刷新最近取用时间（取用成功调用）。 */
        void touch() {
            lastUsedAt = System.currentTimeMillis();
        }
    }

    /**
     * 取/建某 server 的连接；已缓存但断开的连接会被重建，成功取用后刷新 lastUsed。
     *
     * <p>取/检查 isOpen/建连合并进一次 {@link ConcurrentHashMap#compute}：
     * 对同一 key 串行化，并发首次调用只会建连一次。若
     * {@code connectionFactory.create} 抛异常，compute 保持原值不变
     * （旧连接已 close 时下次调用会重建），行为可接受。
     */
    private CachedConnection connectionIfUsable(Long serverId, SshTarget target) {
        long now = System.currentTimeMillis();
        CachedConnection cached = connections.compute(serverId, (id, old) -> {
            if (old != null && old.conn.isOpen()) {
                old.touch();
                return old;
            }
            if (old != null) {
                log.debug("SSH connection for server {} dead, recreating", id);
                closeQuietly(old.conn);
            }
            log.debug("SSH connect for server {} ({}); target={}", id, id, target);
            return new CachedConnection(connectionFactory.create(target, id), now);
        });
        return cached;
    }

    /**
     * 空闲回收：清理 lastUsed 超过 {@code deploy.ssh.connection-idle-timeout-minutes}
     * （默认 30 分钟）的缓存连接。
     *
     * <p>用 {@link ConcurrentHashMap#remove(Object, Object)} 条件移除：
     * 只有 map 里仍是同一个缓存条目时才移除，不会误删并发新建的连接。
     * 正在使用中的连接每次取用都刷新 lastUsed，不会被本方法回收。
     */
    @Scheduled(fixedDelayString = "${deploy.ssh.idle-clean-delay-ms:300000}")
    public void cleanupIdleConnections() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Long, CachedConnection> entry : connections.entrySet()) {
            CachedConnection cached = entry.getValue();
            if (now - cached.lastUsedAt >= idleTimeoutMs) {
                // 条件移除：仅当条目未被并发替换时生效；移除成功后关闭连接
                if (connections.remove(entry.getKey(), cached)) {
                    log.debug("idle SSH connection for server {} reaped (idle {}ms > {}ms)",
                            entry.getKey(), now - cached.lastUsedAt, idleTimeoutMs);
                    closeQuietly(cached.conn);
                }
            }
        }
    }

    /** 关闭连接（幂等，忽略异常），供 compute 内重建与 close/closeAll 复用。 */
    private static void closeQuietly(ClientConnection c) {
        try {
            c.close();
        } catch (RuntimeException e) {
            log.debug("close SSH connection failed (ignored): {}", e.getMessage());
        }
    }

    // ---------- SshExecutor ----------

    @Override
    public CommandResult execute(Long serverId, SshTarget target, String command, int timeoutMs) {
        CachedConnection cached = connectionIfUsable(serverId, target);
        try {
            return cached.conn.execute(command, timeoutMs);
        } catch (BizException e) {
            if (e.getCode() == ErrorCode.SSH_ERROR) {
                // 连接级故障：丢弃缓存，下次调用重建
                if (connections.remove(serverId, cached)) {
                    closeQuietly(cached.conn);
                }
            }
            throw e;
        }
    }

    @Override
    public CommandResult executeWithRetry(Long serverId, SshTarget target, String command,
                                          int timeoutMs, int maxRetries) {
        // 超时（timedOut=true）与 SSH_ERROR 同等视为可重试失败；
        // 重试用尽后把最后一次的 timedOut 结果返回给调用方（状态机可感知超时）。
        CommandResult lastTimedOut = null;
        BizException lastSshError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                long delayMs = backoffDelayMs(attempt);
                log.debug("SSH retry {} for server {} after {}ms", attempt, serverId, delayMs);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new BizException(ErrorCode.SSH_ERROR, "interrupted during retry backoff");
                }
            }
            try {
                CommandResult result = execute(serverId, target, command, timeoutMs);
                if (!result.getTimedOut()) {
                    return result;
                }
                lastTimedOut = result;
                log.debug("SSH command timed out on server {} (attempt {}/{})",
                        serverId, attempt + 1, maxRetries + 1);
            } catch (BizException e) {
                if (e.getCode() != ErrorCode.SSH_ERROR) {
                    throw e; // 非 SSH 错误不重试
                }
                lastSshError = e;
            }
        }
        if (lastSshError != null) {
            throw lastSshError;
        }
        return lastTimedOut;
    }

    /**
     * 指数退避延迟：attempt 1→500ms、2→1000ms、3→2000ms...，
     * 单次上限 {@value #MAX_BACKOFF_DELAY_MS}ms（maxRetries 较大时不无限增长）。
     */
    static long backoffDelayMs(int attempt) {
        long delay = 500L * (1L << (attempt - 1));
        return Math.min(delay, MAX_BACKOFF_DELAY_MS);
    }

    @Override
    public boolean upload(Long serverId, SshTarget target, String remoteDir, String fileName, byte[] content) {
        String tempName = fileName + ".uploading";
        String tempPath = joinPath(remoteDir, tempName);
        String finalPath = joinPath(remoteDir, fileName);
        try {
            ClientConnection c = connectionIfUsable(serverId, target).conn;
            c.upload(remoteDir, tempName, content, DEFAULT_UPLOAD_CHUNK_SIZE);

            verifyRemoteAndRename(c, target, tempPath, finalPath, Sha256Util.hex(content));
            log.debug("upload OK: {} -> {} ({} bytes, sha256 {})",
                    target, tempPath, finalPath, content.length, Sha256Util.hex(content));
            return true;
        } catch (BizException e) {
            // 清理临时文件（失败也尽力清理，忽略二次错误）
            cleanupTempFile(serverId, target, tempPath);
            throw e;
        }
    }

    @Override
    public boolean upload(Long serverId, SshTarget target, String remoteDir, String fileName,
                          java.io.InputStream in, long sizeBytes, String expectedSha256) {
        String tempName = fileName + ".uploading";
        String tempPath = joinPath(remoteDir, tempName);
        String finalPath = joinPath(remoteDir, fileName);
        try {
            ClientConnection c = connectionIfUsable(serverId, target).conn;
            c.upload(remoteDir, tempName, in, sizeBytes, DEFAULT_UPLOAD_CHUNK_SIZE);
            verifyRemoteAndRename(c, target, tempPath, finalPath, expectedSha256);
            log.debug("stream upload OK: {} -> {} ({} bytes, sha256 {})",
                    target, tempPath, finalPath, sizeBytes, expectedSha256);
            return true;
        } catch (BizException e) {
            cleanupTempFile(serverId, target, tempPath);
            throw e;
        }
    }

    @Override
    public boolean uploadStream(Long serverId, SshTarget target, String remoteDir, String fileName,
                                java.io.InputStream content, long declaredSize) {
        String tempName = fileName + ".uploading";
        String tempPath = joinPath(remoteDir, tempName);
        String finalPath = joinPath(remoteDir, fileName);
        try {
            ClientConnection c = connectionIfUsable(serverId, target).conn;
            // 边传边算：流只读一遍，本地摘要由连接层在 SFTP 写入过程中计算并回传
            String expected = c.uploadStream(remoteDir, tempName, content, declaredSize,
                    DEFAULT_UPLOAD_CHUNK_SIZE);
            verifyRemoteAndRename(c, target, tempPath, finalPath, expected);
            log.debug("stream upload OK: {} -> {} ({} bytes, sha256 {})",
                    target, tempPath, finalPath, declaredSize, expected);
            return true;
        } catch (BizException e) {
            // 清理临时文件（失败也尽力清理，忽略二次错误）
            cleanupTempFile(serverId, target, tempPath);
            throw e;
        }
    }

    @Override
    public ResourceProfile probe(Long serverId, SshTarget target) {
        CommandResult r = execute(serverId, target, PROBE_COMMAND, 30_000);
        String out = r.getTimedOut() ? "" : r.getStdout();
        ResourceProfile profile = ResourceParser.parse(out);
        log.debug("probe {} -> {}", target, profile);
        return profile;
    }

    @Override
    public String launchBackground(Long serverId, SshTarget target, String workdir,
                                   String command, String taskName) {
        String statusDir = joinPath(workdir, ".deploy");
        String statusFile = joinPath(statusDir, taskName + ".status");
        String logFile = joinPath(statusDir, taskName + ".log");
        String cmd = "mkdir -p " + shellQuote(statusDir) + " && "
                + BackgroundTaskCommands.buildNohupCommand(workdir, command, logFile, statusFile);
        CommandResult r = execute(serverId, target, cmd, 30_000);
        if (r.getTimedOut() || r.getExitCode() != 0) {
            throw new BizException(ErrorCode.SSH_ERROR,
                    "launch background task " + taskName + " on " + target + " failed: " + r.getStderr());
        }
        log.debug("launched background task {} on {} (status file: {})", taskName, target, statusFile);
        return statusFile;
    }

    @Override
    public BackgroundStatus pollBackground(Long serverId, SshTarget target, String statusFileHandle) {
        CommandResult r = execute(serverId, target, "cat " + shellQuote(statusFileHandle), 15_000);
        if (r.getTimedOut()) {
            return BackgroundStatus.of(BackgroundStatus.UNKNOWN, null);
        }
        // 文件尚未写出（exitCode != 0）也按 UNKNOWN 处理，轮询方继续等待
        return BackgroundTaskCommands.parseStatus(r.getStdout());
    }

    @Override
    public void close(Long serverId) {
        CachedConnection cached = connections.remove(serverId);
        if (cached != null) {
            closeQuietly(cached.conn);
        }
    }

    @Override
    public void closeAll() {
        for (CachedConnection cached : connections.values()) {
            closeQuietly(cached.conn);
        }
        connections.clear();
    }

    @Override
    public StreamHandle stream(Long serverId, SshTarget target, String command,
                               java.util.function.Consumer<String> lineConsumer,
                               java.util.function.Consumer<Integer> onComplete) {
        CachedConnection cached = connectionIfUsable(serverId, target);
        // 流式同样要剔除坏连接（缺陷11）：半死连接的 isOpen()==true 不触发自动重建，
        // 若 stream 失败不剔除，SSE 等流式端点会反复打到同一条坏连接、永久 500 无法自愈
        // （execute 路径有等价剔除，见 execute 方法）。
        try {
            return cached.conn.stream(command, lineConsumer, onComplete);
        } catch (BizException e) {
            if (e.getCode() == ErrorCode.SSH_ERROR) {
                if (connections.remove(serverId, cached)) {
                    closeQuietly(cached.conn);
                }
            }
            throw e;
        }
    }

    @Override
    public void close() {
        closeAll();
    }

    // ---------- 内部工具 ----------

    /**
     * 上传后公共校验：远端 sha256sum 与期望摘要对比，一致则 mv 为正式名；
     * 不一致/失败抛 {@code BizException(SSH_ERROR)}（调用方负责清理临时文件）。
     */
    private void verifyRemoteAndRename(ClientConnection c, SshTarget target, String tempPath,
                                       String finalPath, String expectedSha256) {
        CommandResult sum = c.execute("sha256sum " + tempPath, DEFAULT_TIMEOUT_MS);
        if (sum.getTimedOut() || sum.getExitCode() != 0) {
            throw new BizException(ErrorCode.SSH_ERROR,
                    "sha256sum of temp file failed on " + target + ": " + sum.getStderr());
        }
        String actual = Sha256Util.extractFromSha256sumOutput(sum.getStdout());
        if (!Sha256Util.equalsHex(expectedSha256, actual)) {
            throw new BizException(ErrorCode.SSH_ERROR,
                    "SHA-256 mismatch for " + tempPath + " on " + target
                            + " (expected " + expectedSha256 + ", actual " + actual + ")");
        }
        CommandResult mv = c.execute("mv -f " + tempPath + " " + finalPath, DEFAULT_TIMEOUT_MS);
        if (mv.getTimedOut() || mv.getExitCode() != 0) {
            throw new BizException(ErrorCode.SSH_ERROR,
                    "rename temp file to " + finalPath + " failed on " + target + ": " + mv.getStderr());
        }
    }

    /** 尽力清理远端临时文件（忽略二次错误）。 */
    private void cleanupTempFile(Long serverId, SshTarget target, String tempPath) {
        try {
            execute(serverId, target, "rm -f " + tempPath, 10_000);
        } catch (RuntimeException ignore) {
            log.debug("cleanup temp file {} on {} failed (ignored)", tempPath, target);
        }
    }

    private static String joinPath(String dir, String name) {
        if (dir == null || dir.isEmpty() || "/".equals(dir)) {
            return "/" + name;
        }
        return dir + "/" + name;
    }

    /** shell 单引号转义（与 {@link BackgroundTaskCommands#shellQuote} 行为一致，供包内复用）。 */
    static String shellQuote(String s) {
        return BackgroundTaskCommands.shellQuote(s);
    }

    /**
     * 只读资源探测命令（单条 exec，输出带前缀行，由 {@link ResourceParser} 解析）：
     * CPU 核数 / 1 分钟负载 / 内存 / 磁盘 / 监听端口 / 5 种运行时版本。
     */
    static final String PROBE_COMMAND = String.join(" ; ",
            "echo CPU=$(nproc 2>/dev/null || echo 0)",
            "echo LOAD=$(cut -d' ' -f1 /proc/loadavg 2>/dev/null || echo '')",
            "echo MEM=$(grep MemTotal /proc/meminfo 2>/dev/null | head -1)",
            "echo MEM=$(grep MemAvailable /proc/meminfo 2>/dev/null | head -1)",
            "echo DISK=$(df -kP / 2>/dev/null | tail -1)",
            "echo PORTS=$((ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null) | awk 'NR>1 {print $4}' | grep -oE '[0-9]+$' | head -20)",
            "for rt in java python3 node docker kubectl; do"
                    + " case $rt in java) echo JAVA=$(java -version 2>&1 | head -1 || echo absent) ;;",
                    "python3) echo PYTHON3=$(python3 --version 2>&1 | head -1 || echo absent) ;;",
                    "node) echo NODE=$(node --version 2>&1 | head -1 || echo absent) ;;",
                    "docker) echo DOCKER=$(docker --version 2>&1 | head -1 || echo absent) ;;",
                    "kubectl) echo KUBECTL=$(kubectl version --client 2>&1 | head -1 || echo absent) ;;",
                    "esac; done"
    );
}
