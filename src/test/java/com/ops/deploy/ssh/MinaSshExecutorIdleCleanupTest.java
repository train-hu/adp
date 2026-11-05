package com.ops.deploy.ssh;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MinaSshExecutor 空闲连接回收单测：短空闲超时下验证
 * cleanupIdleConnections 只回收空闲连接（条件移除 + close），活跃连接不受影响。
 * 复用 {@link MinaSshExecutorTest} 的 fake 风格（无真实网络）。
 */
class MinaSshExecutorIdleCleanupTest {

    private static final SshTarget TARGET = new SshTarget("10.0.0.1", 22, "deploy", "secret");

    /** FakeConnection 加 closed 标记，观察空闲回收是否真正调用了 close。 */
    static class ClosingFakeConnection implements SshExecutor.ClientConnection {
        final java.util.List<String> executed = new CopyOnWriteArrayList<>();
        final AtomicInteger executeCount = new AtomicInteger();
        volatile boolean closed;

        @Override
        public CommandResult execute(String command, int timeoutMs) {
            executed.add(command);
            executeCount.incrementAndGet();
            return CommandResult.ok("executed: " + command, 0);
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
            return !closed;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public SshExecutor.StreamHandle stream(String command, java.util.function.Consumer<String> lineConsumer,
                                               java.util.function.Consumer<Integer> onComplete) {
            throw new UnsupportedOperationException("not used in this test");
        }
    }

    static class FakeFactory implements SshExecutor.ConnectionFactory {
        final AtomicInteger createCount = new AtomicInteger();
        final Map<Long, ClosingFakeConnection> perServer = new ConcurrentHashMap<>();

        @Override
        public SshExecutor.ClientConnection create(SshTarget t, Long serverId) {
            createCount.incrementAndGet();
            return perServer.computeIfAbsent(serverId, k -> new ClosingFakeConnection());
        }
    }

    /** 反射读取私有 connections 字段（Map&lt;Long, CachedConnection&gt;）。 */
    @SuppressWarnings("unchecked")
    private static Map<Long, MinaSshExecutor.CachedConnection> cachedConnections(MinaSshExecutor executor)
            throws Exception {
        Field f = MinaSshExecutor.class.getDeclaredField("connections");
        f.setAccessible(true);
        return (Map<Long, MinaSshExecutor.CachedConnection>) f.get(executor);
    }

    @Test
    void idleCleanup_evictsAndClosesIdleConnection() throws Exception {
        // 空闲超时 1 分钟：lastUsed 推老 1 小时即远超阈值，可被回收
        FakeFactory factory = new FakeFactory();
        try (MinaSshExecutor executor = new MinaSshExecutor(factory, 1)) {
            executor.execute(1L, TARGET, "cmd");
            ClosingFakeConnection conn = factory.perServer.get(1L);
            assertThat(cachedConnections(executor)).hasSize(1);

            // 模拟长时间无人取用：把 lastUsed 推到 1 小时前
            MinaSshExecutor.CachedConnection cached = cachedConnections(executor).get(1L);
            cached.lastUsedAt = System.currentTimeMillis() - 60L * 60_000L;

            executor.cleanupIdleConnections();

            // 缓存已清空，且连接被真正 close
            assertThat(cachedConnections(executor)).isEmpty();
            assertThat(conn.closed).isTrue();
        }
    }

    @Test
    void idleCleanup_keepsRecentlyUsedConnection() throws Exception {
        FakeFactory factory = new FakeFactory();
        try (MinaSshExecutor executor = new MinaSshExecutor(factory, 1)) {
            executor.execute(1L, TARGET, "cmd");
            ClosingFakeConnection conn = factory.perServer.get(1L);

            // execute 后 lastUsed 为 now，未超 1 分钟阈值 → 活跃连接不被回收
            executor.cleanupIdleConnections();

            assertThat(cachedConnections(executor)).hasSize(1);
            assertThat(conn.closed).isFalse();
        }
    }

    @Test
    void idleCleanup_onlyReapsExpiredEntries() throws Exception {
        // 两个 server：一个空闲过期、一个刚取用过，只有前者被回收
        FakeFactory factory = new FakeFactory();
        try (MinaSshExecutor executor = new MinaSshExecutor(factory, 1)) {
            executor.execute(1L, TARGET, "cmd");
            executor.execute(2L, TARGET, "cmd");
            ClosingFakeConnection idle = factory.perServer.get(1L);
            ClosingFakeConnection active = factory.perServer.get(2L);

            // 只把 server 1 的 lastUsed 推老 1 小时
            MinaSshExecutor.CachedConnection cached1 = cachedConnections(executor).get(1L);
            cached1.lastUsedAt = System.currentTimeMillis() - 60L * 60_000L;

            executor.cleanupIdleConnections();

            Map<Long, MinaSshExecutor.CachedConnection> remaining = cachedConnections(executor);
            assertThat(remaining).hasSize(1).containsKey(2L);
            assertThat(idle.closed).isTrue();
            assertThat(active.closed).isFalse();
        }
    }

    @Test
    void idleCleanup_evictedConnection_isRecreatedOnNextExecute() throws Exception {
        FakeFactory factory = new FakeFactory();
        try (MinaSshExecutor executor = new MinaSshExecutor(factory, 1)) {
            executor.execute(1L, TARGET, "cmd-1");
            MinaSshExecutor.CachedConnection cached = cachedConnections(executor).get(1L);
            cached.lastUsedAt = System.currentTimeMillis() - 60L * 60_000L;

            executor.cleanupIdleConnections();

            // 回收后再次 execute → 新建一条连接
            executor.execute(1L, TARGET, "cmd-2");
            assertThat(factory.createCount).hasValue(2);
            assertThat(factory.perServer.get(1L).executeCount).hasValue(2);
            assertThat(cachedConnections(executor)).hasSize(1);
        }
    }
}
