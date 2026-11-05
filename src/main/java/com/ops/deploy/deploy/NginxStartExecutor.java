package com.ops.deploy.deploy;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.project.ProjectSubServiceManager;
import com.ops.deploy.ssh.CommandResult;
import com.ops.deploy.ssh.SshService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 部署步骤 3（NGINX 模式）：平台托管 nginx 静态站。
 *
 * <p>免启动命令：不生成 systemd unit，而是
 * <ol>
 *   <li>确保 nginx 可用（{@link #buildEnsureCommand}）：未安装时按 apt-get → yum → dnf
 *       顺序自动安装，已装但服务未运行则先 systemctl start——目标机不要求预装 nginx；</li>
 *   <li>站点配置写入 {@code /etc/nginx/conf.d/deploy-{project}.conf}（quoted heredoc）——
 *       项目配了自定义 nginx 配置文件（file_key=nginx）则按原样下发，否则用
 *       {@link #buildDefaultNginxConf} 生成默认 server 块（root 指向
 *       {@code /opt/deploy/{project}/current} 软链、listen 部署端口缺省 80）；</li>
 *   <li>版本软链 {@code ln -sfn {workdir}/extract /opt/deploy/{project}/current}——
 *       切换版本/回滚只换软链指向，站点配置 root 不变；</li>
 *   <li>{@code nginx -t && nginx -s reload} 校验并热加载；nginx -t 失败绝不 reload。</li>
 * </ol>
 * 任一步失败抛 {@code SSH_ERROR}，由上层触发回滚（回滚动作见 {@link #buildRollbackCommand}：
 * 软链切回旧版本 extract + reload）。
 */
public class NginxStartExecutor {

    private final SshService sshService;

    public NginxStartExecutor(SshService sshService) {
        this.sshService = sshService;
    }

    /** 平台托管站点配置路径（纯函数，便于单测）。 */
    public static String confPath(String projectName) {
        return confPath(projectName, null);
    }

    /** 站点配置路径按 pathKey 分叉：默认服务（null）→ 旧口径 deploy-{project}.conf。 */
    public static String confPath(String projectName, String pathKey) {
        return ProjectSubServiceManager.nginxConfPath(projectName, pathKey);
    }

    /**
     * 确保 nginx 可用（纯函数，便于单测）：未安装时按 apt-get → yum → dnf 顺序安装；
     * 已安装但服务未运行则 systemctl start（无 systemd 的容器环境命令失败但 -t/reload 可兜底）。
     * 已安装且运行时短路退出，无副作用。
     */
    public static String buildEnsureCommand() {
        return "if ! command -v nginx >/dev/null 2>&1; then "
                + "if command -v apt-get >/dev/null 2>&1; then apt-get update -qq && apt-get install -y nginx; "
                + "elif command -v yum >/dev/null 2>&1; then yum install -y nginx; "
                + "elif command -v dnf >/dev/null 2>&1; then dnf install -y nginx; "
                + "else echo NO_PACKAGE_MANAGER >&2 && exit 127; fi; fi; "
                + "systemctl start nginx >/dev/null 2>&1 || true";
    }

    /** 站点根软链路径：所有版本共享，切换版本/回滚只需换指向（纯函数，便于单测）。 */
    public static String symlinkPath(String projectName) {
        return symlinkPath(projectName, null);
    }

    /** 软链路径按 pathKey 分叉：默认服务（null）→ 旧口径 /opt/deploy/{project}/current。 */
    public static String symlinkPath(String projectName, String pathKey) {
        return ProjectSubServiceManager.nginxSymlinkPath(projectName, pathKey);
    }

    /**
     * 生成默认站点配置（纯函数，便于单测）。root 指向 {@code /opt/deploy/{project}/current}
     * 软链（由 {@link #buildSymlinkCommand} 维护指向具体版本），故换版本无需改配置。
     */
    public static String buildDefaultNginxConf(String projectName, Integer port) {
        return buildDefaultNginxConf(projectName, null, port);
    }

    /** 默认站点配置按 pathKey 分叉：root 指向对应 pathKey 的软链。 */
    public static String buildDefaultNginxConf(String projectName, String pathKey, Integer port) {
        int listen = port == null || port <= 0 ? 80 : port;
        StringBuilder sb = new StringBuilder();
        sb.append("server {\n");
        sb.append("    listen ").append(listen).append(";\n");
        sb.append("    server_name _;\n");
        sb.append("    root ").append(symlinkPath(projectName, pathKey)).append(";\n");
        sb.append("    index index.html index.htm;\n");
        sb.append("    location / {\n");
        sb.append("        try_files $uri $uri/ =404;\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    /** 生成写站点配置的远端命令（quoted heredoc，定界符每次随机防碰撞）。纯函数便于单测。 */
    public static String buildWriteConfCommand(String confPath, String content) {
        String delim = "NGINX_EOF_" + UUID.randomUUID();
        return "mkdir -p /etc/nginx/conf.d && cat > " + ShellQuoter.quote(confPath)
                + " << '" + delim + "'\n" + content + "\n" + delim;
    }

    /**
     * 版本软链命令（纯函数，便于单测）：{@code ln -sfn} 覆盖式创建，指向新版本 extract
     * （INSTALL 步对 zip/tar.gz 解压到 {workdir}/extract）。
     */
    public static String buildSymlinkCommand(String projectName, String workdir) {
        return buildSymlinkCommand(projectName, null, workdir);
    }

    /** 软链命令按 pathKey 分叉：目标软链指向对应 pathKey 的 current。 */
    public static String buildSymlinkCommand(String projectName, String pathKey, String workdir) {
        return "ln -sfn " + ShellQuoter.quote(workdir + "/extract") + " "
                + ShellQuoter.quote(symlinkPath(projectName, pathKey));
    }

    /** 校验 + 热加载命令（纯函数，便于单测）。nginx -t 失败则 && 短路、绝不 reload。 */
    public static String buildReloadCommand() {
        return "nginx -t && nginx -s reload";
    }

    /**
     * 回滚命令（纯函数，便于单测）：软链切回旧版本 extract + reload。
     * 站点配置 root 恒指 current 软链，回滚无需重写配置。
     */
    public static String buildRollbackCommand(String projectName, String oldWorkdir) {
        return buildRollbackCommand(projectName, null, oldWorkdir);
    }

    /** 回滚命令按 pathKey 分叉：软链切回旧版本 extract（指向对应 pathKey 的 current）。 */
    public static String buildRollbackCommand(String projectName, String pathKey, String oldWorkdir) {
        return "ln -sfn " + ShellQuoter.quote(oldWorkdir + "/extract") + " "
                + ShellQuoter.quote(symlinkPath(projectName, pathKey)) + " && nginx -s reload";
    }

    /**
     * 下发站点配置（自定义优先）、切版本软链、nginx -t + reload。
     *
     * @param customConf 子服务自定义 nginx 配置（file_key=nginx）内容；null/空则用默认配置
     * @return 步骤 detail（confPath / customConf / confContent / symlink / target）；
     *         {@code confContent} 为本次实际下发的站点配置全文（自定义或默认生成），
     *         供部署方回写项目配置使其在配置列表可见可编辑
     * @throws BizException SSH_ERROR 任一步失败（附 stderr）
     */
    public Map<String, Object> execute(long serverId, String projectName, String pathKey,
                                       Integer port, String workdir, String customConf) {
        boolean custom = customConf != null && !customConf.isBlank();
        String content = custom ? customConf : buildDefaultNginxConf(projectName, pathKey, port);
        String confPath = confPath(projectName, pathKey);

        String ensureCmd = buildEnsureCommand();
        CommandResult e = sshService.execute(serverId, ensureCmd, 300_000);
        if (e.getTimedOut() || e.getExitCode() != 0) {
            throw new BizException(ErrorCode.SSH_ERROR,
                    "ensure nginx available failed (exit=" + e.getExitCode() + "): "
                            + e.getStdout() + " " + e.getStderr());
        }

        String writeCmd = buildWriteConfCommand(confPath, content);
        CommandResult w = sshService.execute(serverId, writeCmd);
        if (w.getTimedOut() || w.getExitCode() != 0) {
            throw new BizException(ErrorCode.SSH_ERROR,
                    "write nginx conf " + confPath + " failed (exit=" + w.getExitCode() + "): " + w.getStderr());
        }

        String symlinkCmd = buildSymlinkCommand(projectName, pathKey, workdir);
        CommandResult s = sshService.execute(serverId, symlinkCmd);
        if (s.getTimedOut() || s.getExitCode() != 0) {
            throw new BizException(ErrorCode.SSH_ERROR,
                    "symlink " + symlinkPath(projectName, pathKey) + " failed (exit=" + s.getExitCode() + "): " + s.getStderr());
        }

        String reloadCmd = buildReloadCommand();
        CommandResult r = sshService.execute(serverId, reloadCmd);
        if (r.getTimedOut() || r.getExitCode() != 0) {
            throw new BizException(ErrorCode.SSH_ERROR,
                    "nginx -t / reload failed (exit=" + r.getExitCode() + "): "
                            + r.getStdout() + " " + r.getStderr());
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("confPath", confPath);
        detail.put("customConf", custom);
        detail.put("confContent", content);
        detail.put("symlink", symlinkPath(projectName, pathKey));
        detail.put("target", workdir + "/extract");
        return detail;
    }
}
