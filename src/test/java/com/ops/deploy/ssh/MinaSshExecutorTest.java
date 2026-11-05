package com.ops.deploy.ssh;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MinaSshExecutor 纯逻辑单测（A 类）：连接缓存 / 重试 / 超时 / SFTP 校验流程 / 探测 / 后台任务。
 * 通过 fake ConnectionFactory + fake ClientConnection 模拟，不触发任何真实网络。
 */
class MinaSshExecutorTest {

    private static final SshTarget TARGET = new SshTarget("10.0.0.1", 22, "deploy", "secret");

    // ---------- fakes ----------

    static class FakeConnection implements SshExecutor.ClientConnection {
        final List<String> executed = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> uploaded = new java.util.concurrent.CopyOnWriteArrayList<>();
        // 模拟远端文件系统：upload 落盘，sha256sum 按真实内容回算
        final Map<String, byte[]> uploadedFiles = new java.util.HashMap<>();
        final AtomicInteger executeCount = new AtomicInteger();
        int failureCount;
        int slowMs;
        boolean open = true;
        // 流式上传观测：读次数与单次最大读长（验证分块行为）
        int streamReadCount;
        int maxStreamReadLen;

        @Override
        public CommandResult execute(String command, int timeoutMs) {
            executed.add(command);
            executeCount.incrementAndGet();
            if (failureCount > 0) {
                failureCount--;
                throw new BizException(ErrorCode.SSH_ERROR, "fake connection failure");
            }
            if (slowMs > timeoutMs) {
                return CommandResult.timeout(slowMs);
            }
            if (command.startsWith("sha256sum ")) {
                String path = command.substring("sha256sum ".length()).strip();
                byte[] content = uploadedFiles.get(path);
                String stdout = content != null
                        ? Sha256Util.hex(content) + "  " + path
                        : "";
                return CommandResult.ok(stdout, 0);
            }
            return CommandResult.ok("executed: " + command, 0);
        }

        @Override
        public void upload(String remoteDir, String fileName, byte[] content, int chunkSize) {
            uploaded.add(remoteDir + "/" + fileName);
            uploadedFiles.put(remoteDir + "/" + fileName, content);
        }

