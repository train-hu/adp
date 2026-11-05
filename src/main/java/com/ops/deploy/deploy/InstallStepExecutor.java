package com.ops.deploy.deploy;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.ssh.CommandResult;
import com.ops.deploy.ssh.SshService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 部署步骤 2：解压 / 安装部署包（按文件后缀）。
 *
 * <p>命令构造（{@link #buildInstallCommand} 纯函数，单测覆盖 5 种后缀 + 不支持）：
 * <ul>
 *   <li>{@code .zip} → {@code unzip -o -q {file} -d {workdir}/extract} + 单根目录拍平</li>
 *   <li>{@code .tar.gz} / {@code .tgz} → {@code mkdir -p {workdir}/extract && tar xzf {file} -C {workdir}/extract} + 单根目录拍平（tar -C 不建目录，须先 mkdir -p）</li>
 *   <li>{@code .jar} → {@code mv {file} {workdir}/app.jar}</li>
 *   <li>{@code .html} / {@code .htm} → {@code mkdir -p {workdir}/extract && mv {file} {workdir}/extract/index.html}（裸静态页，重命名为 index 使站点根 / 可访问）</li>
 *   <li>其他后缀 → 抛 {@code BizException(BAD_REQUEST, "unsupported package format ...")}</li>
 * </ul>
 * 归档包解压后若 {@code extract} 下仅有一个子目录（zip/tar 常见的单根包装目录，如 {@code myapp/...}），
 * 由 {@link #buildFlattenCommand} 拍平上移一层，保证 {@code extract} 恒为站点根——
 * NGINX 模式软链、实例启停、回滚都只认 {@code extract}，无需感知包内是否带包装目录。
 * 执行走 {@link SshService#executeWithRetry}（300s / 2 次重试）；
 * {@code exitCode != 0} 或 {@code timedOut} → 抛 {@code BizException(SSH_ERROR)}。
 */
public class InstallStepExecutor {

    /** 解压 / 安装命令超时（毫秒）。 */
    private static final int TIMEOUT_MS = 300_000;
    /** 重试次数（幂等解压可重试）。 */
    private static final int MAX_RETRIES = 2;

    private final SshService sshService;

    public InstallStepExecutor(SshService sshService) {
        this.sshService = sshService;
    }

    /**
     * 构造安装命令（纯函数，便于单测）。所有路径经 {@link ShellQuoter} 转义。
     *
     * @throws BizException BAD_REQUEST 当后缀不受支持
     */
    public static String buildInstallCommand(String fileName, String workdir) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        String file = ShellQuoter.quote(workdir + "/" + fileName);
        String extract = ShellQuoter.quote(workdir + "/extract");
        if (lower.endsWith(".zip")) {
            return "unzip -o -q " + file + " -d " + extract + " && " + buildFlattenCommand(workdir);
        }
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
            // tar -C 不会创建不存在的目录，首次部署时 extract 不存在会直接 exit=2，需先 mkdir -p
            return "mkdir -p " + extract + " && tar xzf " + file + " -C " + extract + " && " + buildFlattenCommand(workdir);
        }
        if (lower.endsWith(".jar")) {
            return "mv " + file + " " + ShellQuoter.quote(workdir + "/app.jar");
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "mkdir -p " + extract + " && mv " + file + " " + ShellQuoter.quote(workdir + "/extract/index.html");
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "unsupported package format: " + fileName);
    }

    /**
     * 解析 PROCESS 模式 systemd 的 {@code WorkingDirectory}（与 install 落盘分支严格对齐，纯函数便于单测）。
     *
     * <p>{@code .jar} 包 install 后是 {@code mv} 到 workdir 根（{@code app.jar}），运行目录为 workdir 根；
     * 其余类型（zip / tar.gz / tgz / html）内容解压到 {@code workdir/extract}，运行目录为 extract。
     * 识别错目录会让相对路径启动命令（{@code python3 app.py} / {@code ./bin/categraf}）找不到文件。
     *
     * @return jar/war → {@code workdir}；其余（含 fileName 为 null/无法识别）→ {@code workdir/extract}
     */
    public static String resolveRuntimeDir(String workdir, String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        if (lower.endsWith(".jar") || lower.endsWith(".war")) {
            return workdir;
        }
        return workdir + "/extract";
    }

    /**
     * 归档包解压后拍平"单根包装目录"（POSIX sh：无单引号、无 bashism，可直接经 SSH exec 通道执行）。
     *
     * <p>zip/tar 常把整个站点打进一个包装目录（如 {@code myapp/index.html}）。解压后若
     * {@code extract} 下恰好只有一个条目且为目录，则把其内容（含隐藏文件）上移一层并删空目录，
     * 使 {@code extract} 恒为站点根——NGINX 软链、实例启停、回滚都只认 {@code extract}，
     * 无需感知包内是否带包装目录。条目数不为 1、或唯一条目非目录时原样保留（退出码 0）。
     */
    public static String buildFlattenCommand(String workdir) {
        return "cd " + ShellQuoter.quote(workdir + "/extract")
                + " && if [ \"$(find . -mindepth 1 -maxdepth 1 | wc -l)\" -eq 1 ]; then "
                + "d=$(find . -mindepth 1 -maxdepth 1); "
                + "if [ -d \"$d\" ]; then find \"$d\" -mindepth 1 -maxdepth 1 -exec mv -f {} . \\; && rmdir \"$d\"; fi; "
                + "fi";
    }

    /**
     * 执行安装命令。
     *
     * @return 步骤 detail（command 的截断描述 / durationMs）
     */
    public Map<String, Object> execute(long serverId, String workdir, String fileName) {
        String command = buildInstallCommand(fileName, workdir);
        CommandResult r = sshService.executeWithRetry(serverId, command, TIMEOUT_MS, MAX_RETRIES);
        if (r.getTimedOut() || r.getExitCode() != 0) {
            String reason = r.getTimedOut() ? "timeout after " + TIMEOUT_MS + "ms"
                    : "exit=" + r.getExitCode();
            throw new BizException(ErrorCode.SSH_ERROR,
                    "install failed (" + reason + "): " + r.getStderr());
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("command", command);
        detail.put("durationMs", r.getDurationMs());
        return detail;
    }
}
