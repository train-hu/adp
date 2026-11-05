package com.ops.deploy.deploy;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.ssh.CommandResult;
import com.ops.deploy.ssh.SshService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashSet;

/**
 * 部署步骤 3（DOCKER 模式）：compose 部署。
 *
 * <p>免启动命令：不生成 systemd unit，而是把项目配置的 compose 文件（file_key=compose，
 * 必填）写入 {@code {workdir}/docker-compose.yml}，可选 .env（file_key=env）写入
 * {@code {workdir}/.env}，再执行 {@code docker-compose -f {workdir}/docker-compose.yml up -d}。
 *
 * <p>注意：目标机使用独立版 {@code docker-compose}（无 compose 插件），故命令为
 * {@code docker-compose -f ...} 而非 {@code docker compose ...}。
 *
 * <p>up -d 可能拉取镜像、耗时较长，走 {@code executeWithRetry}（300s / 2 次重试）；
 * 写文件为轻量命令走普通 {@code execute}。
 *
 * <p>回滚动作见 {@link #buildRollbackCommand}：旧版本 {@code down} 清掉同名容器后
 * {@code up} 旧版本 compose。目标机要求已安装 docker 与 docker-compose。
 */
public class DockerStartExecutor {

    /** compose up -d 的 SSH 超时（毫秒），拉镜像场景放宽到 5 分钟。 */
    private static final int UP_TIMEOUT_MS = 300_000;
    /** compose up -d 重试次数（含首次）。 */
    private static final int UP_MAX_RETRIES = 2;
    /** 宿主机端口探测（ss -lnt）的 SSH 超时（毫秒）。 */
    private static final int PROBE_TIMEOUT_MS = 10_000;
    /** 端口避让的递增扫描次数上限（从 port+1 起）。 */
    private static final int FREE_PORT_SCAN_LIMIT = 100;
    /**
     * compose ports 映射行的 host 侧端口：匹配 {@code - "8080:8080"} / {@code - 8080:8080}
     * （容器侧不动）。分组：1=dash 前缀 2=前引号 3=host 端口 4=container 端口 5=尾引号/注释。
     */
    private static final Pattern COMPOSE_PORT_LINE = Pattern.compile(
            "(?m)^([ \\t]*-[ \\t]*)(\\\")?(\\d+):(\\d+)(\\\"?[ \\t]*(?:#.*)?)$");
    /** compose build 段标识（行首缩进后 {@code build:}），用于判断 compose 是否含本地构建。 */
    static final Pattern COMPOSE_BUILD_LINE = Pattern.compile(
            "(?m)^[ \\t]+build:[ \\t]*(?:#.*)?$");
    /**
     * build 段 context 键：{@code context: .} / {@code context: "./x"}（带引号可选）。
     * 分组：1=行首至冒号后空格 2=context 值（含引号）。
     */
    static final Pattern COMPOSE_BUILD_CONTEXT_LINE = Pattern.compile(
            "(?m)^([ \\t]+context:[ \\t]*)([^\n#]+)");
    /** build 段 dockerfile 键：{@code dockerfile: Dockerfile}（带引号可选）。分组同 context。 */
    static final Pattern COMPOSE_DOCKERFILE_LINE = Pattern.compile(
            "(?m)^([ \\t]+dockerfile:[ \\t]*)([^\n#]+)");

    private static final Logger log = LoggerFactory.getLogger(DockerStartExecutor.class);

    private final SshService sshService;

    public DockerStartExecutor(SshService sshService) {
        this.sshService = sshService;
    }

    /** compose 文件远端路径（纯函数，便于单测）。 */
    public static String composePath(String workdir) {
        return workdir + "/docker-compose.yml";
    }

    /** .env 远端路径（纯函数，便于单测）。 */
    public static String envPath(String workdir) {
        return workdir + "/.env";
    }