        @Override
        public String uploadStream(String remoteDir, String fileName, InputStream content,
                                   long declaredSize, int chunkSize) {
            uploaded.add(remoteDir + "/" + fileName);
            // 逐 chunk 读入内存并落盘（模拟真实 SFTP 流式写入），边读边算 SHA-256
            byte[] buf = new byte[chunkSize];
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                int len;
                while ((len = content.read(buf)) != -1) {
                    baos.write(buf, 0, len);
                    md.update(buf, 0, len);
                    streamReadCount++;
                    maxStreamReadLen = Math.max(maxStreamReadLen, len);
                }
                byte[] data = baos.toByteArray();
                uploadedFiles.put(remoteDir + "/" + fileName, data);
                return Sha256Util.hex(data);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void upload(String remoteDir, String fileName, InputStream in, long sizeBytes, int chunkSize) {
            // 纯传输重载（不算摘要）：逐 chunk 读入内存并落盘，复用流式观测字段
            uploaded.add(remoteDir + "/" + fileName);
            byte[] buf = new byte[chunkSize];
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            try {
                int len;
                while ((len = in.read(buf)) != -1) {
                    baos.write(buf, 0, len);
                    streamReadCount++;
                    maxStreamReadLen = Math.max(maxStreamReadLen, len);
                }
                uploadedFiles.put(remoteDir + "/" + fileName, baos.toByteArray());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }

        int streamFailureCount;
        int streamCallCount;

        @Override
        public SshExecutor.StreamHandle stream(String command, java.util.function.Consumer<String> lineConsumer,
                                               java.util.function.Consumer<Integer> onComplete) {
            streamCallCount++;
            if (streamFailureCount > 0) {
                streamFailureCount--;
                throw new BizException(ErrorCode.SSH_ERROR, "fake stream failure");
            }
            return () -> {};
        }
    }

    static class FakeFactory implements SshExecutor.ConnectionFactory {
        final AtomicInteger createCount = new AtomicInteger();
        final Map<Long, FakeConnection> perServer = new ConcurrentHashMap<>();

        @Override
        public SshExecutor.ClientConnection create(SshTarget t, Long serverId) {
            createCount.incrementAndGet();
            // computeIfAbsent：与真实 executor 的缓存语义一致，预置的 fake 会被复用
            return perServer.computeIfAbsent(serverId, k -> new FakeConnection());
        }
    }

    /** 不去重的工厂：每次 create 都新建一个连接并计数（模拟真实工厂，暴露建连竞态）。 */
    static class CountingNoDedupFactory implements SshExecutor.ConnectionFactory {
        final AtomicInteger createCount = new AtomicInteger();
        final List<FakeConnection> created = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public SshExecutor.ClientConnection create(SshTarget t, Long serverId) {
            createCount.incrementAndGet();
            FakeConnection c = new FakeConnection();
            created.add(c);
            return c;
        }
    }

    // ---------- 连接缓存 ----------

    @Test
    void connectionCache_sameServerId_createdOnlyOnce() {
        FakeFactory factory = new FakeFactory();
        try (MinaSshExecutor executor = new MinaSshExecutor(factory)) {
            executor.execute(1L, TARGET, "cmd-1");
            executor.execute(1L, TARGET, "cmd-2");
            executor.execute(1L, TARGET, "cmd-3");

            assertThat(factory.createCount).hasValue(1);
            assertThat(factory.perServer.get(1L).executeCount).hasValue(3);
        }
    }

    @Test
    void connectionCache_differentServerIds_createdSeparately() {
        FakeFactory factory = new FakeFactory();
        try (MinaSshExecutor executor = new MinaSshExecutor(factory)) {
            executor.execute(1L, TARGET, "cmd");
            executor.execute(2L, TARGET, "cmd");

            assertThat(factory.createCount).hasValue(2);
            assertThat(factory.perServer).hasSize(2);
        }
    }

    @Test
    void concurrentFirstExecute_sameServerId_factoryCreateCalledOnce() throws InterruptedException {
        // factory 不去重（模拟真实工厂）：若 executor 内部取/建两步非原子，
        // 并发首次调用会双建，输掉竞态的 ClientSession 无引用、永不 close → 泄漏
        CountingNoDedupFactory factory = new CountingNoDedupFactory();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try (MinaSshExecutor executor = new MinaSshExecutor(factory)) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        CommandResult r = executor.execute(1L, TARGET, "concurrent-cmd");
                        assertThat(r.getExitCode()).isEqualTo(0);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

            // 只建一条连接，无泄漏；所有执行落在同一条连接上
            assertThat(factory.createCount).hasValue(1);
            assertThat(factory.created.get(0).executeCount).hasValue(threads);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void close_serverId_recreatesConnectionOnNextExecute() {
        FakeFactory factory = new FakeFactory();
        try (MinaSshExecutor executor = new MinaSshExecutor(factory)) {
            executor.execute(1L, TARGET, "cmd-1");
            assertThat(factory.createCount).hasValue(1);

            executor.close(1L);

            executor.execute(1L, TARGET, "cmd-2");
            assertThat(factory.createCount).hasValue(2);
        }
    }

    @Test
    void closeAll_closesEverything() {
        FakeFactory factory = new FakeFactory();
        try (MinaSshExecutor executor = new MinaSshExecutor(factory)) {
            executor.execute(1L, TARGET, "a");
            executor.execute(2L, TARGET, "b");
            executor.closeAll();

            executor.execute(1L, TARGET, "c");
            assertThat(factory.createCount).hasValue(3);
        }
    }

    @Test
    void stream_sshError_evictsPoisonedConnectionAndRecreates() {
        // 缓存的 SSH 连接半死（TCP 活着但协议层失效，isOpen()==true 不触发自动重建）：
        // 若 stream 不剔除坏连接，后续 SSE 请求会反复 500 无法自愈（缺陷11 根因）。
        FakeFactory factory = new FakeFactory();
        FakeConnection poisoned = new FakeConnection();
        poisoned.streamFailureCount = Integer.MAX_VALUE;
        factory.perServer.put(1L, poisoned);
        try (MinaSshExecutor executor = new MinaSshExecutor(factory)) {
            assertThatThrownBy(() -> executor.stream(1L, TARGET, "journalctl -f",
                    l -> {}, code -> {}))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.SSH_ERROR));

            // 修复后：坏连接被剔除并关闭，下次 stream 拿到新连接（重建成功）
            assertThat(poisoned.open).isFalse();
            FakeConnection fresh = new FakeConnection();
            factory.perServer.put(1L, fresh);
            executor.stream(1L, TARGET, "journalctl -f", l -> {}, code -> {});
            assertThat(fresh.streamCallCount).isEqualTo(1);
            assertThat(poisoned.streamCallCount).isEqualTo(1); // 第二次未再打到坏连接
        }
    }

