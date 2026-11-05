package com.ops.deploy.deploy;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.ssh.CommandResult;
import com.ops.deploy.ssh.SshService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 部署步骤 3：生成 systemd unit 并启动服务。
 *
 * <p>流程：
 * <ol>
 *   <li>{@link #buildUnitContent}（纯函数）生成 unit 文件文本，用 quoted heredoc 写入
 *       {@code /etc/systemd/system/{unitName}}（heredoc 定界符带引号 → 内容按字面量写入，安全）；</li>
 *   <li>执行 {@code systemctl daemon-reload && systemctl enable {unit} && systemctl restart {unit}}
 *       —— 必须用 {@code restart} 而非 {@code enable --now}：对"已 active"的 unit，{@code enable --now}
 *       中的 start 分支对已 active 单元是 no-op、不会重启，进程仍跑旧配置（缺陷10：E2E 实锤 v2 部署时
 *       稳定性校验采到的是仍在运行的旧 v1 进程 → 假 SUCCESS）。{@code enable} 保证开机自启，{@code restart}
 *       保证进程一定被替换为新配置；</li>
 *   <li>启动失败时附带 {@code systemctl status {unit}} 输出，便于排查；</li>
 *   <li>稳定性校验：连续 N 次采样 {@code systemctl show -p SubState,-p MainPID}，要求 SubState 持续为
 *       {@code running} 且 MainPID 稳定（两次非 0 采样一致）。端口被外部服务占用时新进程 bind 失败会在
 *       {@code Restart=on-failure} 下崩溃循环（SubState 大部分时间 dead/auto-restart，MainPID 反复变化/为 0），
 *       而健康检查 curl 会被占用端口的外部服务 2xx 欺骗——仅靠"启动命令返回 0 + curl 2xx"会误判部署成功。
 *       此校验失败抛 {@code SSH_ERROR}，由上层触发回滚。</li>
 * </ol>
 * unitName 形如 {@code deploy-{projectName}.service}。
 */
public class StartStepExecutor {

    private final SshService sshService;
    /** 稳定性校验连续采样次数（须均为 running）。 */
    private final int stableChecks;
    /** 稳定性校验采样间隔（毫秒）；0 表示不等待（单测用）。 */
    private final long stableIntervalMs;
    /**
     * 启动命令冒烟校验时长（秒）：装 unit 前用 {@code timeout <N>s} 试跑一遍候选启动命令，
     * 快速非 0 退出（如 flag 错误 exit=2 / 找不到可执行文件 exit=127 / 缺依赖 exit=1）立即判失败
     * 并携带 stderr——把坏命令挡在装 unit 之前，避免留下 {@code Restart=on-failure} 的崩溃循环 unit
     * （线上 categraf 把 {@code --configs} 打成 {@code --config} 后 unit 反复重启 140+ 次的实证）。
     * 0 表示跳过冒烟校验（单测用）。超时（124）说明进程仍在跑（长驻守护进程），视为通过、交给稳定性校验兜底。
     */
    private final int smokeTestSeconds;

    public StartStepExecutor(SshService sshService, int stableChecks, long stableIntervalMs,
                             int smokeTestSeconds) {
        this.sshService = sshService;
        this.stableChecks = Math.max(1, stableChecks);
        this.stableIntervalMs = Math.max(0, stableIntervalMs);
        this.smokeTestSeconds = Math.max(0, smokeTestSeconds);
    }

    /**
     * 生成 systemd unit 文件内容（纯函数，便于单测）。
     * 值原样写入 unit 文件（unit 文件由 systemd 解析，不经 shell，故不做 shell 转义）。
     *
     * <p>{@code workingDir} 是 <b>运行目录</b>（不是部署 workdir 根）：jar/war 包 install 后落
     * workdir 根，其余包内容在 {@code {workdir}/extract}，由
     * {@link InstallStepExecutor#resolveRuntimeDir(String, String)} 解析。startCmd 常为相对路径
     * （{@code python3 app.py} / {@code ./bin/categraf}），WorkingDirectory 指错目录会导致启动失败。
     *
     * <p>startCmd 可能带 shell 风格的内联环境变量前缀（如 {@code PORT=8001 /usr/bin/python3 app.py}），
     * 但 systemd 的 {@code ExecStart} 不接受这种写法——它会把 {@code PORT=8001} 当作可执行文件名，
     * 报 {@code Executable "PORT=8001" not found in path}。因此把 startCmd 前导的 {@code KEY=VALUE}
     * 提取为 {@code Environment=} 指令，剩余部分才写入 {@code ExecStart=}。
     */
    public static String buildUnitContent(String unitName, String workingDir, String startCmd) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Unit]\n");
        sb.append("Description=").append(unitName).append('\n');
        sb.append("After=network.target\n\n");
        sb.append("[Service]\n");
        sb.append("Type=simple\n");
        sb.append("WorkingDirectory=").append(workingDir).append('\n');
        String[] envAndCmd = splitInlineEnv(startCmd);
        for (String env : envAndCmd[0].split("\u0000", -1)) {
            if (!env.isEmpty()) {
                sb.append("Environment=").append(env).append('\n');
            }
        }
        sb.append("ExecStart=").append(absolutizeExecStart(workingDir, envAndCmd[1])).append('\n');
        sb.append("Restart=on-failure\n");
        sb.append("RestartSec=5\n\n");
        sb.append("[Install]\n");
        sb.append("WantedBy=multi-user.target\n");
        return sb.toString();
    }

    /**
     * 把 startCmd 前导的 {@code KEY=VALUE} 内联环境变量拆出（systemd ExecStart 不支持内联 env）。
     * 只识别"前导连续"的 KEY=VALUE：遇到第一个不符合的 token 即视为命令开始。
     *
     * @return String[2]：[0]=以 {@code \u0000} 分隔的环境变量列表（可能为空串），[1]=剩余命令
     */
    static String[] splitInlineEnv(String startCmd) {
        String s = startCmd == null ? "" : startCmd.trim();
        if (s.isEmpty()) {
            return new String[]{"", ""};
        }
        String[] tokens = s.split("\\s+");
        StringBuilder envs = new StringBuilder();
        int i = 0;
        for (; i < tokens.length; i++) {
            if (isEnvToken(tokens[i])) {
                if (envs.length() > 0) {
                    envs.append('\u0000');
                }
                envs.append(tokens[i]);
            } else {
                break;
            }
        }
        StringBuilder cmd = new StringBuilder();
        for (; i < tokens.length; i++) {
            if (cmd.length() > 0) {
                cmd.append(' ');
            }
            cmd.append(tokens[i]);
        }
        return new String[]{envs.toString(), cmd.toString()};
    }

    /** 判定 token 是否为合法的内联环境变量（{@code KEY=VALUE}，KEY 为标识符、VALUE 非空且不含空白）。 */
    private static boolean isEnvToken(String tok) {
        int eq = tok.indexOf('=');
        if (eq <= 0) {
            return false;
        }
        for (int i = 0; i < eq; i++) {
            char c = tok.charAt(i);
            boolean ok = c == '_'
                    || (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (i > 0 && c >= '0' && c <= '9');
            if (!ok) {
                return false;
            }
        }
        return eq + 1 < tok.length();
    }

    /**
     * 把 ExecStart 的可执行文件路径转为绝对路径（systemd 的 ExecStart <b>不接受相对路径</b>：
     * {@code ./categraf} 会被判为 "Neither a valid executable name nor an absolute path"，
     * unit 加载即 bad-setting 无法启动；bare 命令如 {@code java} / {@code python3} 由 systemd
     * 沿 PATH 查找，本身合法，不得改动）。
     *
     * <p>规则：仅当第一个 token 是相对路径（以 {@code ./} 或 {@code ../} 开头）时，拼到
     * {@code workingDir} 下；其余情况（绝对路径 / bare 命令 / 空命令）原样返回。参数中形如
     * {@code --configs ./conf} 的相对路径不经 systemd 解析，由进程自身配合 WorkingDirectory 处理，
     * 保持原样。
     */
    static String absolutizeExecStart(String workingDir, String cmd) {
        String c = cmd == null ? "" : cmd.trim();
        if (c.isEmpty()) {
            return c;
        }
        int sp = c.indexOf(' ');
        String first = sp < 0 ? c : c.substring(0, sp);
        if (first.startsWith("./") || first.startsWith("../")) {
            String rest = sp < 0 ? "" : c.substring(sp);
            String base = workingDir == null ? "" : workingDir.endsWith("/")
                    ? workingDir.substring(0, workingDir.length() - 1)
                    : workingDir;
            return base + "/" + first + rest;
        }
        return c;
    }

    /**
     * 生成启动命令冒烟校验的远端命令（纯函数，便于单测）。
     *
     * <p>形态：{@code cd <workingDir> && env <ENV...> timeout <N>s <cmd> 2>&1}。
     * <ul>
     *   <li>{@code cd <workingDir>} 复刻 unit 的 {@code WorkingDirectory}，使 startCmd 中的相对路径
     *       （{@code ./bin/categraf} / {@code app.py}）与 systemd 运行时一致；</li>
     *   <li>{@code env <ENV...>} 复刻 unit 的 {@code Environment=}（startCmd 前导内联 env 由
     *       {@link #splitInlineEnv} 拆出），让进程读到与 unit 相同的环境；</li>
     *   <li>{@code timeout <N>s} 限定冒烟时长：长驻守护进程到点被 SIGTERM → 退出码 124（"活着"，视为通过）；
     *       快速非 0 退出（flag 错误 exit=2 / 找不到可执行文件 exit=127 / 缺依赖 exit=1）则原样返回；</li>
     *   <li>{@code 2>&1} 合并 stderr，保证 "flag provided but not defined" 这类报错进入 stdout 便于上报。</li>
     * </ul>
     * 冒烟命令<b>不复用</b> {@link #absolutizeExecStart}：那一步是为 systemd ExecStart（必须绝对路径）服务的，
     * 而这里走 shell（{@code cd} 后相对路径即可），保持 startCmd 原样最贴近 unit 实际执行语义。
     */
    static String buildSmokeTestCommand(String workingDir, int timeoutSeconds, String startCmd) {
        String[] envAndCmd = splitInlineEnv(startCmd);
        String cmd = envAndCmd[1];
        String base = workingDir == null ? "" : workingDir.endsWith("/")
                ? workingDir.substring(0, workingDir.length() - 1)
                : workingDir;
        StringBuilder sb = new StringBuilder();
        sb.append("cd ").append(base).append(" && ");
        String[] envs = envAndCmd[0].split("\u0000", -1);
        if (envs.length > 0) {
            StringBuilder envPart = new StringBuilder("env ");
            for (String env : envs) {
                if (!env.isEmpty()) {
                    if (envPart.length() > 4) {
                        envPart.append(' ');
                    }
                    envPart.append(env);
                }
            }
            if (envPart.length() > 4) {
                sb.append(envPart).append(' ');
            }
        }
        sb.append("timeout ").append(timeoutSeconds).append('s').append(' ')
                .append(cmd).append(" 2>&1");
        return sb.toString();
    }

    /**
     * 执行启动命令冒烟校验（装 unit 之前）。仅当 {@code smokeTestSeconds > 0} 且 startCmd 非空时执行。
     *
     * <p>判定：
     * <ul>
     *   <li>退出码 0（命令主动正常退出，如批处理）→ 通过；</li>
     *   <li>退出码 124 / 125（{@code timeout} 超时或 timeout 自身用法错误）→ 进程仍在跑，视为通过，
     *       交给后续 systemd 稳定性校验兜底（长驻守护进程本就不会主动退出）；</li>
     *   <li>其他非 0 退出码 → 命令无效，抛 {@code SSH_ERROR} 并携带 stderr（含 "flag provided but not defined"
     *       等具体原因），<b>此时尚未写 unit</b>，不会留下崩溃循环单元。</li>
     * </ul>
     */
    private void runSmokeTest(long serverId, String workingDir, String startCmd) {
        if (smokeTestSeconds <= 0) {
            return;
        }
        String cmd = startCmd == null ? "" : startCmd.trim();
        if (cmd.isEmpty()) {
            return;
        }
        CommandResult r = sshService.execute(serverId, buildSmokeTestCommand(workingDir, smokeTestSeconds, startCmd),
                (smokeTestSeconds + 10) * 1000);
        int code = r.getExitCode();
        if (r.getTimedOut() || code == 0 || code == 124 || code == 125) {
            return;
        }
        String out = firstNonBlank(r.getStderr(), r.getStdout());
        throw new BizException(ErrorCode.SSH_ERROR,
                "start command smoke test failed (exit=" + code + "): " + out);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b == null ? "" : b;
    }

    /** 生成写 unit 文件的远端命令（quoted heredoc，定界符每次随机防碰撞）。纯函数便于单测。 */
    public static String buildWriteUnitCommand(String unitPath, String content) {
        String delim = "UNIT_EOF_" + UUID.randomUUID();
        return "mkdir -p /etc/systemd/system && cat > " + ShellQuoter.quote(unitPath)
                + " << '" + delim + "'\n" + content + "\n" + delim;
    }

    /**
     * 写入 unit 文件并启动服务。
     *
     * @param fileName 部署包文件名，用于解析运行目录（{@link InstallStepExecutor#resolveRuntimeDir}：
     *                 jar/war → workdir 根，其余 → workdir/extract）
     * @return 步骤 detail（unitName / workdir / workingDir）
     * @throws BizException SSH_ERROR 当写 unit / 启动失败（启动失败附带 status 输出）
     */
    public Map<String, Object> execute(long serverId, String unitName, String workdir,
                                       String fileName, String startCmd) {
        String workingDir = InstallStepExecutor.resolveRuntimeDir(workdir, fileName);
        // 装 unit 前先对候选启动命令做通用冒烟校验：坏命令（flag 错误 / 找不到可执行文件 / 缺依赖）
        // 在写 unit 之前就被判失败，避免留下 Restart=on-failure 的崩溃循环 unit（线上 categraf 实证）。
        runSmokeTest(serverId, workingDir, startCmd);
        String content = buildUnitContent(unitName, workingDir, startCmd);
        String unitPath = "/etc/systemd/system/" + unitName;
        // quoted heredoc：定界符每次随机，防内容恰好含定界文本导致 shell 提前闭合
        String writeCmd = buildWriteUnitCommand(unitPath, content);
        CommandResult w = sshService.execute(serverId, writeCmd);
        if (w.getTimedOut() || w.getExitCode() != 0) {
            throw new BizException(ErrorCode.SSH_ERROR,
                    "write unit " + unitName + " failed (exit=" + w.getExitCode() + "): " + w.getStderr());
        }

        String startLine = buildStartCommand(unitName);
        CommandResult s = sshService.execute(serverId, startLine);
        if (s.getTimedOut() || s.getExitCode() != 0) {
            String reason = s.getTimedOut() ? "timeout" : "exit=" + s.getExitCode();
            CommandResult status = sshService.execute(serverId, "systemctl status " + ShellQuoter.quote(unitName));
            throw new BizException(ErrorCode.SSH_ERROR,
                    "start " + unitName + " failed (" + reason + "): " + s.getStderr()
                            + " | status: " + status.getStdout());
        }

        // 稳定性校验：连续 stableChecks 次采样 SubState 必须均为 running，且 MainPID 稳定（两次非 0 采样一致）。
        // 端口被外部服务占用时新进程 bind 失败 → 崩溃循环（SubState 反复 dead/auto-restart、MainPID 反复变化/为 0），
        // 而健康检查 curl 会被占用端口的外部服务 2xx 欺骗——此校验使崩溃循环无法伪装成部署成功。
        String checkCmd = buildSubStateCommand(unitName);
        List<String> subStates = new ArrayList<>(stableChecks);
        List<String> mainPids = new ArrayList<>(stableChecks);
        for (int i = 1; i <= stableChecks; i++) {
            if (i > 1 && stableIntervalMs > 0) {
                try {
                    Thread.sleep(stableIntervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new BizException(ErrorCode.SSH_ERROR, "start check interrupted for " + unitName);
                }
            }
            CommandResult c = sshService.execute(serverId, checkCmd);
            String out = c.getStdout();
            subStates.add(parseSubState(out));
            mainPids.add(parseMainPid(out));
            if (!"running".equals(subStates.get(i - 1))) {
                break;
            }
        }
        if (subStates.stream().anyMatch(st -> !"running".equals(st))) {
            // 任一采样非 running（崩溃循环）→ 触发回滚
            throw new BizException(ErrorCode.SSH_ERROR,
                    "unit " + unitName + " not stable after start (SubState samples: "
                            + String.join(",", subStates) + "; MainPID samples: "
                            + String.join(",", mainPids) + "); likely crash-loop (port conflict or startup error), "
                            + "triggering rollback");
        }
        if (!mainPidStable(mainPids)) {
            // SubState 虽持续 running 但 MainPID 变化（进程被替换）→ 仍是崩溃循环/反复重启 → 触发回滚
            throw new BizException(ErrorCode.SSH_ERROR,
                    "unit " + unitName + " not stable after start (MainPID changed between samples: "
                            + String.join(",", mainPids) + ", SubState samples: "
                            + String.join(",", subStates) + "); process is being restarted (crash-loop), "
                            + "triggering rollback");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("unitName", unitName);
        detail.put("workdir", workdir);
        detail.put("workingDir", workingDir);
        return detail;
    }

    /**
     * 解析 {@code systemctl show -p SubState[, -p MainPID]} 输出中的 SubState 值（形如
     * {@code SubState=running}，可能含多行）。取 {@code SubState=} 后到行尾的值；找不到
     * {@code SubState=} 或值为空时返回 {@code unknown}，保证稳定性校验不会因输出异常而误判通过。
     */
    static String parseSubState(String stdout) {
        String v = propertyValue(stdout, "SubState");
        return v == null ? "unknown" : v;
    }

    /**
     * 解析 {@code systemctl show -p MainPID[, -p SubState]} 输出中的 MainPID 值。
     * 进程不在运行（崩溃/重启间隙）时 systemd 报 {@code MainPID=0}；找不到属性返回 {@code unknown}。
     */
    static String parseMainPid(String stdout) {
        String v = propertyValue(stdout, "MainPID");
        return v == null ? "unknown" : v;
    }

    /** 从多行 {@code Key=Value} 输出中取指定 key 的值（取该 key 所在行 {@code =} 后到行尾，trim 前缀容忍缩进）。 */
    private static String propertyValue(String stdout, String key) {
        if (stdout == null) {
            return null;
        }
        String prefix = key + "=";
        for (String rawLine : stdout.split("\\R")) {
            String line = rawLine.trim();
            if (line.startsWith(prefix)) {
                String v = line.substring(prefix.length()).trim();
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    /**
     * MainPID 稳定性判定：所有非 0 的采样值必须一致（进程在整个观察窗口内未被替换）。
     * 全为 0 / unknown（进程从未稳定存活）判不稳定；两次非 0 采样不同（进程被反复重启）判不稳定。
     */
    static boolean mainPidStable(List<String> pids) {
        String first = null;
        for (String p : pids) {
            if (p == null || p.isEmpty() || "0".equals(p) || "unknown".equals(p)) {
                continue;
            }
            if (first == null) {
                first = p;
            } else if (!first.equals(p)) {
                return false;
            }
        }
        return first != null;
    }

    /**
     * 启动命令：{@code daemon-reload}（加载新 unit 文件）+ {@code enable}（保证开机自启）+
     * {@code restart}（一定替换进程为新配置）。不能用 {@code enable --now}：对已 active 的 unit，
     * start 分支是 no-op、进程仍跑旧配置（缺陷10：E2E 实锤稳定性校验采到旧进程 → 假 SUCCESS）。
     */
    static String buildStartCommand(String unitName) {
        String q = ShellQuoter.quote(unitName);
        return "systemctl daemon-reload && systemctl enable " + q + " && systemctl restart " + q;
    }

    /** 构造稳定性校验命令（一次取 SubState + MainPID，纯函数，便于单测）。 */
    static String buildSubStateCommand(String unitName) {
        return "systemctl show -p SubState -p MainPID " + ShellQuoter.quote(unitName);
    }
}
