package com.ops.deploy.deploy;

import com.ops.deploy.notify.NotificationEntity;
import com.ops.deploy.notify.NotificationRepository;
import com.ops.deploy.notify.WebhookNotifier;
import com.ops.deploy.ssh.CommandResult;
import com.ops.deploy.ssh.SshService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 服务健康探针：定时（默认 30s，deploy.health-probe.fixed-delay-ms 可调）SSH 到各服务器
 * 探测全部在线实例（online=true）的真实状态，与 DB 中的 status 对比，发生翻转时回写并
 * 写站内通知（DOWN=WARN / UP=INFO）；DOWN 翻转额外经 WebhookNotifier 推送 webhook 告警
 * （UP 恢复仅站内通知，不推送）。
 *
 * <p>探测命令按 mode 分支（纯函数 {@link #probeCommand}，便于单测）：
 * <ul>
 *   <li>PROCESS → {@code systemctl is-active --quiet <unit> && echo UP || echo DOWN}；</li>
 *   <li>DOCKER → {@code compose ps --status running --services}，stdout 非空即 UP；</li>
 *   <li>NGINX → {@code curl -fsS -o /dev/null -w '%{http_code}' http://127.0.0.1:<port><path>}，
 *       2xx/3xx 即 UP（需 port 与 healthPath 齐全，否则跳过探测）。</li>
 * </ul>
 *
 * <p>SSH 异常（连不上服务器）不翻转状态——区分「服务停了」与「服务器不可达」，
 * 仅记录日志，避免把网络抖动误报为服务宕机。
 */
@Component
public class ServiceHealthProbe {

    private static final Logger log = LoggerFactory.getLogger(ServiceHealthProbe.class);

    /** 单次探测的 SSH 超时（毫秒）。 */
    static final int PROBE_TIMEOUT_MS = 10_000;

    private final ServiceInstanceRepository instanceRepository;
    private final SshService sshService;
    private final NotificationRepository notificationRepository;
    private final WebhookNotifier webhookNotifier;

    public ServiceHealthProbe(ServiceInstanceRepository instanceRepository,
                              SshService sshService,
                              NotificationRepository notificationRepository,
                              WebhookNotifier webhookNotifier) {
        this.instanceRepository = instanceRepository;
        this.sshService = sshService;
        this.notificationRepository = notificationRepository;
        this.webhookNotifier = webhookNotifier;
    }

    /** PROCESS 探测命令：systemctl is-active 静默判定，输出 UP/DOWN。 */
    public static String buildProcessProbeCommand(String unitName) {
        return "systemctl is-active --quiet " + ShellQuoter.quote(unitName)
                + " && echo UP || echo DOWN";
    }

    /** DOCKER 探测命令：列出 running 中的服务名，stdout 非空即 UP。 */
    public static String buildDockerProbeCommand(String workdir) {
        return "docker-compose -f " + ShellQuoter.quote(DockerStartExecutor.composePath(workdir))
                + " ps --status running --services";
    }

    /** NGINX 探测命令：curl 本机端口+健康路径，输出 HTTP 状态码。 */
    public static String buildNginxProbeCommand(Integer port, String healthPath) {
        String path = (healthPath == null || healthPath.isEmpty()) ? "/" : healthPath;
        return "curl -fsS -o /dev/null -w '%{http_code}' http://127.0.0.1:" + port + path;
    }

    /**
     * 按实例 mode 生成探测命令；无法探测时返回 null（跳过，不翻转状态）：
     * PROCESS 缺 unitName / NGINX 缺 port 时返回 null。
     */
    public static String probeCommand(ServiceInstanceEntity inst) {
        String mode = inst.getMode() == null ? "PROCESS" : inst.getMode();
        return switch (mode) {
            case "DOCKER" -> buildDockerProbeCommand(inst.getWorkdir());
            case "NGINX" -> inst.getPort() == null ? null : buildNginxProbeCommand(inst.getPort(), inst.getHealthPath());
            default -> inst.getUnitName() == null ? null : buildProcessProbeCommand(inst.getUnitName());
        };
    }

    /** 判定探测结果：UP=true 健康，DOWN=false 不健康，null=无法判定（SSH 失败/输出不可识别，不翻转）。 */
    static Boolean interpret(ServiceInstanceEntity inst, CommandResult r) {
        if (r == null || r.getTimedOut() || r.getExitCode() != 0) {
            return null;
        }
        String mode = inst.getMode() == null ? "PROCESS" : inst.getMode();
        String out = r.getStdout() == null ? "" : r.getStdout().trim();
        return switch (mode) {
            case "DOCKER" -> !out.isEmpty();
            case "NGINX" -> out.length() == 3 && out.charAt(0) != '4' && out.charAt(0) != '5';
            default -> "UP".equals(out);
        };
    }

    /** 定时入口：遍历全部在线实例逐个探测（单实例失败不影响其他实例）。 */
    @Scheduled(fixedDelayString = "${deploy.health-probe.fixed-delay-ms:30000}")
    public void probeAll() {
        List<ServiceInstanceEntity> online = instanceRepository.findAllByOnlineTrue();
        for (ServiceInstanceEntity inst : online) {
            try {
                probe(inst);
            } catch (Exception e) {
                log.warn("health probe failed for instance {} ({}): {}",
                        inst.getName(), inst.getId(), e.getMessage());
            }
        }
    }

    /**
     * 探测单个实例：命令为 null（缺必要字段）跳过；SSH 抛异常视为服务器不可达，不翻转。
     * 状态翻转（RUNNING↔STOPPED）时回写 instance.status 并写站内通知。
     *
     * @return UP=true / DOWN=false / null=未探测（跳过或不可达）
     */
    @Transactional
    public Boolean probe(ServiceInstanceEntity inst) {
        String command = probeCommand(inst);
        if (command == null) {
            return null;
        }
        CommandResult r;
        try {
            r = sshService.execute(inst.getServerId(), command, PROBE_TIMEOUT_MS);
        } catch (Exception e) {
            log.warn("health probe unreachable for instance {} on server {}: {}",
                    inst.getName(), inst.getServerId(), e.getMessage());
            return null;
        }
        Boolean up = interpret(inst, r);
        if (up == null) {
            return null;
        }
        boolean wasUp = "RUNNING".equals(inst.getStatus());
        if (up == wasUp) {
            return up;
        }
        // 状态翻转：回写 + 通知
        inst.setStatus(up ? "RUNNING" : "STOPPED");
        inst.setUpdatedAt(LocalDateTime.now());
        instanceRepository.save(inst);
        NotificationEntity n = new NotificationEntity();
        n.setLevel(up ? "INFO" : "WARN");
        n.setTitle(up ? "服务恢复运行" : "服务已停止");
        n.setMessage(String.format("实例 %s（项目 %d / 服务器 %d）%s",
                inst.getName(), inst.getProjectId(), inst.getServerId(),
                up ? "探测恢复正常" : "探测到服务已停止"));
        n.setProjectId(inst.getProjectId());
        n.setInstanceId(inst.getId());
        notificationRepository.save(n);
        // DOWN 翻转额外推送 webhook 告警（与站内通知同 title/message；UP 恢复不推送）。
        // webhook 失败绝不影响探针主流程（WebhookNotifier 内部已吞异常仅 log）。
        if (!up) {
            try {
                webhookNotifier.notify("WARN", n.getTitle(), n.getMessage());
            } catch (RuntimeException e) {
                log.warn("webhook notify on down-flip failed for instance {}: {}",
                        inst.getName(), e.getMessage());
            }
        }
        log.info("health probe flipped instance {} (project={} server={}) to {}",
                inst.getName(), inst.getProjectId(), inst.getServerId(), inst.getStatus());
        return up;
    }
}
