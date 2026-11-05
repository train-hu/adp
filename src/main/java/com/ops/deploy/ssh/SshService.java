package com.ops.deploy.ssh;

import com.ops.deploy.server.ServerService;
import com.ops.deploy.server.ServerVO;
import org.springframework.stereotype.Service;

/**
 * SSH 通道服务：上层（Task 5-8 部署流水线）只依赖 {@code Long serverId} 入口，
 * 凭证获取（{@link ServerService#getDecryptedPassword}）在此收口。
 *
 * <p>凭证安全：{@link SshTarget} 只在方法内部短暂存在，日志一律使用其打码
 * {@link SshTarget#toString()}；明文密码不出现在任何字段/异常消息/响应中。
 */
@Service
public class SshService {

    private final ServerService serverService;
    private final SshExecutor sshExecutor;

    public SshService(ServerService serverService, SshExecutor sshExecutor) {
        this.serverService = serverService;
        this.sshExecutor = sshExecutor;
    }

    /** 取服务器 VO + 解密密码，组合成一次性 {@link SshTarget}。 */
    private SshTarget target(Long serverId) {
        ServerVO vo = serverService.get(serverId);
        String password = serverService.getDecryptedPassword(serverId);
        return SshTarget.of(vo, password);
    }

    public CommandResult execute(Long serverId, String command) {
        return sshExecutor.execute(serverId, target(serverId), command);
    }

    public CommandResult execute(Long serverId, String command, int timeoutMs) {
        return sshExecutor.execute(serverId, target(serverId), command, timeoutMs);
    }

    public CommandResult executeWithRetry(Long serverId, String command, int timeoutMs, int maxRetries) {
        return sshExecutor.executeWithRetry(serverId, target(serverId), command, timeoutMs, maxRetries);
    }

    public boolean upload(Long serverId, String remoteDir, String fileName, byte[] content) {
        SshPathRules.validateRemoteDir(remoteDir);
        SshPathRules.validateFileName(fileName);
        return sshExecutor.upload(serverId, target(serverId), remoteDir, fileName, content);
    }

    /** 流式上传（大文件版，避免整包进内存），校验规则与 {@link #upload} 一致。 */
    public boolean uploadStream(Long serverId, String remoteDir, String fileName,
                                java.io.InputStream content, long declaredSize) {
        SshPathRules.validateRemoteDir(remoteDir);
        SshPathRules.validateFileName(fileName);
        return sshExecutor.uploadStream(serverId, target(serverId), remoteDir, fileName, content, declaredSize);
    }

    /**
     * 流式上传 + 远端 SHA-256 校验（Task 6 部署包用）。
     * expectedSha256 来自 package_version 表（上传时已校验），本地不再计算。
     * 校验规则与 {@link #uploadStream(Long, String, String, java.io.InputStream, long)} 一致。
     */
    public boolean uploadStream(Long serverId, String remoteDir, String fileName,
                                java.io.InputStream content, long sizeBytes, String expectedSha256) {
        SshPathRules.validateRemoteDir(remoteDir);
        SshPathRules.validateFileName(fileName);
        return sshExecutor.upload(serverId, target(serverId), remoteDir, fileName, content, sizeBytes, expectedSha256);
    }

    public ResourceProfile probe(Long serverId) {
        return sshExecutor.probe(serverId, target(serverId));
    }

    public String launchBackground(Long serverId, String workdir, String command, String taskName) {
        SshPathRules.validateRemoteDir(workdir);
        SshPathRules.validateTaskName(taskName);
        return sshExecutor.launchBackground(serverId, target(serverId), workdir, command, taskName);
    }

    public BackgroundStatus pollBackground(Long serverId, String statusFileHandle) {
        return sshExecutor.pollBackground(serverId, target(serverId), statusFileHandle);
    }

    /** 异步流式执行命令（journalctl -f 等无限输出场景），stdout 按行回调 lineConsumer。 */
    public com.ops.deploy.ssh.SshExecutor.StreamHandle stream(Long serverId, String command,
            java.util.function.Consumer<String> lineConsumer, java.util.function.Consumer<Integer> onComplete) {
        return sshExecutor.stream(serverId, target(serverId), command, lineConsumer, onComplete);
    }

    /** 服务器删除/凭证变更时显式关闭缓存连接。 */
    public void close(Long serverId) {
        sshExecutor.close(serverId);
    }
}
