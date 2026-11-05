package com.ops.deploy.ssh;

import java.io.InputStream;
import java.util.List;
import java.util.function.Consumer;

/**
 * SSH 执行器接口：Task 5-8 的通道层契约。
 *
 * <p>所有方法以 {@code Long serverId} 为第一参数：实现按 serverId 缓存 SSH 连接
 * （复用 TCP+认证开销）；serverId 同时是连接缓存 key 与 close 的维度。
 * SshTarget 只承载 host/port/凭证，不携带 serverId，保持值对象纯净。
 */
public interface SshExecutor {

    /** 流式句柄：cancel() 幂等，关闭底层 exec 通道。 */
    interface StreamHandle {
        void cancel();
    }

    /** 默认命令执行超时（毫秒）。 */
    int DEFAULT_TIMEOUT_MS = 60_000;

    /** 默认 SFTP 分块大小（256KB）。 */
    int DEFAULT_UPLOAD_CHUNK_SIZE = 256 * 1024;

    /**
     * 执行命令（默认超时 60s）。连接失败/认证失败抛 {@code BizException(SSH_ERROR)}。
     */
    default CommandResult execute(Long serverId, SshTarget target, String command) {
        return execute(serverId, target, command, DEFAULT_TIMEOUT_MS);
    }

    /** 执行命令（指定超时）。超时返回 timedOut=true 的结果，不抛异常。 */
    CommandResult execute(Long serverId, SshTarget target, String command, int timeoutMs);

    /**
     * 幂等命令带重试执行：失败（SSH_ERROR 或超时 timedOut=true）时按指数退避
     * （500ms/1000ms/...，单次上限 5000ms）最多重试 maxRetries 次。
     * 超时是断线最常见的表现，<b>也按可重试失败处理</b>；重试用尽后把最后一次的
     * timedOut 结果返回给调用方（而非抛异常），让状态机能感知超时。
     * 非 SSH 错误（如 400）立即抛出、不重试。Task 6 状态机调用。
     */
    CommandResult executeWithRetry(Long serverId, SshTarget target, String command,
                                   int timeoutMs, int maxRetries);

    /**
     * SFTP 分块上传 + 整体 SHA-256 校验。
     * 流程：传临时文件 {fileName}.uploading → 本地算 SHA-256 → 远端 sha256sum 对比
     * → 一致则 mv 为正式名；不一致/失败抛 {@code BizException(SSH_ERROR)} 并清理临时文件。
     *
     * @return true 表示上传并校验成功
     */
    boolean upload(Long serverId, SshTarget target, String remoteDir, String fileName, byte[] content);

    /**
     * 流式 SFTP 上传 + 远端 SHA-256 校验（大文件版，Task 6 部署包流式上传用）。
     * 与 {@link #upload(Long, SshTarget, String, String, byte[])} 流程一致，区别：
     * <ul>
     *   <li>内容来自 {@code InputStream}（流由调用方负责关闭）</li>
     *   <li>SHA-256 由调用方传入（来自 package_version 表），本地不再计算</li>
     * </ul>
     * 流程：传临时文件 {fileName}.uploading → 远端 sha256sum 与 expectedSha256 对比
     * → 一致则 mv 为正式名；不一致/失败抛 {@code BizException(SSH_ERROR)} 并清理临时文件。
     *
     * @param sizeBytes      声明的内容大小（字节，仅用于日志）
     * @param expectedSha256 期望的 SHA-256 小写 hex（来自 package_version 表）
     * @return true 表示上传并校验成功
     */
    boolean upload(Long serverId, SshTarget target, String remoteDir, String fileName,
                   InputStream in, long sizeBytes, String expectedSha256);

    /**
     * 流式 SFTP 上传 + 整体 SHA-256 校验（大文件版，避免整包进内存，Task 6 部署包 ≥2GB 用）。
     * 流程与 {@link #upload} 一致：传临时文件 {fileName}.uploading → 边传边算本地 SHA-256
     * → 远端 sha256sum 对比 → 一致则 mv 为正式名；不一致/失败抛
     * {@code BizException(SSH_ERROR)} 并清理临时文件。
     *
     * @param declaredSize 声明的内容大小（字节，仅用于日志，不做截断校验）
     * @return true 表示上传并校验成功
     */
    boolean uploadStream(Long serverId, SshTarget target, String remoteDir, String fileName,
                         InputStream content, long declaredSize);

