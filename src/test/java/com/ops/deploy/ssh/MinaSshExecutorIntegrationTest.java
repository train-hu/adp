package com.ops.deploy.ssh;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpFileSystemAccessor;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B 类集成测试：进程内启动真实 MINA {@link SshServer}（随机端口）+ 真实
 * {@link MinaConnectionFactory}，验证 真实 TCP 连接 / 密码认证 / exec 通道 /
 * SFTP 分块上传 + sha256sum 校验 全链路。
 *
 * <p>测试机（含 Windows CI）无 shell，故用自定义 {@code CommandFactory} 在临时目录
 * 内模拟最小命令集（echo/sha256sum/mv/rm/cat）；SFTP 子系统经
 * {@link SftpFileSystemAccessor} 根重定向到同一临时目录，保证 SFTP 与 exec 看到同一"远端文件系统"。
 * 可选标签 {@code integration} 便于后续单独分组排除，默认随 {@code mvn test} 运行。
 */
@Tag("integration")
class MinaSshExecutorIntegrationTest {

    private static final String USER = "admin";
    private static final String PASSWORD = "int-test-pw";

    @TempDir
    Path tempDir;

    private SshServer server;
    private MinaConnectionFactory factory;
    private MinaSshExecutor executor;
    private SshTarget target;
    /** stream 测试用：stream-slow 命令的 cancel 通知（按 session 隔离）。 */
    private final ConcurrentHashMap<String, CountDownLatch> cancellations = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0); // 随机空闲端口，避免端口冲突
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempDir));
        server.setPasswordAuthenticator((user, pw, session) -> USER.equals(user) && PASSWORD.equals(pw));
        server.setCommandFactory((session, command) -> new SimulatedCommand(command));

        SftpSubsystemFactory sftp = new SftpSubsystemFactory();
        sftp.setFileSystemAccessor(new SftpFileSystemAccessor() {
            @Override
            public Path resolveLocalFilePath(
                    org.apache.sshd.sftp.server.SftpSubsystemProxy proxy, Path base, String fileName) {
                // SFTP 根重定向：远端绝对路径 → 测试临时目录
                return toLocalPath(fileName);
            }
        });
        server.setSubsystemFactories(List.of(sftp));

        server.start();
        target = new SshTarget("127.0.0.1", server.getPort(), USER, PASSWORD);
        factory = new MinaConnectionFactory();
        executor = new MinaSshExecutor(factory);
    }

    @AfterEach
    void tearDown() {
        executor.closeAll();
        factory.shutdown();
        try {
            server.stop();
        } catch (IOException e) {
            // 测试收尾，忽略停止异常
        }
    }

    @Test
    void execute_echoReturnsStdoutAndZeroExit() {
        CommandResult r = executor.execute(1L, target, "echo hello-from-ssh");

        assertThat(r.getExitCode()).isEqualTo(0);
        assertThat(r.getStdout()).contains("hello-from-ssh");
        assertThat(r.getTimedOut()).isFalse();
    }

    @Test
    void execute_largeOutput_isAlwaysComplete_acrossRepeats() {
        // 回归：MINA 的 ClientChannel.waitFor(事件集) 是"任一事件先发生即返回"语义，
        // EXIT_STATUS 常先于远端 stdout 关闭（EOF）到达就返回，此时工作线程可能尚未把
        // DATA 刷入 out 流，直接读取会得到空/不完整 stdout（E2E 实测 sha256sum 校验
        // actual=null 即源于此）。修复为两阶段等待（补等 EOF）后，任何次数执行都应拿到
        // 完整且确定的输出。用 64KB 大块 + 循环放大触发面。
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 64 * 1024 / 16; i++) {
            expected.append(String.format("%016d", i));
        }
        String expectedOut = expected.toString();
        int fails = 0;
        int rounds = 20;
        for (int i = 0; i < rounds; i++) {
            CommandResult r = executor.execute(1L, target, "big-echo");
            if (r.getExitCode() != 0 || !expectedOut.equals(r.getStdout().strip())) {
                fails++;
                if (fails == 1) {
                    System.err.println("[regression] round " + i + " exit=" + r.getExitCode()
                            + " stdoutLen=" + (r.getStdout() == null ? -1 : r.getStdout().length())
                            + " expectedLen=" + expectedOut.length());
                }
            }
        }
        assertThat(fails).as("stdout 完整率应为 100%（两阶段等待已消除刷盘竞态）").isZero();
    }

    @Test
    void authFailure_wrongPassword_throwsSshError() {
        SshTarget badTarget = new SshTarget("127.0.0.1", server.getPort(), USER, "wrong-password");

        assertThatThrownBy(() -> executor.execute(999L, badTarget, "echo x"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.SSH_ERROR));
    }

    @Test
    void upload_sftpChunkedUploadVerifiedByRemoteSha256sum() throws IOException {
        // 300KB > 256KB 默认分块，确保走多分块写
        byte[] content = new byte[300 * 1024];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 251);
        }

        boolean ok = executor.upload(1L, target, "/data/app", "app.jar", content);

        assertThat(ok).isTrue();
        Path finalFile = toLocalPath("/data/app/app.jar");
        assertThat(finalFile).exists();
        assertThat(Files.readAllBytes(finalFile)).isEqualTo(content);
        assertThat(toLocalPath("/data/app/app.jar.uploading")).doesNotExist();
    }

    @Test
    void stream_receivesAllLinesUtf8AndCompletesOnceWithExit() throws InterruptedException {
        List<String> lines = new ArrayList<>();
        AtomicInteger completions = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);

        SshExecutor.StreamHandle handle = executor.stream(1L, target, "stream-echo",
                lines::add,
                exit -> {
                    completions.incrementAndGet();
                    done.countDown();
                });

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        // 全部行按序到达，UTF-8 中文不被拆散
        assertThat(lines).containsExactly("line-1", "line-2-你好", "line-3");
        assertThat(completions.get()).isEqualTo(1);
        assertThat(handle).isNotNull();
        handle.cancel(); // 已结束后 cancel 应幂等无害
    }

    @Test
    void stream_cancelMidStream_completesOnceWithNullExit() throws InterruptedException {
        List<String> lines = new ArrayList<>();
        AtomicInteger completions = new AtomicInteger();
        CountDownLatch firstLine = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        String sessionKey = "stream-slow";
        CountDownLatch cancelSignal = new CountDownLatch(1);
        cancellations.put(sessionKey, cancelSignal);

        SshExecutor.StreamHandle handle = executor.stream(1L, target, "stream-slow",
                line -> {
                    synchronized (lines) {
                        lines.add(line);
                    }
                    firstLine.countDown();
                },
                exit -> {
                    completions.incrementAndGet();
                    done.countDown();
                });
        // 远端阻塞命令启动，先收到第一行
        assertThat(firstLine.await(10, TimeUnit.SECONDS)).isTrue();

        handle.cancel();

        // cancel 后读线程退出，onComplete(null) 恰好一次
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        synchronized (lines) {
            assertThat(lines.get(0)).isEqualTo("first");
        }
        assertThat(completions.get()).isEqualTo(1);
        cancelSignal.countDown(); // 解除远端阻塞命令，避免残留
    }

    private Path toLocalPath(String remotePath) {
        String p = remotePath.startsWith("/") ? remotePath.substring(1) : remotePath;
        if (p.isEmpty()) {
            return tempDir;
        }
        return tempDir.resolve(p);
    }

    /**
     * 在临时目录内模拟最小 shell 命令集（echo/sha256sum/mv/rm/cat），
     * 让 exec 通道与 SFTP 共享同一"远端文件系统"。
     */
    private final class SimulatedCommand implements Command {

        private final String command;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback exitCallback;

        SimulatedCommand(String command) {
            this.command = command;
        }

        @Override
        public void setOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void setErrorStream(OutputStream err) {
            this.err = err;
        }

        @Override
        public void setInputStream(InputStream in) {
            // exec 通道无 stdin 需求
        }

        @Override
        public void setExitCallback(ExitCallback exitCallback) {
            this.exitCallback = exitCallback;
        }

        @Override
        public void start(ChannelSession session, Environment env) {
            int exit = run();
            try {
                out.flush();
                err.flush();
            } catch (IOException ignored) {
                // 输出流关闭，忽略
            }
            exitCallback.onExit(exit, "", false);
        }

        private int run() {
            try {
                if (command.startsWith("echo ")) {
                    out.write(command.substring(5).getBytes(StandardCharsets.UTF_8));
                    out.write('\n');
                } else if (command.equals("big-echo")) {
                    // 大块确定性输出（64KB），放大 stdout 刷盘竞态的触发面：
                    // EXIT_STATUS 更可能在所有 DATA 写回 out 流之前到达。
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 64 * 1024 / 16; i++) {
                        sb.append(String.format("%016d", i));
                    }
                    out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                    out.write('\n');
                } else if (command.startsWith("sha256sum ")) {
                    String path = command.substring("sha256sum ".length()).strip();
                    Path f = toLocalPath(path);
                    if (!Files.isRegularFile(f)) {
                        err.write(("sha256sum: " + path + ": No such file").getBytes(StandardCharsets.UTF_8));
                        return 1;
                    }
                    out.write((Sha256Util.hex(Files.readAllBytes(f)) + "  " + path)
                            .getBytes(StandardCharsets.UTF_8));
                    out.write('\n');
                } else if (command.startsWith("mv -f ")) {
                    String[] parts = command.substring(6).trim().split("\\s+");
                    Files.move(toLocalPath(parts[0]), toLocalPath(parts[1]), StandardCopyOption.REPLACE_EXISTING);
                } else if (command.startsWith("rm -f ")) {
                    Files.deleteIfExists(toLocalPath(command.substring(6).strip()));
                } else if (command.startsWith("cat ")) {
                    String path = command.substring(4).strip();
                    Path f = toLocalPath(path);
                    if (!Files.isRegularFile(f)) {
                        err.write(("cat: " + path + ": No such file").getBytes(StandardCharsets.UTF_8));
                        return 1;
                    }
                    out.write(Files.readAllBytes(f));
                } else if (command.equals("stream-echo")) {
                    // 流式命令：3 行输出（含 UTF-8 中文）后退出，exit 0
                    out.write("line-1\nline-2-你好\nline-3\n".getBytes(StandardCharsets.UTF_8));
                } else if (command.equals("stream-slow")) {
                    // 流式阻塞命令：发一行并 flush（保证阻塞期间首行即时送达），随后阻塞，
                    // 等待测试 cancel 后退出（模拟 journalctl -f）
                    out.write("first\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    cancellations.get("stream-slow").await(10, TimeUnit.SECONDS);
                } else {
                    out.write("ok\n".getBytes(StandardCharsets.UTF_8));
                }
                return 0;
            } catch (Exception e) {
                try {
                    err.write(("simulated command failed: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // 输出流关闭，忽略
                }
                return 1;
            }
        }

        @Override
        public void destroy(ChannelSession session) {
            // 无资源需释放
        }
    }
}
