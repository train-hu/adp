package com.ops.deploy.ssh;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import jakarta.annotation.PreDestroy;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.auth.password.PasswordIdentityProvider;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.session.SessionHeartbeatController.HeartbeatType;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Apache MINA SSHD 的 {@link SshExecutor.ConnectionFactory}：
 * 真实 TCP 连接 + 密码认证 + exec 通道执行 + SFTP 分块上传。
 *
 * <p>安全要点：
 * <ul>
 *   <li>M1 采用 {@link AcceptAllServerKeyVerifier}（内网可信环境，简化首连 UX）；
 *       生产化时应替换为固定指纹/CA 校验，防止中间人攻击。</li>
 *   <li>日志只引用 {@link SshTarget#toString()}（密码打码为 ***），绝不输出明文。</li>
 * </ul>
 *
 * <p>进程内共享一个 {@link SshClient}（线程安全），按 (host, user, port) 建立会话。
 */
@Component
public class MinaConnectionFactory implements SshExecutor.ConnectionFactory {

    private static final Logger log = LoggerFactory.getLogger(MinaConnectionFactory.class);

    /** 连接 + 认证总等待时间（毫秒）。 */
    private static final long AUTH_WAIT_MS = 10_000L;

    /**
     * 补等 EOF（远端 stdout 关闭）的等待时间（毫秒）。
     * {@link #execute(String, int)} 在收到 EXIT_STATUS 后仍需等流刷完，否则读到空/不完整输出。
     */
    private static final long EOF_WAIT_MS = 5_000L;

    private final SshClient client;

    public MinaConnectionFactory() {
        this.client = SshClient.setUpDefaultClient();
        this.client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        this.client.start();
    }

    @PreDestroy
    public void shutdown() {
        try {
            client.stop();
        } catch (RuntimeException e) {
            log.debug("stop SshClient failed (ignored): {}", e.getMessage());
        }
    }

    @Override
    public SshExecutor.ClientConnection create(SshTarget target, Long serverId) {
        ClientSession session;
        try {
            ConnectFuture cf = client.connect(target.getUsername(), target.getHost(), target.getPort(), null, null);
            cf.verify(AUTH_WAIT_MS);
            session = cf.getSession();
            if (session == null) {
                throw new BizException(ErrorCode.SSH_ERROR, "connect to " + target + " returned no session");
            }
            session.setPasswordIdentityProvider(PasswordIdentityProvider.wrapPasswords(target.getPassword()));
            AuthFuture af = session.auth();
            af.verify(AUTH_WAIT_MS);
            if (!af.isSuccess()) {
                try {
                    session.disconnect(0, "auth failed");
                } catch (IOException ignore) {
                    // 已失败，忽略断开异常
                }
                throw new BizException(ErrorCode.SSH_ERROR, "auth failed for " + target);
            }
            // 会话心跳：每 30s 发送 global request（无回复要求），探测半死连接
            // （"TCP 活着但命令挂死"的状态），超时后由 executeWithRetry 重试兜底
            session.setSessionHeartbeat(HeartbeatType.IGNORE, Duration.ofSeconds(30));
            log.debug("SSH connected server {} ({}); target={}", serverId, serverId, target);
            return new MinaConnection(session, target);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.debug("SSH connect to {} failed: {}", target, e.toString());
            throw new BizException(ErrorCode.SSH_ERROR, "connect to " + target + " failed: " + e.getMessage());
        }
    }

    /** 单条 SSH 会话的线程安全封装：exec 通道执行 + SFTP 上传。 */
    private static final class MinaConnection implements SshExecutor.ClientConnection {

        private final ClientSession session;
        private final SshTarget target;
        private final AtomicBoolean closed = new AtomicBoolean();

        MinaConnection(ClientSession session, SshTarget target) {
            this.session = session;
            this.target = target;
        }

        @Override
        public CommandResult execute(String command, int timeoutMs) {
            long startNanos = System.nanoTime();
            ClientChannel channel = null;
            try {
                channel = session.createExecChannel(command);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ByteArrayOutputStream err = new ByteArrayOutputStream();
                channel.setOut(out);
                channel.setErr(err);
                channel.open().verify(AUTH_WAIT_MS);
                Set<ClientChannelEvent> events =
                        channel.waitFor(Set.of(ClientChannelEvent.EXIT_STATUS,
                                ClientChannelEvent.CLOSED, ClientChannelEvent.TIMEOUT), timeoutMs);
                if (events.contains(ClientChannelEvent.TIMEOUT)
                        && !events.contains(ClientChannelEvent.EXIT_STATUS)) {
                    return CommandResult.timeout((System.nanoTime() - startNanos) / 1_000_000L);
                }
                // 两阶段等待（竞态修复）：waitFor 是"任一事件先发生即返回"语义，
                // EXIT_STATUS 常先于远端 stdout 关闭（EOF）到达就返回，此时 MINA 工作线程
                // 可能尚未把 stdout DATA 刷入 out 流，直接读取会得到空/不完整输出
                // （E2E 实测 sha256sum 校验 actual=null 即源于此）。补等 EOF 确保流刷完再读。
                channel.waitFor(Set.of(ClientChannelEvent.EOF), EOF_WAIT_MS);
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
                String stdout = new String(out.toByteArray(), StandardCharsets.UTF_8);
                String stderr = new String(err.toByteArray(), StandardCharsets.UTF_8);
                Integer exit = channel.getExitStatus();
                if (exit == null) {
                    // 通道被关闭但无退出状态（远端异常终止）
                    throw new BizException(ErrorCode.SSH_ERROR,
                            "no exit status for command on " + target + ": " + stderr);
                }
                return CommandResult.ok(stdout, exit.intValue(), elapsedMs);
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                log.debug("exec on {} failed: {}", target, e.toString());
                throw new BizException(ErrorCode.SSH_ERROR,
                        "exec failed on " + target + ": " + e.getMessage());
            } finally {
                if (channel != null) {
                    try {
                        channel.close();
                    } catch (Exception ignore) {
                        // 通道已关闭，忽略
                    }
                }
            }
        }

        @Override
        public SshExecutor.StreamHandle stream(String command, java.util.function.Consumer<String> lineConsumer,
                                               java.util.function.Consumer<Integer> onComplete) {
            ClientChannel channel;
            try {
                channel = session.createExecChannel(command);
            } catch (Exception e) {
                log.debug("open exec channel on {} failed: {}", target, e.toString());
                throw new BizException(ErrorCode.SSH_ERROR,
                        "open exec channel on " + target + ": " + e.getMessage());
            }
            StreamLineSplitter splitter = new StreamLineSplitter(lineConsumer);
            try {
                channel.setOut(new OutputStream() {
                    @Override
                    public void write(int b) {
                        splitter.write(b);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) {
                        splitter.write(b, off, len);
                    }
                });
                // stderr 丢弃：无限输出场景只关心 stdout（journalctl -f）
                channel.setErr(DROP_STREAM);
                channel.open().verify(10_000);
            } catch (Exception e) {
                closeQuietly(channel);
                log.debug("stream exec on {} failed: {}", target, e.toString());
                throw new BizException(ErrorCode.SSH_ERROR,
                        "open exec channel on " + target + ": " + e.getMessage());
            }

            AtomicBoolean cancelled = new AtomicBoolean();
            Thread reader = new Thread(() -> {
                Integer exit = null;
                try {
                    while (!cancelled.get() && channel.isOpen()) {
                        channel.waitFor(Set.of(ClientChannelEvent.CLOSED), 1_000);
                    }
                    if (!cancelled.get()) {
                        splitter.flush();
                        exit = channel.getExitStatus(); // 可能为 null
                    }
                } catch (Exception e) {
                    log.debug("stream reader on {} failed: {}", target, e.toString());
                } finally {
                    closeQuietly(channel);
                    try {
                        onComplete.accept(exit);
                    } catch (RuntimeException ignore) {
                        // 回调异常绝不逸出读线程
                    }
                }
            }, "ssh-stream-" + target.getHost());
            reader.setDaemon(true);
            reader.start();

            return () -> {
                if (cancelled.compareAndSet(false, true)) {
                    closeQuietly(channel);
                }
            };
        }

        @Override
        public void upload(String remoteDir, String fileName, byte[] content, int chunkSize) {
            String path = normalize(remoteDir) + "/" + fileName;
            try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                ensureDirectory(sftp, normalize(remoteDir));
                try (SftpClient.CloseableHandle handle =
                             sftp.open(path, SftpClient.OpenMode.Create,
                                     SftpClient.OpenMode.Truncate, SftpClient.OpenMode.Write)) {
                    for (int off = 0; off < content.length; off += chunkSize) {
                        int len = Math.min(chunkSize, content.length - off);
                        sftp.write(handle, off, content, off, len);
                    }
                }
            } catch (Exception e) {
                log.debug("SFTP upload to {} on {} failed: {}", path, target, e.toString());
                throw new BizException(ErrorCode.SSH_ERROR,
                        "SFTP upload to " + path + " on " + target + " failed: " + e.getMessage());
            }
        }

        @Override
        public String uploadStream(String remoteDir, String fileName, InputStream content,
                                   long declaredSize, int chunkSize) {
            String path = normalize(remoteDir) + "/" + fileName;
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                // SHA-256 为 JDK 必备算法，不可能缺失
                throw new IllegalStateException("SHA-256 not available", e);
            }
            writeStreamToSftp(path, remoteDir, content, chunkSize, (buf, len) -> digest.update(buf, 0, len));
            return Sha256Util.hex(digest.digest());
        }

        @Override
        public void upload(String remoteDir, String fileName, InputStream in, long sizeBytes, int chunkSize) {
            String path = normalize(remoteDir) + "/" + fileName;
            // 纯传输（不算摘要），校验由 executor 用远端 sha256sum 完成
            writeStreamToSftp(path, remoteDir, in, chunkSize, null);
        }

        /**
         * 流式 SFTP 写入公共实现：逐 chunk 从 in 读到 -1 为止，边读边写；
         * onChunk 非空时（如摘要更新）在每次写入前回调。流由调用方负责关闭。
         */
        private void writeStreamToSftp(String path, String remoteDir, InputStream content,
                                       int chunkSize, java.util.function.BiConsumer<byte[], Integer> onChunk) {
            try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session);
                 InputStream in = content) {
                ensureDirectory(sftp, normalize(remoteDir));
                try (SftpClient.CloseableHandle handle =
                             sftp.open(path, SftpClient.OpenMode.Create,
                                     SftpClient.OpenMode.Truncate, SftpClient.OpenMode.Write)) {
                    byte[] buf = new byte[chunkSize];
                    long off = 0;
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        if (onChunk != null) {
                            onChunk.accept(buf, len);
                        }
                        sftp.write(handle, off, buf, 0, len);
                        off += len;
                    }
                }
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                log.debug("SFTP stream upload to {} on {} failed: {}", path, target, e.toString());
                throw new BizException(ErrorCode.SSH_ERROR,
                        "SFTP stream upload to " + path + " on " + target + " failed: " + e.getMessage());
            }
        }

        /** 逐级创建 remoteDir（已存在的目录跳过，含根 "/"）。 */
        private void ensureDirectory(SftpClient sftp, String dir) {
            StringBuilder built = new StringBuilder();
            for (String seg : normalize(dir).split("/")) {
                if (seg.isEmpty()) {
                    continue;
                }
                built.append('/').append(seg);
                try {
                    sftp.lstat(built.toString());
                } catch (IOException notExists) {
                    try {
                        sftp.mkdir(built.toString());
                    } catch (IOException e) {
                        throw new BizException(ErrorCode.SSH_ERROR,
                                "mkdir " + built + " on " + target + " failed: " + e.getMessage());
                    }
                }
            }
        }

        private static String normalize(String dir) {
            String d = dir == null || dir.isEmpty() ? "/" : dir;
            while (d.endsWith("/") && d.length() > 1) {
                d = d.substring(0, d.length() - 1);
            }
            return d;
        }

        /** 丢弃流：channel.setErr 用，无限输出场景不关心 stderr。 */
        private static final OutputStream DROP_STREAM = new OutputStream() {
            @Override
            public void write(int b) {
                // 丢弃
            }

            @Override
            public void write(byte[] b, int off, int len) {
                // 丢弃
            }
        };

        /** 关闭 exec 通道（幂等，忽略异常）。 */
        private static void closeQuietly(ClientChannel channel) {
            try {
                channel.close();
            } catch (Exception ignore) {
                // 通道已关闭，忽略
            }
        }

        @Override
        public boolean isOpen() {
            return !closed.get() && session.isOpen() && session.isAuthenticated();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    session.disconnect(0, "closed");
                } catch (IOException ignore) {
                    // 已断开，忽略
                }
            }
        }
    }
}
