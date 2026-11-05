package com.ops.deploy.deploy;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.ssh.CommandResult;
import com.ops.deploy.ssh.SshService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 部署步骤 4：远端健康检查（curl 轮询）。
 *
 * <p>每轮执行（{@link #buildVerifyCommand} 纯函数）：
 * {@code curl -sf -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:{port}{healthPath}}，
 * 单轮 SSH 超时 15s。stdout 为 HTTP 状态码；2xx → 该轮成功。
 * 全部 attempts 轮失败 → 抛 {@code BizException(DEPLOY_FAILED)} 并附最后一轮输出。
 *
 * <p>轮询次数 / 间隔可注入（测试传 0 间隔避免真实 sleep）。
 */
public class VerifyStepExecutor {

    /** 单轮 curl 的 SSH 超时（毫秒）。 */
    private static final int ROUND_TIMEOUT_MS = 15_000;

    private final SshService sshService;
    private final int attempts;
    private final long intervalMs;

    public VerifyStepExecutor(SshService sshService, int attempts, long intervalMs) {
        this.sshService = sshService;
        this.attempts = attempts;
        this.intervalMs = intervalMs;
    }

    /**
     * 构造单轮健康检查 curl 命令（纯函数，便于单测）。
     * 整个 URL 作为单个 shell token 加引号（纵深防御，防 healthPath 注入）。
     */
    public static String buildVerifyCommand(int port, String healthPath) {
        String path = healthPath == null || healthPath.isEmpty() ? "/" : healthPath;
        String url = "http://127.0.0.1:" + port + path;
        return "curl -sf -o /dev/null -w '%{http_code}' --max-time 5 " + ShellQuoter.quote(url);
    }

    /**
     * 轮询健康检查，2xx 返回成功。等价于 {@link #execute(long, int, String, String)} 传 {@code unitName=null}
     * （不做进程存活降级）。
     */
    public Map<String, Object> execute(long serverId, int port, String healthPath) {
        return execute(serverId, port, healthPath, null);
    }

    /**
     * 轮询健康检查，2xx 返回成功；全部轮次未通过时按失败类型决定处置。
     *
     * <p>失败分两类：
     * <ul>
     *   <li><b>真实 HTTP 错误</b>（端口上有服务监听并返回了非 000 的状态码，如 500/503）→ 服务在跑但不健康，
     *       抛 {@code DEPLOY_FAILED} 触发回滚，不做降级；</li>
     *   <li><b>连接级失败</b>（{@code code=000} 端口无监听，或 SSH 通道故障）→ 可能是"服务本身无 HTTP 端口"
     *       （如监控 agent、后台 worker）。此时若提供了 {@code unitName}，退化为
     *       <b>进程存活校验</b>：查 {@code systemctl show -p SubState}，{@code running} 即通过，
     *       否则（进程崩溃循环/已停）抛 {@code DEPLOY_FAILED} 触发回滚。</li>
     * </ul>
     *
     * <p>该降级使无 HTTP 端口的 agent 类服务能通过 VERIFY，同时仍能拦截"进程根本没起来"
     * （SubState 非 running）——START 步的稳定性采样与此处构成双重保险。
     *
     * @param unitName systemd 单元名（PROCESS 模式为 {@code deploy-{project}.service}；
     *                 NGINX/DOCKER 传 null 表示不做进程存活降级）
     * @return 步骤 detail（url / attempts / httpCode，降级时另含 verifyMethod=process_alive / subState）
     * @throws BizException DEPLOY_FAILED 当真实 HTTP 错误，或降级校验判定进程未存活
     */
    public Map<String, Object> execute(long serverId, int port, String healthPath, String unitName) {
        String cmd = buildVerifyCommand(port, healthPath);
        String url = "http://127.0.0.1:" + port + (healthPath == null || healthPath.isEmpty() ? "/" : healthPath);
        String lastOutput = "";
        boolean sawRealHttpError = false;
        for (int i = 1; i <= attempts; i++) {
            String roundOutput;
            String httpCode = null;
            try {
                CommandResult r = sshService.execute(serverId, cmd, ROUND_TIMEOUT_MS);
                String out = r.getStdout() == null ? "" : r.getStdout().trim();
                roundOutput = "round=" + i + " exit=" + r.getExitCode() + " timedOut=" + r.getTimedOut()
                        + " code=" + out + " stderr=" + r.getStderr();
                if (isRealStatusCode(out)) {
                    sawRealHttpError = true;
                }
                if (out.startsWith("2")) {
                    httpCode = out;
                }
            } catch (BizException e) {
                if (e.getCode() != ErrorCode.SSH_ERROR) {
                    throw e; // 非 SSH 错误（参数/权限等）立即上抛、不消耗轮次
                }
                // SSH 通道级故障（半死连接 / channel open failed）：MinaSshExecutor 已剔除坏连接，
                // 本轮按未通过处理，下轮用新连接重试，避免一次瞬时故障终止整个健康检查。
                roundOutput = "round=" + i + " sshError=" + e.getMessage();
            }
            lastOutput = roundOutput;
            if (httpCode != null) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("url", url);
                detail.put("attempts", i);
                detail.put("httpCode", httpCode);
                return detail;
            }
            if (i < attempts && intervalMs > 0) {
                sleepBetweenRounds();
            }
        }
        // 全部轮次未通过：连接级失败（无真实 HTTP 错误）且提供了 systemd 单元 → 退化为进程存活校验。
        // 真实 HTTP 错误（端口有监听但报错）说明服务在跑但不健康，不降级、直接判失败。
        if (unitName != null && !sawRealHttpError) {
            Map<String, Object> alive = checkProcessAlive(serverId, unitName);
            if (alive != null) {
                alive.put("url", url);
                alive.put("attempts", attempts);
                alive.put("verifyMethod", "process_alive");
                alive.put("note", "http health check unreachable (no HTTP listener), "
                        + "fell back to systemd process-alive check");
                return alive;
            }
        }
        throw new BizException(ErrorCode.DEPLOY_FAILED,
                "health check failed after " + attempts + " attempts, last: " + lastOutput);
    }

    /**
     * 进程存活校验：{@code systemctl show -p SubState {unit}} 输出 {@code SubState=running} 返回
     * detail（subState/unitName），否则（dead / auto-restart / 查询失败）返回 null 交由调用方判失败。
     */
    private Map<String, Object> checkProcessAlive(long serverId, String unitName) {
        try {
            CommandResult r = sshService.execute(serverId,
                    "systemctl show -p SubState " + ShellQuoter.quote(unitName), ROUND_TIMEOUT_MS);
            String sub = StartStepExecutor.parseSubState(r.getStdout());
            if ("running".equals(sub)) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("subState", sub);
                detail.put("unitName", unitName);
                return detail;
            }
            return null;
        } catch (BizException e) {
            return null;
        }
    }

    /** 判定 curl 输出是否为真实 HTTP 状态码（3 位数字且非 {@code 000}）——{@code 000} 表示连接失败而非服务返回。 */
    private static boolean isRealStatusCode(String out) {
        if (out == null || out.length() != 3 || out.equals("000")) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            if (!Character.isDigit(out.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private void sleepBetweenRounds() {
        try {
            Thread.sleep(intervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.DEPLOY_FAILED, "verify interrupted");
        }
    }
}