    /** Dockerfile 远端路径（纯函数，便于单测）：构建上下文为 extract 目录。 */
    public static String dockerfilePath(String workdir) {
        return workdir + "/extract/Dockerfile";
    }

    /**
     * up -d 命令（纯函数，便于单测）。独立版 docker-compose + -f 指定 compose 路径。
     * 带 --build：compose 含 build 段时按当前 workdir 源码重建镜像（仅 build 段生效；
     * 纯 image 拉取式 compose 不受影响），避免代码/依赖变更后复用旧镜像导致 VERIFY 失败。
     */
    public static String buildUpCommand(String workdir) {
        return "docker-compose -f " + ShellQuoter.quote(composePath(workdir)) + " up -d --build";
    }

    /**
     * 手动启动实例用的 up -d（不带 --build）：实例在部署时已构建好镜像，手动"启动"
     * 只负责把容器拉起来，不应重新触发构建（慢且无必要）。构建只发生在部署流程
     * （{@link #buildUpCommand}）。纯函数便于单测。
     */
    public static String buildPlainUpCommand(String workdir) {
        return "docker-compose -f " + ShellQuoter.quote(composePath(workdir)) + " up -d";
    }

    /**
     * 回滚命令（纯函数，便于单测）：旧版本先 {@code down}（清掉该 compose 名下的容器，
     * 含可能已起来的同名新容器）再 {@code up -d}。
     */
    public static String buildRollbackCommand(String oldWorkdir) {
        String q = ShellQuoter.quote(composePath(oldWorkdir));
        return "docker-compose -f " + q + " down && docker-compose -f " + q + " up -d";
    }