    // ---------- 命令执行结果 ----------

    @Test
    void execute_returnsCommandOutputAndZeroExit() {
        try (MinaSshExecutor executor = new MinaSshExecutor(new FakeFactory())) {
            CommandResult r = executor.execute(1L, TARGET, "echo hi");

            assertThat(r.getExitCode()).isEqualTo(0);
            assertThat(r.getStdout()).contains("echo hi");
            assertThat(r.getTimedOut()).isFalse();
            assertThat(r.getDurationMs()).isGreaterThanOrEqualTo(0);
        }
    }

    // ---------- 超时 ----------

    @Test
    void execute_withTinyTimeout_returnsTimedOutResult() {
        FakeFactory factory = new FakeFactory();
        factory.perServer.put(1L, new FakeConnection());
        try (MinaSshExecutor executor = new MinaSshExecutor(factory)) {
            factory.perServer.get(1L).slowMs = 300;

            CommandResult r = executor.execute(1L, TARGET, "slow-command", 50);

            assertThat(r.getTimedOut()).isTrue();
        }
    }

    // ---------- 重试 ----------

    @Test
    void retry_firstTwoFailThenSucceed_returnsSuccess() {
        FakeFactory factory = new FakeFactory();
        factory.perServer.put(1L, new FakeConnection());
        try (MinaSshExecutor executor = new MinaSshExecutor(factory)) {
            factory.perServer.get(1L).failureCount = 2;

            long start = System.currentTimeMillis();
            CommandResult r = executor.executeWithRetry(1L, TARGET, "idempotent-cmd", 5000, 2);
            long elapsed = System.currentTimeMillis() - start;

            assertThat(r.getExitCode()).isEqualTo(0);
            assertThat(factory.perServer.get(1L).executeCount).hasValue(3);
            // 指数退避 500ms + 1000ms（留少量抖动余量）
            assertThat(elapsed).isGreaterThanOrEqualTo(1400);
        }
    }

