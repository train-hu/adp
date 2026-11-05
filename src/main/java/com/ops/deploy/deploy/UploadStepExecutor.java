package com.ops.deploy.deploy;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.project.PackageStorage;
import com.ops.deploy.ssh.CommandResult;
import com.ops.deploy.ssh.SshService;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 部署步骤 1：流式上传部署包到远端 workdir。
 *
 * <p>流程：{@code mkdir -p workdir} → 打开本地包流 → {@link SshService#uploadStream}
 * （远端 sha256sum 与 package_version.sha256 比对，一致才落正式名）。
 * 本地不算 sha（来自 package_version 表）。失败抛 {@code BizException(SSH_ERROR)}。
 */
public class UploadStepExecutor {

    private final PackageStorage packageStorage;
    private final SshService sshService;

    public UploadStepExecutor(PackageStorage packageStorage, SshService sshService) {
        this.packageStorage = packageStorage;
        this.sshService = sshService;
    }

    /**
     * 上传部署包。
     *
     * @return 步骤 detail（sha256 / sizeBytes / durationMs）
     */
    public Map<String, Object> execute(long serverId, String workdir, String fileName,
                                       String storagePath, long sizeBytes, String sha256) {
        CommandResult mkdir = sshService.execute(serverId, "mkdir -p " + ShellQuoter.quote(workdir));
        if (mkdir.getTimedOut() || mkdir.getExitCode() != 0) {
            throw new BizException(ErrorCode.SSH_ERROR,
                    "mkdir -p " + workdir + " failed (exit=" + mkdir.getExitCode() + "): " + mkdir.getStderr());
        }
        long start = System.currentTimeMillis();
        try (InputStream in = packageStorage.open(storagePath)) {
            sshService.uploadStream(serverId, workdir, fileName, in, sizeBytes, sha256);
        } catch (IOException e) {
            throw new BizException(ErrorCode.SSH_ERROR, "upload failed: " + e.getMessage());
        }
        long durationMs = System.currentTimeMillis() - start;
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("sha256", sha256);
        detail.put("sizeBytes", sizeBytes);
        detail.put("durationMs", durationMs);
        return detail;
    }
}
