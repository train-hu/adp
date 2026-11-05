package com.ops.deploy.deploy;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.project.ProjectConfigFileEntity;
import com.ops.deploy.ssh.CommandResult;
import com.ops.deploy.ssh.SshService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * PROCESS / DOCKER 模式包内文件覆盖执行器（方案 C）：把项目配置中"包内文件"型
 * fileKey（非 4 个平台特殊 key，如 {@code conf/config.toml} 或根级 {@code requirements.txt}）
 * 的条目，按该相对路径写入 {@code {extract}/{relPath}}，实现"包内配置文件按环境覆盖"。
 *
 * <p>4 个特殊 key（nginx / compose / env / dockerfile）由各自执行器按原逻辑下发，
 * 不在本执行器处理范围（{@link #isPathKey} 过滤）。
 *
 * <p>时机：在 INSTALL 解压完成后、START 启动前调用（解压产物在 {@code {workdir}/extract}，
 * 由 {@link InstallStepExecutor#resolveRuntimeDir} 保证）；DOCKER 模式下覆盖内容随
 * {@code build.context: ./extract} 进入 {@code --build} 构建上下文，使镜像依赖/配置与包同步。
 *
 * <p>写文件走 quoted heredoc（定界符随机防碰撞），单个文件失败即抛 {@code SSH_ERROR}
 * （配置覆盖是部署语义的一部分，写失败不应静默继续）。
 */
public class ProcessConfigOverwriteExecutor {

    private static final Logger log = LoggerFactory.getLogger(ProcessConfigOverwriteExecutor.class);

    /** 平台特殊配置 key：由 nginx/compose/env/dockerfile 执行器按原逻辑下发，不参与包内覆盖。 */
    private static final java.util.Set<String> SPECIAL_KEYS =
            java.util.Set.of("nginx", "compose", "env", "dockerfile");

    private final SshService sshService;

    public ProcessConfigOverwriteExecutor(SshService sshService) {
        this.sshService = sshService;
    }

    /**
     * 判定 fileKey 是否为"包内文件"型配置 key：非空且不属于 4 个平台特殊 key 即为覆盖目标。
     *
     * <p>覆盖目标既可以是子目录相对路径（{@code conf/config.toml}），也可以是包根级文件
     * （{@code requirements.txt} / {@code package.json}）——后者不含 {@code /}，早期"含 {@code /}"
     * 的判定会漏掉它们（szhmh 缺陷回归：requirements.txt 改了 PyJWT 却没进镜像）。
     */
    public static boolean isPathKey(String fileKey) {
        return fileKey != null && !fileKey.isBlank() && !SPECIAL_KEYS.contains(fileKey);
    }

    /** 从项目配置条目中筛出包内文件型配置（保持原顺序）。 */
    public static List<ProjectConfigFileEntity> pathKeys(List<ProjectConfigFileEntity> configs) {
        if (configs == null) {
            return List.of();
        }
        return configs.stream()
                .filter(e -> isPathKey(e.getFileKey()))
                .collect(Collectors.toList());
    }

    /** 覆盖写入的远端目标路径（纯函数，便于单测）：{@code {extract}/{relPath}}。 */
    public static String targetPath(String workdir, String relPath) {
        String base = workdir.endsWith("/") ? workdir.substring(0, workdir.length() - 1) : workdir;
        return base + "/extract/" + relPath;
    }

    /** 生成写远端文件的 quoted heredoc 命令（先建父目录，定界符随机防碰撞）。纯函数便于单测。 */
    public static String buildWriteCommand(String workdir, String relPath, String content) {
        String target = targetPath(workdir, relPath);
        String delim = "CFG_EOF_" + UUID.randomUUID();
        // mkdir -p 父目录（relPath 可能含多级目录，如 conf/plugins/foo.toml）
        int lastSlash = target.lastIndexOf('/');
        String parent = lastSlash > 0 ? target.substring(0, lastSlash) : "";
        return "mkdir -p " + ShellQuoter.quote(parent) + " && cat > "
                + ShellQuoter.quote(target) + " << '" + delim + "'\n" + content + "\n" + delim;
    }

    /**
     * 把所有相对路径型配置按 {@code {extract}/{relPath}} 覆盖写入。
     *
     * @param serverId 目标机
     * @param workdir  部署工作目录（解压产物在其 {@code extract} 子目录）
     * @param configs  项目全部配置条目（内部自动筛出相对路径型）
     * @return 步骤 detail（written 列表 / count）
     * @throws BizException SSH_ERROR 当任一文件写入失败
     */
    public Map<String, Object> execute(long serverId, String workdir,
                                       List<ProjectConfigFileEntity> configs) {
        List<ProjectConfigFileEntity> pathConfigs = pathKeys(configs);
        List<String> written = new java.util.ArrayList<>();
        for (ProjectConfigFileEntity e : pathConfigs) {
            String cmd = buildWriteCommand(workdir, e.getFileKey(), e.getContent());
            CommandResult r = sshService.execute(serverId, cmd);
            if (r.getTimedOut() || r.getExitCode() != 0) {
                throw new BizException(ErrorCode.SSH_ERROR,
                        "overwrite config " + e.getFileKey() + " failed (exit=" + r.getExitCode()
                                + "): " + r.getStderr());
            }
            written.add(e.getFileKey());
            log.info("overwrote package config {} on server {} at extract/{}",
                    e.getFileKey(), serverId, e.getFileKey());
        }
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("written", written);
        detail.put("count", written.size());
        return detail;
    }
}