    @Test
    void retry_allAttemptsFail_throwsSshError() {
        FakeFactory factory = new FakeFactory();
        factory.perServer.put(1L, new FakeConnection());
        try (MinaSshExecutor executor = new MinaSshExecutor(factory)) {
            factory.perServer.get(1L).failureCount = Integer.MAX_VALUE;

            assertThatThrownBy(() -> executor.executeWithRetry(1L, TARGET, "cmd", 1000, 2))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.SSH_ERROR));
            // 首次 + 重试 2 次 = 3 次尝试
            assertThat(factory.perServer.get(1L).executeCount).hasValue(3);
        }
    }

    @Test
    void retry_nonSshError_notRetried() {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        SshExecutor.ClientConnection throwing = new SshExecutor.ClientConnection() {
            @Override
            public CommandResult execute(String command, int timeoutMs) {
                attempts.incrementAndGet();
                if (attempts.get() == 1) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "bad param");
                }
                return CommandResult.ok("ok", 0);
            }

            @Override
            public void upload(String remoteDir, String fileName, byte[] content, int chunkSize) {
            }

            @Override
            public String uploadStream(String remoteDir, String fileName, InputStream content,
                                       long declaredSize, int chunkSize) {
                return null;
            }

            @Override
            public void upload(String remoteDir, String fileName, InputStream in, long sizeBytes, int chunkSize) {
            }

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public SshExecutor.StreamHandle stream(String command, java.util.function.Consumer<String> lineConsumer,
                                                   java.util.function.Consumer<Integer> onComplete) {
                throw new UnsupportedOperationException("not used in this test");
            }
        };
        try (MinaSshExecutor executor = new MinaSshExecutor((t, serverId) -> throwing)) {
            assertThatThrownBy(() -> executor.executeWithRetry(1L, TARGET, "cmd", 1000, 2))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        }
        // 非 SSH 错误立即抛出、不消耗重试预算：只尝试了 1 次
        assertThat(attempts).hasValue(1);
    }

    @Test
    void retry_timeout_result_isRetriedThenReturned() {
        FakeFactory factory = new FakeFactory();
        factory.perServer.put(1L, new FakeConnection());
        try (MinaSshExecutor executor = new MinaSshExecutor(factory)) {
            // slowMs=300 > timeoutMs=50 → 每次 execute 都返回 timedOut=true
            factory.perServer.get(1L).slowMs = 300;

            CommandResult r = executor.executeWithRetry(1L, TARGET, "slow-cmd", 50, 2);

            // 超时视为可重试失败：首次 + 重试 2 次 = 3 次尝试
            assertThat(factory.perServer.get(1L).executeCount).hasValue(3);
            // 重试用尽后返回最后一次的 timedOut 结果，让调用方状态机能感知超时
            assertThat(r.getTimedOut()).isTrue();
            assertThat(r.getExitCode()).isEqualTo(CommandResult.TIMEOUT_EXIT_CODE);
        }
    }

    @Test
    void retry_backoffDelay_cappedAtFiveSeconds() {
        // 指数退避 500/1000/2000/4000，第 5 次起 clamp 到 5000
        assertThat(MinaSshExecutor.backoffDelayMs(1)).isEqualTo(500);
        assertThat(MinaSshExecutor.backoffDelayMs(2)).isEqualTo(1000);
        assertThat(MinaSshExecutor.backoffDelayMs(3)).isEqualTo(2000);
        assertThat(MinaSshExecutor.backoffDelayMs(4)).isEqualTo(4000);
        assertThat(MinaSshExecutor.backoffDelayMs(5)).isEqualTo(5000);
        assertThat(MinaSshExecutor.backoffDelayMs(6)).isEqualTo(5000);
    }

    // ---------- SFTP 上传 + SHA-256 校验流程 ----------

    @Test
    void upload_writesTempFileVerifiesAndRenames() {
        FakeFactory factory = new FakeFactory();
        try (MinaSshExecutor executor = new MinaSshExecutor(factory)) {
            byte[] content = "hello".getBytes(StandardCharsets.UTF_8);

            boolean ok = executor.upload(1L, TARGET, "/data", "app.jar", content);

            assertThat(ok).isTrue();
            // 先传临时文件
            assertThat(factory.perServer.get(1L).uploaded).contains("/data/app.jar.uploading");
            // 远端 sha256sum 校验
            assertThat(factory.perServer.get(1L).executed)
                    .anySatisfy(c -> assertThat(c).contains("sha256sum /data/app.jar.uploading"));
            // 校验一致后重命名为正式名
            assertThat(factory.perServer.get(1L).executed)
                    .anySatisfy(c -> assertThat(c).contains("mv -f /data/app.jar.uploading /data/app.jar"));
        }
    }

    @Test
    void upload_hashMismatch_throwsSshErrorAndCleansTempFile() {
        // 用固定返回的 fake 连接：upload 记录上传、execute 恒返回固定串（无法匹配真实摘要）→ 校验必然失败
        SshExecutor.ClientConnection conn = new SshExecutor.ClientConnection() {
            @Override
            public CommandResult execute(String command, int timeoutMs) {
                if (command.contains("sha256sum")) {
                    return CommandResult.ok("deadbeef  " + command.substring(command.lastIndexOf(' ') + 1), 0);
                }
                if (command.contains("rm -f")) {
                    return CommandResult.ok("", 0);
                }
                return CommandResult.ok("", 0);
            }

            @Override
            public void upload(String remoteDir, String fileName, byte[] content, int chunkSize) {
            }

            @Override
            public String uploadStream(String remoteDir, String fileName, InputStream content,
                                       long declaredSize, int chunkSize) {
                // 返回与远端固定摘要不匹配的 hex，触发 mismatch
                return "cafecafecafecafecafecafecafecafecafecafecafecafecafecafecafecafecafe";
            }

            @Override
            public void upload(String remoteDir, String fileName, InputStream in, long sizeBytes, int chunkSize) {
            }

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public SshExecutor.StreamHandle stream(String command, java.util.function.Consumer<String> lineConsumer,
                                                   java.util.function.Consumer<Integer> onComplete) {
                throw new UnsupportedOperationException("not used in this test");
            }
        };
        try (MinaSshExecutor executor = new MinaSshExecutor((t, serverId) -> conn)) {
            assertThatThrownBy(() -> executor.upload(1L, TARGET, "/data", "app.jar",
                    new byte[]{1, 2, 3}))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.SSH_ERROR));
        }
    }

    // ---------- SFTP 流式上传 + SHA-256 校验流程 ----------

    @Test
    void uploadStream_writesTempFileVerifiesAndRenames() {
        FakeFactory factory = new FakeFactory();
        try (MinaSshExecutor executor = new MinaSshExecutor(factory)) {
            byte[] content = "streaming content for upload".getBytes(StandardCharsets.UTF_8);

            boolean ok = executor.uploadStream(1L, TARGET, "/data", "app.zip",
                    new ByteArrayInputStream(content), content.length);

            assertThat(ok).isTrue();
            // 先传临时文件
            assertThat(factory.perServer.get(1L).uploaded).contains("/data/app.zip.uploading");
            // 远端 sha256sum 校验
            assertThat(factory.perServer.get(1L).executed)
                    .anySatisfy(c -> assertThat(c).contains("sha256sum /data/app.zip.uploading"));
            // 校验一致后重命名为正式名
            assertThat(factory.perServer.get(1L).executed)
                    .anySatisfy(c -> assertThat(c).contains("mv -f /data/app.zip.uploading /data/app.zip"));
            // 远端最终文件内容与输入一致
            assertThat(factory.perServer.get(1L).uploadedFiles.get("/data/app.zip.uploading"))
                    .isEqualTo(content);
        }
    }

    @Test
    void uploadStream_hashMismatch_throwsSshErrorAndCleansTempFile() {
        // uploadStream 返回的本地摘要与远端固定摘要不一致 → 必然 mismatch，
        // 且应触发 rm -f 清理临时文件
        java.util.List<String> executed = new java.util.ArrayList<>();
        SshExecutor.ClientConnection conn = new SshExecutor.ClientConnection() {
            @Override
            public CommandResult execute(String command, int timeoutMs) {
                executed.add(command);
                if (command.contains("sha256sum")) {
                    return CommandResult.ok("deadbeef  " + command.substring(command.lastIndexOf(' ') + 1), 0);
                }
                return CommandResult.ok("", 0);
            }

            @Override
            public void upload(String remoteDir, String fileName, byte[] content, int chunkSize) {
            }

            @Override
            public String uploadStream(String remoteDir, String fileName, InputStream content,
                                       long declaredSize, int chunkSize) {
                // 消费流（模拟真实传输）后返回与远端不匹配的 hex
                try {
                    content.readAllBytes();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return "cafecafecafecafecafecafecafecafecafecafecafecafecafecafecafecafecafe";
            }

            @Override
            public void upload(String remoteDir, String fileName, InputStream in, long sizeBytes, int chunkSize) {
                try {
                    in.readAllBytes();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public SshExecutor.StreamHandle stream(String command, java.util.function.Consumer<String> lineConsumer,
                                                   java.util.function.Consumer<Integer> onComplete) {
                throw new UnsupportedOperationException("not used in this test");
            }
        };
        try (MinaSshExecutor executor = new MinaSshExecutor((t, serverId) -> conn)) {
            byte[] content = new byte[]{1, 2, 3, 4};
            assertThatThrownBy(() -> executor.uploadStream(1L, TARGET, "/data", "app.zip",
                    new ByteArrayInputStream(content), content.length))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.SSH_ERROR));
            // 失败后清理临时文件
            assertThat(executed).anySatisfy(c -> assertThat(c).contains("rm -f /data/app.zip.uploading"));
        }
    }

    @Test
    void uploadStream_readsInChunksAndTransfersAllBytes() {
        // 300KB 内容 > 默认 256KB 分块 → 至少读 2 次，单次读长不超过 chunkSize
        FakeFactory factory = new FakeFactory();
        factory.perServer.put(1L, new FakeConnection());
        try (MinaSshExecutor executor = new MinaSshExecutor(factory)) {
            byte[] content = new byte[300 * 1024];
            java.util.Arrays.fill(content, (byte) 7);

            boolean ok = executor.uploadStream(1L, TARGET, "/data", "big.tar.gz",
                    new ByteArrayInputStream(content), content.length);

            assertThat(ok).isTrue();
            FakeConnection c = factory.perServer.get(1L);
            assertThat(c.streamReadCount).isGreaterThanOrEqualTo(2);
            assertThat(c.maxStreamReadLen).isLessThanOrEqualTo(SshExecutor.DEFAULT_UPLOAD_CHUNK_SIZE);
            assertThat(c.uploadedFiles.get("/data/big.tar.gz.uploading")).isEqualTo(content);
        }
    }

    @Test
    void uploadStream_emptyContent_succeedsWithEmptyFile() {
        FakeFactory factory = new FakeFactory();
        try (MinaSshExecutor executor = new MinaSshExecutor(factory)) {
            boolean ok = executor.uploadStream(1L, TARGET, "/data", "empty.zip",
                    new ByteArrayInputStream(new byte[0]), 0);

            assertThat(ok).isTrue();
            // 空内容的 SHA-256 摘要（e3b0c442...）通过远端校验
            assertThat(factory.perServer.get(1L).uploadedFiles.get("/data/empty.zip.uploading"))
                    .isEmpty();
        }
    }

    // ---------- 流式上传 + 调用方传入 SHA 校验（Task 6 部署包版） ----------

    @Test
    void upload_streamRemoteVerify_writesTempFileAndRenamesOnMatch() {
        FakeFactory factory = new FakeFactory();
        try (MinaSshExecutor executor = new MinaSshExecutor(factory)) {
            byte[] content = "deploy package streamed".getBytes(StandardCharsets.UTF_8);
            String expected = Sha256Util.hex(content);

            boolean ok = executor.upload(1L, TARGET, "/data", "app.tar.gz",
                    new ByteArrayInputStream(content), content.length, expected);

            assertThat(ok).isTrue();
            // 先传临时文件
            assertThat(factory.perServer.get(1L).uploaded).contains("/data/app.tar.gz.uploading");
            // 远端 sha256sum 校验
            assertThat(factory.perServer.get(1L).executed)
                    .anySatisfy(c -> assertThat(c).contains("sha256sum /data/app.tar.gz.uploading"));
            // 校验一致后重命名为正式名
            assertThat(factory.perServer.get(1L).executed)
                    .anySatisfy(c -> assertThat(c).contains("mv -f /data/app.tar.gz.uploading /data/app.tar.gz"));
            // 远端最终文件内容与输入一致
            assertThat(factory.perServer.get(1L).uploadedFiles.get("/data/app.tar.gz.uploading"))
                    .isEqualTo(content);
        }
    }

    @Test
    void upload_streamRemoteVerify_hashMismatch_throwsSshErrorAndCleansTempFile() {
        // 传入与远端固定摘要不一致的 expectedSha256 → 必然 mismatch，且触发 rm -f 清理
        java.util.List<String> executed = new java.util.ArrayList<>();
        SshExecutor.ClientConnection conn = new SshExecutor.ClientConnection() {
            @Override
            public CommandResult execute(String command, int timeoutMs) {
                executed.add(command);
                if (command.contains("sha256sum")) {
                    return CommandResult.ok("deadbeef  " + command.substring(command.lastIndexOf(' ') + 1), 0);
                }
                return CommandResult.ok("", 0);
            }

            @Override
            public void upload(String remoteDir, String fileName, byte[] content, int chunkSize) {
            }

            @Override
            public String uploadStream(String remoteDir, String fileName, InputStream content,
                                       long declaredSize, int chunkSize) {
                return null;
            }

            @Override
            public void upload(String remoteDir, String fileName, InputStream in, long sizeBytes, int chunkSize) {
                // 消费流（模拟真实传输）
                try {
                    in.readAllBytes();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public SshExecutor.StreamHandle stream(String command, java.util.function.Consumer<String> lineConsumer,
                                                   java.util.function.Consumer<Integer> onComplete) {
                throw new UnsupportedOperationException("not used in this test");
            }
        };
        try (MinaSshExecutor executor = new MinaSshExecutor((t, serverId) -> conn)) {
            byte[] content = new byte[]{1, 2, 3, 4};
            String wrong = "f".repeat(64);
            assertThatThrownBy(() -> executor.upload(1L, TARGET, "/data", "app.tar.gz",
                    new ByteArrayInputStream(content), content.length, wrong))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.SSH_ERROR));
            // 失败后清理临时文件
            assertThat(executed).anySatisfy(c -> assertThat(c).contains("rm -f /data/app.tar.gz.uploading"));
        }
    }

    @Test
    void upload_streamRemoteVerify_readsInChunksAndTransfersAllBytes() {
        // 300KB 内容 > 默认 256KB 分块 → 至少读 2 次，单次读长不超过 chunkSize
        FakeFactory factory = new FakeFactory();
        factory.perServer.put(1L, new FakeConnection());
        try (MinaSshExecutor executor = new MinaSshExecutor(factory)) {
            byte[] content = new byte[300 * 1024];
            java.util.Arrays.fill(content, (byte) 9);
            String expected = Sha256Util.hex(content);

            boolean ok = executor.upload(1L, TARGET, "/data", "big.tar.gz",
                    new ByteArrayInputStream(content), content.length, expected);

            assertThat(ok).isTrue();
            FakeConnection c = factory.perServer.get(1L);
            assertThat(c.streamReadCount).isGreaterThanOrEqualTo(2);
            assertThat(c.maxStreamReadLen).isLessThanOrEqualTo(SshExecutor.DEFAULT_UPLOAD_CHUNK_SIZE);
            assertThat(c.uploadedFiles.get("/data/big.tar.gz.uploading")).isEqualTo(content);
        }
    }

    // ---------- 资源探测（fake 覆盖"执行→解析"编排） ----------

    @Test
    void probe_executesAndParses() {
        String probeOutput = "CPU=8\nLOAD=0.10 0.05 0.01\nMEM=MemTotal:       16384000 kB\n"
                + "MEM=MemAvailable:    8192000 kB\nDISK=/dev/vda1 1000000 4096 500000 1% /\nPORTS=22\n"
                + "JAVA=absent\nPYTHON3=absent\nNODE=absent\nDOCKER=absent\nKUBECTL=absent";
        SshExecutor.ConnectionFactory probeFactory = (t, serverId) -> new SshExecutor.ClientConnection() {
            @Override
            public CommandResult execute(String command, int timeoutMs) {
                return CommandResult.ok(probeOutput, 0);
            }

            @Override
            public void upload(String remoteDir, String fileName, byte[] content, int chunkSize) {
            }

            @Override
            public String uploadStream(String remoteDir, String fileName, InputStream content,
                                       long declaredSize, int chunkSize) {
                return null;
            }

            @Override
            public void upload(String remoteDir, String fileName, InputStream in, long sizeBytes, int chunkSize) {
            }

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public SshExecutor.StreamHandle stream(String command, java.util.function.Consumer<String> lineConsumer,
                                                   java.util.function.Consumer<Integer> onComplete) {
                throw new UnsupportedOperationException("not used in this test");
            }
        };
        try (MinaSshExecutor executor = new MinaSshExecutor(probeFactory)) {
            ResourceProfile p = executor.probe(1L, TARGET);

            assertThat(p.getCpuCores()).isEqualTo(8);
            assertThat(p.getMemTotalKb()).isEqualTo(16_384_000L);
            assertThat(p.getUsedPorts()).containsExactly("22");
            assertThat(p.getRuntimes()).containsValue("absent");
        }
    }

    // ---------- 后台任务（fake 覆盖"构造→launch→poll"编排） ----------

    @Test
    void launchBackground_returnsHandleAndPollReadsStatus() {
        SshExecutor.ConnectionFactory factory = (t, serverId) -> new SshExecutor.ClientConnection() {
            @Override
            public CommandResult execute(String command, int timeoutMs) {
                if (command.contains("cat ")) {
                    return CommandResult.ok("DONE:0", 0);
                }
                return CommandResult.ok(command, 0);
            }

            @Override
            public void upload(String remoteDir, String fileName, byte[] content, int chunkSize) {
            }

            @Override
            public String uploadStream(String remoteDir, String fileName, InputStream content,
                                       long declaredSize, int chunkSize) {
                return null;
            }

            @Override
            public void upload(String remoteDir, String fileName, InputStream in, long sizeBytes, int chunkSize) {
            }

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public SshExecutor.StreamHandle stream(String command, java.util.function.Consumer<String> lineConsumer,
                                                   java.util.function.Consumer<Integer> onComplete) {
                throw new UnsupportedOperationException("not used in this test");
            }
        };
        try (MinaSshExecutor executor = new MinaSshExecutor(factory)) {
            String handle = executor.launchBackground(1L, TARGET, "/opt/app", "run.sh", "task-1");

            assertThat(handle).startsWith("/opt/app/");
            assertThat(handle).contains("task-1");

            BackgroundStatus s = executor.pollBackground(1L, TARGET, handle);
            assertThat(s.getState()).isEqualTo("DONE");
            assertThat(s.getExitCode()).isEqualTo(0);
        }
    }
}