    /**
     * 下发 Dockerfile（可选）/ compose / .env 并 {@code up -d}。
     *
     * @param composeContent   项目 compose 配置（file_key=compose）内容，必填
     * @param envContent       项目 .env 配置（file_key=env）内容，null/空则不写 .env
     * @param dockerfileContent 项目 Dockerfile 配置（file_key=dockerfile）内容，null/空则不写；
     *                          非空时写入 {@code {workdir}/extract/Dockerfile}（compose 用
     *                          {@code build.context: ./extract} 本地构建时必须有 Dockerfile 源）
     * @param port             宿主机端口（compose 端口映射的 host 侧）；null 时不做端口避让，
     *                          使用 compose 原样内容。compose 内容缺失端口映射（无 {@code ports} 键）
     *                          时同样不修改，由调用方自行兜底。
     * @return 步骤 detail（composePath / envWritten / dockerfileWritten / resolvedPort）；
     *         {@code resolvedPort} 为实际写入 compose 的 host 端口（compose 原端口可用即原值，
     *         被占用时为从 port+1 递增找到的第一个空闲端口，上限 100 次、65535 截断；耗尽仍占用
     *         时回落到原值让 {@code up -d} 自身报 bind 错，交由上层失败流程处理）
     * @throws BizException SSH_ERROR 写文件 / up -d 失败（附 stderr）
     */
    public Map<String, Object> execute(long serverId, String workdir,
                                       String composeContent, String envContent,
                                       String dockerfileContent, Integer port) {
        // 1. 可选写 Dockerfile（compose 走本地构建时提供构建源；buildWriteCommand 自带 mkdir -p workdir，
        //    但 extract 子目录需单独保证存在）
        boolean dockerfileWritten = false;
        if (dockerfileContent != null && !dockerfileContent.isBlank()) {
            String extractDir = ShellQuoter.quote(workdir + "/extract");
            String dfCmd = "mkdir -p " + extractDir + " && "
                    + buildWriteCommand(workdir, dockerfilePath(workdir), dockerfileContent, "DOCKERFILE_EOF_");
            CommandResult d = sshService.execute(serverId, dfCmd);
            if (d.getTimedOut() || d.getExitCode() != 0) {
                throw new BizException(ErrorCode.SSH_ERROR,
                        "write extract/Dockerfile failed (exit=" + d.getExitCode() + "): " + d.getStderr());
            }
            dockerfileWritten = true;
        }

        // 2. 写 compose（quoted heredoc，定界符随机防碰撞；compose 内容含 $ 变量时按字面量写入，
        //    交由 docker-compose 在 up 时解析 .env，符合 compose 语义）。
        //    DOCKER 模式平台托管端口：避让锚点是 compose 实际声明的 host 端口（up -d 真正要 bind 的
        //    端口），而非 deployment.port——后者可能已被上轮避让回写而与 compose 原端口不一致
        //    （如 8080→8082），只校验 deployment.port 会漏掉 compose 原端口的冲突。anchor 取
        //    两者较小值，保证被回写的目标端口也纳入占用探测；compose 无 ports 段时回落到 port。
        //    anchor 被占用则重写 compose 中 host 侧等于 anchor 的映射为空闲端口，
        //    保证 up -d 不会因宿主机端口冲突失败、健康检查端口与实际一致。
        //    写盘前先做 build context 规范化：上传 compose 若以 workdir 根为构建上下文
        //    （context: .）而源码实际在 extract/ 下（Dockerfile 不在根），构建必然失败
        //    （buildkit 按 0 字节读缺失的 Dockerfile）；此时把 context 改指 ./extract。
        String effectiveCompose = normalizeBuildContext(workdir, composeContent, serverId);
        Integer resolvedPort = port;
        if (port != null) {
            Set<Integer> busy = probeBusyPorts(serverId);
            Integer composeHost = extractHostPort(composeContent);
            int anchor = composeHost != null ? Math.min(composeHost, port) : port;
            Integer target = resolvePort(anchor, busy);
            if (target.equals(anchor)) {
                resolvedPort = anchor;
            } else {
                log.info("host port {} occupied on server {}, auto-avoided to {}", anchor, serverId, target);
                String rewritten = rewriteComposePort(composeContent, anchor, target);
                if (rewritten != null) {
                    effectiveCompose = rewritten;
                    resolvedPort = target;
                } else {
                    // compose 中找不到 host 侧为 anchor 的映射（无 ports 段或端口行带 IP/协议等
                    // 无法安全识别的形式）：保持原样，resolvedPort 回落为 anchor（compose 未变），
                    // up -d 若因端口冲突失败由上层失败流程处理。
                    log.warn("compose of server {} has no recognizable port mapping for host port {}, keep original",
                            serverId, anchor);
                }
            }
        }
        String composeCmd = buildWriteCommand(workdir, composePath(workdir), effectiveCompose, "COMPOSE_EOF_");
        CommandResult w = sshService.execute(serverId, composeCmd);
        if (w.getTimedOut() || w.getExitCode() != 0) {
            throw new BizException(ErrorCode.SSH_ERROR,
                    "write docker-compose.yml failed (exit=" + w.getExitCode() + "): " + w.getStderr());
        }

        // 3. 可选写 .env
        boolean envWritten = false;
        if (envContent != null && !envContent.isBlank()) {
            String envCmd = buildWriteCommand(workdir, envPath(workdir), envContent, "ENV_EOF_");
            CommandResult e = sshService.execute(serverId, envCmd);
            if (e.getTimedOut() || e.getExitCode() != 0) {
                throw new BizException(ErrorCode.SSH_ERROR,
                        "write .env failed (exit=" + e.getExitCode() + "): " + e.getStderr());
            }
            envWritten = true;
        }

        // 4. up -d（拉镜像较慢，走 executeWithRetry）
        String upCmd = buildUpCommand(workdir);
        CommandResult u = sshService.executeWithRetry(serverId, upCmd, UP_TIMEOUT_MS, UP_MAX_RETRIES);
        if (u.getTimedOut() || u.getExitCode() != 0) {
            String reason = u.getTimedOut() ? "timeout" : "exit=" + u.getExitCode();
            String output = tailOf(u.getStdout(), u.getStderr(), 800);
            throw new BizException(ErrorCode.SSH_ERROR,
                    "docker-compose up -d failed (" + reason + "): " + output);
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("composePath", composePath(workdir));
        detail.put("envWritten", envWritten);
        detail.put("dockerfileWritten", dockerfileWritten);
        detail.put("resolvedPort", resolvedPort);
        return detail;
    }

    /**
     * 规范化 compose 的 build context（写盘前调用）。
     *
     * <p>平台布局：上传包解压到 {@code {workdir}/extract/}，compose 写到 {@code {workdir}/docker-compose.yml}，
     * 平台托管的 Dockerfile（若有）写到 {@code {workdir}/extract/Dockerfile}。
     * 用户自带的 compose 若声明 {@code build.context: .}（workdir 根）而根目录并无 Dockerfile
     * （源码都在 extract/），构建时 buildkit 读不到 Dockerfile 必然失败（按 0 字节传输报
     * "transferring dockerfile: 2B"）。
     *
     * <p>规则：build 段存在、context 为根（{@code .}/{@code ./}/{@code .\/}）、根目录无 Dockerfile
     * 且 extract/ 存在时，把 context 改写为 {@code ./extract}；若同时声明 {@code dockerfile: <相对路径>}
     * （非绝对、非 extract 前缀），同步加 {@code extract/} 前缀，保持"上下文根 + 相对路径"语义不变。
     * 其余情况（context 已是 ./extract、根目录本就有 Dockerfile、无 build 段、context 为其他相对路径）
     * 一律原样返回——不猜测、不破坏用户显式配置。
     *
     * @param workdir    部署工作目录（绝对路径）
     * @param compose    原始 compose 内容
     * @param serverId   目标服务器（用于探测根目录 / extract 是否存在）
     * @return 规范化后的 compose（未触发改写时与原串相同）
     */
    String normalizeBuildContext(String workdir, String compose, long serverId) {
        if (compose == null || compose.isBlank()) {
            return compose;
        }
        if (!hasBuildSection(compose)) {
            return compose;
        }
        String context = extractBuildContext(compose);
        if (context == null || !isRootContext(context)) {
            return compose;
        }
        // 根目录已有 Dockerfile：用户 compose 与平台布局一致，无需改写
        if (pathExists(serverId, workdir + "/Dockerfile")) {
            return compose;
        }
        if (!pathExists(serverId, workdir + "/extract")) {
            // extract 不存在（异常状态）：保持原样让 up -d 自身报错，交由上层失败流程
            return compose;
        }
        String rewritten = rewriteBuildContext(compose, "./extract");
        if (rewritten != null) {
            log.info("normalized compose build context from root to ./extract (workdir={})", workdir);
        }
        return rewritten != null ? rewritten : compose;
    }

    /** compose 是否含至少一个 build 段（粗判：行首缩进后的 {@code build:}）。 */
    static boolean hasBuildSection(String compose) {
        return COMPOSE_BUILD_LINE.matcher(compose).find();
    }

    /**
     * 提取首个 build 段的 context 值（{@code context: .} → {@code .}）。无 build.context 时返回 null。
     * 兼容 {@code context: .} / {@code context: "./"} / 带引号形式。
     */
    static String extractBuildContext(String compose) {
        Matcher m = COMPOSE_BUILD_CONTEXT_LINE.matcher(compose);
        if (!m.find()) {
            return null;
        }
        String v = m.group(2).trim();
        // 去掉成对的引号
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\""))
                || (v.startsWith("'") && v.endsWith("'")))) {
            v = v.substring(1, v.length() - 1);
        }
        return v.isEmpty() ? null : v;
    }

    /** context 是否指 build 上下文根（{@code .}/{@code ./}，带引号已在提取时去掉）。 */
    static boolean isRootContext(String context) {
        return ".".equals(context) || "./".equals(context);
    }

    /**
     * 把首个 build 段的 {@code context:} 值改写为 {@code newContext}，并对同段 {@code dockerfile:}
     * 的相对路径加 {@code extract/} 前缀（若尚未有）。找不到可改写的 context 行时返回 null。
     */
    static String rewriteBuildContext(String compose, String newContext) {
        Matcher m = COMPOSE_BUILD_CONTEXT_LINE.matcher(compose);
        if (!m.find()) {
            return null;
        }
        // 先定位 dockerfile 键（原始串上的位置），再改 context；replaceFirst 顺序保证前缀不互相污染
        Matcher df = COMPOSE_DOCKERFILE_LINE.matcher(compose);
        String dfPrefix = null;
        if (df.find()) {
            String v = df.group(2).trim();
            String unquoted = v;
            if (unquoted.length() >= 2 && ((unquoted.startsWith("\"") && unquoted.endsWith("\""))
                    || (unquoted.startsWith("'") && unquoted.endsWith("'")))) {
                unquoted = unquoted.substring(1, unquoted.length() - 1);
            }
            if (!unquoted.isEmpty() && !unquoted.startsWith("/")
                    && !unquoted.equals("extract") && !unquoted.startsWith("extract/")) {
                dfPrefix = df.group(1) + "extract/" + unquoted;
            }
        }
        String replaced = compose.substring(0, m.start()) + m.group(1) + newContext
                + compose.substring(m.end());
        if (dfPrefix != null) {
            // context 行在 dockerfile 行之前的常规布局下，行长度变化不影响按行改写
            Matcher df2 = COMPOSE_DOCKERFILE_LINE.matcher(replaced);
            if (df2.find()) {
                replaced = replaced.substring(0, df2.start()) + dfPrefix + replaced.substring(df2.end());
            }
        }
        return replaced;
    }

    /** 远端路径是否存在（SSH 测试，尽力而为：SSH 失败视为不存在）。 */
    boolean pathExists(long serverId, String path) {
        try {
            CommandResult r = sshService.execute(serverId,
                    "test -e " + ShellQuoter.quote(path) + " && echo EXISTS || echo MISSING", PROBE_TIMEOUT_MS);
            return !r.getTimedOut() && r.getExitCode() == 0
                    && r.getStdout() != null && r.getStdout().contains("EXISTS");
        } catch (RuntimeException e) {
            log.warn("probe path {} on server {} failed (ssh): {}", path, serverId, e.getMessage());
            return false;
        }
    }

    /**
     * 探测目标机当前监听的端口集合（{@code ss -lnt}）。尽力而为：SSH 失败 / 超时 / 命令
     * 非 0 返回空集（不阻断部署，端口冲突由 {@code up -d} 的 bind 报错兜底）。
     */
    Set<Integer> probeBusyPorts(long serverId) {
        Set<Integer> busy = new HashSet<>();
        CommandResult r;
        try {
            r = sshService.execute(serverId, "ss -lnt", PROBE_TIMEOUT_MS);
        } catch (RuntimeException e) {
            log.warn("probe busy ports on server {} failed (ssh): {}", serverId, e.getMessage());
            return busy;
        }
        if (r.getTimedOut() || r.getExitCode() != 0) {
            log.warn("probe busy ports on server {} failed (exit={}, timeout={})",
                    serverId, r.getExitCode(), r.getTimedOut());
            return busy;
        }
        String stdout = r.getStdout();
        if (stdout == null) {
            return busy;
        }
        for (String line : stdout.split("\\R")) {
            String s = line.trim();
            if (s.isEmpty() || !s.toUpperCase().startsWith("LISTEN")) {
                continue;
            }
            // ss 输出列：State Recv-Q Send-Q Local-Address:Port ...，取第 4 列 Local
            String[] cols = s.split("\\s+");
            if (cols.length < 4) {
                continue;
            }
            String local = cols[3];
            int ci = local.lastIndexOf(':');
            if (ci < 0 || ci == local.length() - 1) {
                continue;
            }
            try {
                busy.add(Integer.parseInt(local.substring(ci + 1)));
            } catch (NumberFormatException ignored) {
                // 非数字端口（如 unix socket 行），忽略
            }
        }
        return busy;
    }

    /**
     * 解析实际可用的宿主机端口：部署端口空闲返回原值；被占用则从 {@code port+1} 递增最多
     * {@link #FREE_PORT_SCAN_LIMIT} 次找第一个空闲端口（超过 65535 截断）；扫描耗尽仍无
     * 空闲端口时回落到原值，交由 {@code up -d} 的 bind 报错暴露冲突。
     */
    static Integer resolvePort(Integer port, Set<Integer> busy) {
        if (port == null || !busy.contains(port)) {
            return port;
        }
        for (int i = port + 1; i <= 65535 && i < port + FREE_PORT_SCAN_LIMIT; i++) {
            if (!busy.contains(i)) {
                return i;
            }
        }
        return port;
    }

    /**
     * 将 compose ports 映射行中 host 侧等于 {@code oldHostPort} 的端口重写为 {@code newHostPort}
     * （容器侧端口不动）。支持 {@code - "8080:8080"} / {@code - 8080:8080} 形式（行尾注释保留）；
     * 找不到任何 host 侧为 {@code oldHostPort} 的映射时返回 null（调用方保持原内容）。
     */
    static String rewriteComposePort(String content, int oldHostPort, int newHostPort) {
        if (content == null || oldHostPort == newHostPort) {
            return null;
        }
        Matcher m = COMPOSE_PORT_LINE.matcher(content);
        StringBuilder sb = new StringBuilder();
        boolean changed = false;
        while (m.find()) {
            int hostPort = Integer.parseInt(m.group(3));
            if (hostPort != oldHostPort) {
                continue;
            }
            String quote = m.group(2) == null ? "" : m.group(2);
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    m.group(1) + quote + newHostPort + ":" + m.group(4) + m.group(5)));
            changed = true;
        }
        m.appendTail(sb);
        return changed ? sb.toString() : null;
    }

    /**
     * 提取 compose ports 映射中首个 host 侧端口（{@code - "8080:8080"} 的 8080）。
     * 无 ports 映射或无法识别时返回 null（调用方回落到 deployment.port 作为避让锚点）。
     */
    static Integer extractHostPort(String content) {
        if (content == null) {
            return null;
        }
        Matcher m = COMPOSE_PORT_LINE.matcher(content);
        while (m.find()) {
            try {
                return Integer.parseInt(m.group(3));
            } catch (NumberFormatException ignored) {
                // 理论上正则已限定 \d+，防御性忽略
            }
        }
        return null;
    }

    /** 生成写远端文件的 quoted heredoc 命令（定界符前缀 + 随机 UUID，防内容碰撞）。纯函数便于单测。 */
    static String buildWriteCommand(String workdir, String path, String content, String delimPrefix) {
        String delim = delimPrefix + UUID.randomUUID();
        return "mkdir -p " + ShellQuoter.quote(workdir) + " && cat > "
                + ShellQuoter.quote(path) + " << '" + delim + "'\n" + content + "\n" + delim;
    }

    /**
     * 合并 stdout + stderr 取末尾若干字符。docker 的 pull 进度与失败错误主要走 stdout，
     * 且失败信息（如 "Error Get ... context deadline exceeded"）出现在输出末尾；
     * 因 error_summary 截断保留头部（见 {@code DeploymentRunner#truncate}），故取尾部并置于
     * 错误消息最前，确保关键失败信息不被截掉。
     */
    static String tailOf(String stdout, String stderr, int max) {
        String combined = (stdout == null ? "" : stdout) + (stderr == null ? "" : stderr);
        if (combined.isBlank()) {
            return "";
        }
        String s = combined.trim();
        return s.length() <= max ? s : s.substring(s.length() - max);
    }
}