    /** 只读资源探测（CPU/内存/磁盘/端口/运行时），解析失败字段兜底 0/空/"absent"。 */
    ResourceProfile probe(Long serverId, SshTarget target);

    /**
     * 启动后台长任务：nohup 包装，stdout/stderr 追加到日志文件，
     * 先写 RUNNING 状态文件、命令结束写 DONE:&lt;code&gt; / FAILED:&lt;code&gt;，
     * SSH 会话立即返回。
     *
     * @return 状态文件绝对路径（句柄），供 {@link #pollBackground} 轮询
     */
    String launchBackground(Long serverId, SshTarget target, String workdir,
                            String command, String taskName);

    /** 读取状态文件句柄，解析出当前状态（DONE/FAILED 带 exitCode，否则 RUNNING/UNKNOWN）。 */
    BackgroundStatus pollBackground(Long serverId, SshTarget target, String statusFileHandle);

    /** 关闭并移除某服务器的缓存连接。 */
    void close(Long serverId);

    /** 关闭全部缓存连接（@PreDestroy / 平台停机）。 */
    void closeAll();

    /**
     * 异步流式执行命令（journalctl -f 等无限输出场景）。
     *
     * <p>stdout 按行（\n 分隔，去除 \r）回调 {@code lineConsumer}（可能来自内部线程），
     * stderr 忽略。立即返回 {@link StreamHandle}；命令退出（或 cancel 使通道关闭）后
     * 调用 {@code onComplete}（退出码；cancel 时为 null）。
     */
    StreamHandle stream(Long serverId, SshTarget target, String command,
                        Consumer<String> lineConsumer, Consumer<Integer> onComplete);

    /**
     * 单条 SSH 连接的抽象（线程安全），隔离 MINA 细节以便单测注入 fake。
     * 实现需自行处理 channel 打开/关闭、stdout/stderr 读取与超时。
     */
    interface ClientConnection {

        /**
         * 同步执行命令，最多等待 timeoutMs。
         * 超时返回 {@link CommandResult#timeout(long)}；连接级故障抛 {@code BizException(SSH_ERROR)}。
         */
        CommandResult execute(String command, int timeoutMs);

        /**
         * SFTP 分块上传 content 到 remoteDir/fileName（调用方负责临时文件命名与校验）。
         */
        void upload(String remoteDir, String fileName, byte[] content, int chunkSize);

        /**
         * 流式 SFTP 上传（纯传输，不算摘要）：边从 in 读 chunk 边写入 remoteDir/fileName，
         * 256KB 块读到 -1 为止。校验由调用方用远端 sha256sum 完成。
         * 流由调用方负责关闭。
         */
        void upload(String remoteDir, String fileName, InputStream in, long sizeBytes, int chunkSize);

        /**
         * 流式 SFTP 上传：边从 content 读 chunk 边写入 remoteDir/fileName，
         * 同时计算内容 SHA-256。
         *
         * @return 实际写入内容的 SHA-256 小写 hex（供调用方与远端 sha256sum 比对）
         */
        String uploadStream(String remoteDir, String fileName, InputStream content,
                            long declaredSize, int chunkSize);

        /**
         * 异步流式执行命令（journalctl -f 等无限输出场景）：命令 stdout 按行（\n 分隔，去除 \r）
         * 回调 lineConsumer；stderr 忽略。立即返回 StreamHandle；命令退出（或 cancel 使通道关闭）后
         * 调用 onComplete（退出码；cancel 时为 null）。lineConsumer 可能来自内部线程。
         */
        StreamHandle stream(String command, Consumer<String> lineConsumer,
                            Consumer<Integer> onComplete);

        /** 连接是否仍可用（session open 且已认证）。 */
        boolean isOpen();

        /** 关闭底层连接（幂等）。 */
        default void close() {
            // 无资源可释放的 fake 实现可空实现
        }
    }

    /** 连接工厂：按 (target, serverId) 创建（并缓存）一条 {@link ClientConnection}。 */
    @FunctionalInterface
    interface ConnectionFactory {
        ClientConnection create(SshTarget target, Long serverId);
    }
}
