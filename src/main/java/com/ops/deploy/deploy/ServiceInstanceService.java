package com.ops.deploy.deploy;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.project.ProjectAccessService;
import com.ops.deploy.project.ProjectEntity;
import com.ops.deploy.project.ProjectRepository;
import com.ops.deploy.project.ProjectSubServiceEntity;
import com.ops.deploy.project.ProjectSubServiceManager;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.ssh.CommandResult;
import com.ops.deploy.ssh.SshExecutor;
import com.ops.deploy.ssh.SshService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

/**
 * 服务实例维护（Task 6 G + Task 7 启停/重启/实时日志）。
 *
 * <p>实例名形如 {@code {projectName}-{serverId}-{versionId}}（全局唯一）。
 * <ul>
 *   <li>{@link #onSuccess(DeploymentEntity)}：部署 SUCCESS 后 upsert 新实例
 *       （status=RUNNING / online=true），并把同 (project, server) 下旧的在线实例
 *       置 online=false / status=STOPPED。实例写入 mode 与 workdir；unitName 仅
 *       PROCESS 模式有值（NGINX/DOCKER 无 systemd unit）。</li>
 *   <li>{@link #onRolledBack(DeploymentEntity)}：回滚成功后，把指向 oldVersionId 的
 *       旧实例恢复 online=true / status=RUNNING。</li>
 *   <li>start / stop / restart 按实例 mode 分支：PROCESS 用 systemctl（unitName 经
 *       {@link ShellQuoter} 转义）；DOCKER 用 docker-compose up -d / down / down+up；
 *       NGINX 用「软链指向该版本 + nginx -t && reload」；stop 删版本软链 + reload
 *       （站点 conf 保留，再次 start 重建软链即恢复，nginx 守护进程本身不停）。
 *       成功后回写 status / online。</li>
 *   <li>streamLogs 按 mode 分支：PROCESS 用 journalctl -f；DOCKER 用 docker-compose logs -f；
 *       NGINX 无站点专属日志文件（共享 nginx）暂不支持。stdout 按行回调 lineConsumer。</li>
 *   <li>delete：DOCKER 按 workdir 隔离，任何实例 compose down；PROCESS/NGINX 的
 *       unit/站点 conf 为 (project, server) 级共享资源，仅在线实例删除时清理
 *       （停 unit+删 unit 文件 / 删站点 conf+软链+reload），非在线实例只删 DB 记录。</li>
 * </ul>
 */
@Service
public class ServiceInstanceService {

    private static final Logger log = LoggerFactory.getLogger(ServiceInstanceService.class);

    private final ServiceInstanceRepository instanceRepository;
    private final ProjectRepository projectRepository;
    private final SshService sshService;
    private final ProjectAccessService accessService;
    private final ProjectSubServiceManager subServiceManager;

    public ServiceInstanceService(ServiceInstanceRepository instanceRepository,
                                  ProjectRepository projectRepository,
                                  SshService sshService,
                                  ProjectAccessService accessService,
                                  ProjectSubServiceManager subServiceManager) {
        this.instanceRepository = instanceRepository;
        this.projectRepository = projectRepository;
        this.sshService = sshService;
        this.accessService = accessService;
        this.subServiceManager = subServiceManager;
    }

    /** 实例名：{projectName}-{serverId}-{versionId}。 */
    public static String buildName(String projectName, long serverId, long versionId) {
        return projectName + "-" + serverId + "-" + versionId;
    }

    /** 部署成功后：upsert 新实例上线，旧在线实例下线（在线唯一性维度 (project, server, service)）。 */
    @Transactional
    public void onSuccess(DeploymentEntity deployment) {
        String projectName = projectName(deployment.getProjectId());
        long projectId = deployment.getProjectId();
        long serverId = deployment.getServerId();
        long serviceId = deployment.getServiceId() == null ? 0L : deployment.getServiceId();
        String pathKey = pathKeyOfServiceId(serviceId);

        // 先取同 (project, server, service) 旧在线实例（upsert 新实例 online=true 之前，避免查到自身）。
        // 服务级在线唯一：同项目不同子服务在同一 server 上各自独立在线，互不影响。
        ServiceInstanceEntity old =
                instanceRepository.findFirstByProjectIdAndServerIdAndServiceIdAndOnlineTrue(projectId, serverId, serviceId)
                        .orElse(null);

        String name = buildName(projectName, serverId, deployment.getVersionId());
        ServiceInstanceEntity inst = instanceRepository.findByName(name)
                .orElseGet(ServiceInstanceEntity::new);
        inst.setName(name);
        inst.setProjectId(projectId);
        inst.setServiceId(serviceId);
        inst.setServerId(serverId);
        inst.setDeploymentId(deployment.getId());
        String mode = deployment.getMode() == null ? "PROCESS" : deployment.getMode();
        inst.setMode(mode);
        inst.setUnitName("PROCESS".equals(mode)
                ? ProjectSubServiceManager.unitName(projectName, pathKey) : null);
        inst.setWorkdir(deployment.getWorkdir());
        inst.setPort(deployment.getPort());
        inst.setHealthPath(deployment.getHealthPath());
        inst.setStatus("RUNNING");
        inst.setOnline(Boolean.TRUE);
        inst.setDeployedAt(LocalDateTime.now());
        inst.setUpdatedAt(LocalDateTime.now());
        instanceRepository.save(inst);

        if (old != null && !old.getId().equals(inst.getId())) {
            old.setOnline(Boolean.FALSE);
            old.setStatus("STOPPED");
            old.setUpdatedAt(LocalDateTime.now());
            instanceRepository.save(old);
            log.info("offline old instance {} for project={} server={} service={}",
                    old.getName(), projectId, serverId, serviceId);
        }
    }

    /** 回滚成功后：恢复指向 oldVersionId 的旧实例为在线。 */
    @Transactional
    public void onRolledBack(DeploymentEntity deployment) {
        if (deployment.getOldVersionId() == null) {
            return;
        }
        String projectName = projectName(deployment.getProjectId());
        String name = buildName(projectName, deployment.getServerId(), deployment.getOldVersionId());
        instanceRepository.findByName(name).ifPresent(inst -> {
            inst.setOnline(Boolean.TRUE);
            inst.setStatus("RUNNING");
            inst.setUpdatedAt(LocalDateTime.now());
            instanceRepository.save(inst);
            log.info("restored instance {} online after rollback", name);
        });
    }

    /** unit 名：deploy-{projectName}.service（与 StartStepExecutor 约定一致；默认服务口径）。 */
    public static String unitName(String projectName) {
        return ProjectSubServiceManager.unitName(projectName, null);
    }

    /** 按 serviceId 解析 pathKey（默认服务 → null，旧口径 workdir/unit）。 */
    private String pathKeyOfServiceId(Long serviceId) {
        long sid = serviceId == null ? 0L : serviceId;
        if (sid == 0L) {
            return null;
        }
        return subServiceManager.resolveEntity(sid).getPathKey();
    }

    /** 实例的 pathKey（由实例 serviceId 解析；0/空 → null 默认服务）。 */
    private String pathKeyOf(ServiceInstanceEntity inst) {
        return pathKeyOfServiceId(inst.getServiceId());
    }

    /** systemctl start 命令（unitName 经 shell 转义）。 */
    public static String buildStartCommand(String unitName) {
        return "systemctl start " + ShellQuoter.quote(unitName);
    }

    /** systemctl stop 命令（unitName 经 shell 转义）。 */
    public static String buildStopCommand(String unitName) {
        return "systemctl stop " + ShellQuoter.quote(unitName);
    }

    /** systemctl restart 命令（unitName 经 shell 转义）。 */
    public static String buildRestartCommand(String unitName) {
        return "systemctl restart " + ShellQuoter.quote(unitName);
    }

    /** journalctl -f 实时日志命令（unitName 经 shell 转义）。 */
    public static String buildJournalCommand(String unitName) {
        return "journalctl -u " + ShellQuoter.quote(unitName) + " -f --no-pager";
    }

    /** NGINX 启动/重启：软链指向该版本 extract + 校验 reload（复用 NginxStartExecutor 纯函数；默认服务口径）。 */
    public static String buildNginxStartCommand(String projectName, String workdir) {
        return buildNginxStartCommand(projectName, null, workdir);
    }

    /** NGINX 启动/重启按 pathKey 分叉：软链指向对应 pathKey 的 current。 */
    public static String buildNginxStartCommand(String projectName, String pathKey, String workdir) {
        return NginxStartExecutor.buildSymlinkCommand(projectName, pathKey, workdir)
                + " && " + NginxStartExecutor.buildReloadCommand();
    }

    /**
     * NGINX 停止：删除版本软链（站点 root 失效，访问返回 404）+ reload（默认服务口径）。
     * 站点 conf 与软链为 (project, server, service) 级共享资源，删除软链即下线当前在线版本；
     * 再次启动（{@link #buildNginxStartCommand}）会重建软链指向该版本 extract 并 reload 恢复。
     */
    public static String buildNginxStopCommand(String projectName) {
        return buildNginxStopCommand(projectName, null);
    }

    /** NGINX 停止按 pathKey 分叉：删对应 pathKey 的版本软链 + reload。 */
    public static String buildNginxStopCommand(String projectName, String pathKey) {
        return "rm -f " + ShellQuoter.quote(NginxStartExecutor.symlinkPath(projectName, pathKey))
                + " && " + NginxStartExecutor.buildReloadCommand();
    }

    /** DOCKER 启动：docker-compose up -d（纯启动，不带 --build；镜像由部署流程构建）。 */
    public static String buildDockerStartCommand(String workdir) {
        return DockerStartExecutor.buildPlainUpCommand(workdir);
    }

    /** DOCKER 停止：docker-compose down（仅停容器，不删卷/镜像）。 */
    public static String buildDockerStopCommand(String workdir) {
        return "docker-compose -f " + ShellQuoter.quote(DockerStartExecutor.composePath(workdir)) + " down";
    }

    /** DOCKER 重启：compose restart 全部服务。 */
    public static String buildDockerRestartCommand(String workdir) {
        return "docker-compose -f " + ShellQuoter.quote(DockerStartExecutor.composePath(workdir)) + " restart";
    }

    /** DOCKER 实时日志：docker-compose logs -f（--no-color 便于前端着色）。 */
    public static String buildDockerLogsCommand(String workdir) {
        return "docker-compose -f " + ShellQuoter.quote(DockerStartExecutor.composePath(workdir))
                + " logs -f --no-color";
    }

    /**
     * PROCESS 删除命令：停 unit（容错）+ disable + 删 unit 文件 + daemon-reload。
     * unit 为 (project, server) 级共享资源，仅在线实例删除时调用。
     */
    public static String buildProcessDeleteCommand(String unitName) {
        String unit = ShellQuoter.quote(unitName);
        String unitFile = ShellQuoter.quote("/etc/systemd/system/" + unitName);
        return "systemctl stop " + unit + " || true"
                + "; systemctl disable " + unit + " || true"
                + "; rm -f " + unitFile
                + "; systemctl daemon-reload";
    }

    /**
     * NGINX 删除命令：删站点 conf + 版本软链（(project, server, service) 级共享资源）+ 校验 reload（容错；默认服务口径）。
     * 仅在线实例删除时调用。
     */
    public static String buildNginxDeleteCommand(String projectName) {
        return buildNginxDeleteCommand(projectName, null);
    }

    /** NGINX 删除命令按 pathKey 分叉：删对应 pathKey 的站点 conf + 版本软链 + reload。 */
    public static String buildNginxDeleteCommand(String projectName, String pathKey) {
        String conf = ShellQuoter.quote(NginxStartExecutor.confPath(projectName, pathKey));
        String link = ShellQuoter.quote(NginxStartExecutor.symlinkPath(projectName, pathKey));
        return "rm -f " + conf + " " + link + " && (" + NginxStartExecutor.buildReloadCommand() + " || true)";
    }

    // ---------- 启停 / 重启（Task 7，按实例 mode 分支） ----------

    /** 启动服务：按 mode 生成命令（PROCESS=systemctl start / DOCKER=up -d / NGINX=软链+reload），成功后 RUNNING/online。 */
    public ServiceInstanceVO start(Long id, AuthUser user) {
        ServiceInstanceEntity inst = requireInstance(id);
        accessService.assertOperate(inst.getProjectId(), user);
        return runAndMark(inst, "start", controlCommand(inst, "start"), "RUNNING", Boolean.TRUE);
    }

    /** 停止服务：按 mode 生成命令（PROCESS=systemctl stop / DOCKER=down / NGINX=删软链+reload），成功后 STOPPED（online 保持原值）。 */
    public ServiceInstanceVO stop(Long id, AuthUser user) {
        ServiceInstanceEntity inst = requireInstance(id);
        accessService.assertOperate(inst.getProjectId(), user);
        String cmd = controlCommand(inst, "stop");
        CommandResult r = sshService.execute(inst.getServerId(), cmd);
        if (r.getTimedOut() || r.getExitCode() != 0) {
            throw new BizException(ErrorCode.SSH_ERROR,
                    opLabel(inst, "stop") + " failed on server " + inst.getServerId() + " (exit " + r.getExitCode() + "): "
                            + r.getStderr());
        }
        inst.setStatus("STOPPED");
        inst.setUpdatedAt(LocalDateTime.now());
        instanceRepository.save(inst);
        log.info("stopped instance {} on server {}", inst.getName(), inst.getServerId());
        return toVo(inst);
    }

    /** 重启服务：按 mode 生成命令（PROCESS=systemctl restart / DOCKER=restart / NGINX=软链+reload），成功后 RUNNING/online。 */
    public ServiceInstanceVO restart(Long id, AuthUser user) {
        ServiceInstanceEntity inst = requireInstance(id);
        accessService.assertOperate(inst.getProjectId(), user);
        return runAndMark(inst, "restart", controlCommand(inst, "restart"), "RUNNING", Boolean.TRUE);
    }

    /**
     * 删除服务实例：清理目标机资源后删 DB 记录。
     * <ul>
     *   <li>DOCKER：compose 资源按 workdir 隔离，任何实例都 compose down；</li>
     *   <li>PROCESS：unit 为 (project, server) 级共享，仅在线实例停 unit + 删 unit 文件 + daemon-reload；</li>
     *   <li>NGINX：站点 conf/软链同为共享资源，仅在线实例删 conf + 软链 + reload。</li>
     * </ul>
     * 非在线的 PROCESS/NGINX 实例不动共享资源，仅删 DB 记录。SSH 失败抛 SSH_ERROR（不删记录）。
     */
    @Transactional
    public void delete(Long id, AuthUser user) {
        ServiceInstanceEntity inst = requireInstance(id);
        accessService.assertOperate(inst.getProjectId(), user);
        String mode = inst.getMode() == null ? "PROCESS" : inst.getMode();
        boolean shared = Boolean.TRUE.equals(inst.getOnline());
        String pk = pathKeyOf(inst);
        String command = switch (mode) {
            case "DOCKER" -> buildDockerStopCommand(inst.getWorkdir());
            case "NGINX" -> shared ? buildNginxDeleteCommand(projectName(inst.getProjectId()), pk) : null;
            default -> (shared && inst.getUnitName() != null)
                    ? buildProcessDeleteCommand(inst.getUnitName()) : null;
        };
        if (command != null) {
            CommandResult r = sshService.execute(inst.getServerId(), command);
            if (r.getTimedOut() || r.getExitCode() != 0) {
                throw new BizException(ErrorCode.SSH_ERROR,
                        deleteLabel(mode) + " failed on server " + inst.getServerId()
                                + " (exit " + r.getExitCode() + "): " + r.getStderr());
            }
        }
        instanceRepository.delete(inst);
        log.info("deleted instance {} (mode={}, sharedResourcesCleaned={}) on server {}",
                inst.getName(), mode, shared, inst.getServerId());
    }


    /** 通用 start/restart：执行预构建命令，exit 0 且非超时则置 status/online 并 save，否则抛 SSH_ERROR（opLabel 区分 mode）。 */
    private ServiceInstanceVO runAndMark(ServiceInstanceEntity inst, String verb, String command,
                                         String newStatus, Boolean newOnline) {
        CommandResult r = sshService.execute(inst.getServerId(), command);
        if (r.getTimedOut() || r.getExitCode() != 0) {
            throw new BizException(ErrorCode.SSH_ERROR,
                    opLabel(inst, verb) + " failed on server " + inst.getServerId()
                            + " (exit " + r.getExitCode() + "): " + r.getStderr());
        }
        inst.setStatus(newStatus);
        inst.setOnline(newOnline);
        inst.setUpdatedAt(LocalDateTime.now());
        instanceRepository.save(inst);
        log.info("{} instance {} on server {}", newStatus.toLowerCase(), inst.getName(), inst.getServerId());
        return toVo(inst);
    }

    /**
     * 启停命令按 mode 分支：
     * <ul>
     *   <li>PROCESS → {@code systemctl {verb} unitName}；</li>
     *   <li>DOCKER → compose {@code up -d} / {@code down} / {@code restart}（基于 workdir 派生 compose 路径）；</li>
     *   <li>NGINX → start/restart 软链指向该版本 extract + {@code nginx -t && reload}；
     *       stop 删版本软链 + reload（站点下线但 conf 保留，nginx 守护进程不停）。</li>
     * </ul>
     */
    private String controlCommand(ServiceInstanceEntity inst, String verb) {
        String mode = inst.getMode() == null ? "PROCESS" : inst.getMode();
        String pk = pathKeyOf(inst);
        switch (mode) {
            case "NGINX" -> {
                if ("stop".equals(verb)) {
                    return buildNginxStopCommand(projectName(inst.getProjectId()), pk);
                }
                return buildNginxStartCommand(projectName(inst.getProjectId()), pk, inst.getWorkdir());
            }
            case "DOCKER" -> {
                return switch (verb) {
                    case "stop" -> buildDockerStopCommand(inst.getWorkdir());
                    case "restart" -> buildDockerRestartCommand(inst.getWorkdir());
                    default -> buildDockerStartCommand(inst.getWorkdir());
                };
            }
            default -> {
                return switch (verb) {
                    case "stop" -> buildStopCommand(inst.getUnitName());
                    case "restart" -> buildRestartCommand(inst.getUnitName());
                    default -> buildStartCommand(inst.getUnitName());
                };
            }
        }
    }

    /** 错误消息的动词前缀（按 mode：systemctl / docker-compose / nginx）。 */
    private static String opLabel(ServiceInstanceEntity inst, String verb) {
        String mode = inst.getMode() == null ? "PROCESS" : inst.getMode();
        return switch (mode) {
            case "NGINX" -> "nginx " + verb;
            case "DOCKER" -> "docker-compose " + verb;
            default -> "systemctl " + verb;
        };
    }

    /** 删除错误消息前缀（按 mode）。 */
    private static String deleteLabel(String mode) {
        return switch (mode) {
            case "NGINX" -> "delete nginx site";
            case "DOCKER" -> "docker-compose down";
            default -> "delete systemd unit";
        };
    }

    /** 查实例，不存在抛 NOT_FOUND。 */
    private ServiceInstanceEntity requireInstance(Long id) {
        return instanceRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "service instance not found: " + id));
    }

    /** 实例所属项目可见性校验（供 Controller 在 SSE 等主线程先做权限判定，状态码准确）。 */
    public void assertView(Long id, AuthUser user) {
        ServiceInstanceEntity inst = requireInstance(id);
        accessService.assertView(inst.getProjectId(), user);
    }

    /** 查实例详情。 */
    public ServiceInstanceVO get(Long id, AuthUser user) {
        ServiceInstanceEntity inst = requireInstance(id);
        accessService.assertView(inst.getProjectId(), user);
        return toVo(inst);
    }

    /** 列表：按 projectId / serverId / serviceId 过滤（都无则全量），非 ADMIN 仅返回其可见项目的实例，按部署时间倒序。 */
    public List<ServiceInstanceVO> list(Long projectId, Long serverId, Long serviceId, AuthUser user) {
        if (projectId != null) {
            accessService.assertView(projectId, user);
        }
        List<Long> visibleIds = accessService.visibleProjectIds(user);
        if (visibleIds != null && visibleIds.isEmpty()) {
            return List.of();
        }
        List<ServiceInstanceEntity> all;
        if (projectId != null && serverId != null) {
            all = instanceRepository.findByProjectIdAndServerIdOrderByDeployedAtDesc(projectId, serverId);
        } else if (projectId != null) {
            all = instanceRepository.findByProjectIdOrderByDeployedAtDesc(projectId);
        } else if (serverId != null) {
            all = instanceRepository.findByServerIdOrderByDeployedAtDesc(serverId);
        } else {
            all = instanceRepository.findAllByOrderByDeployedAtDesc();
        }
        if (visibleIds == null) {
            return all.stream().filter(e -> serviceId == null || serviceId.equals(e.getServiceId())).map(this::toVo).toList();
        }
        return all.stream()
                .filter(e -> visibleIds.contains(e.getProjectId()))
                .filter(e -> serviceId == null || serviceId.equals(e.getServiceId()))
                .map(this::toVo)
                .toList();
    }

    /** 实时日志：按 mode 分支（PROCESS=journalctl -f / DOCKER=compose logs -f；NGINX 无站点专属日志不支持），stdout 按行回调 lineConsumer，退出后 onComplete（退出码）。 */
    public SshExecutor.StreamHandle streamLogs(Long id, AuthUser user, Consumer<String> lineConsumer,
                                               Consumer<Integer> onComplete) {
        ServiceInstanceEntity inst = requireInstance(id);
        accessService.assertView(inst.getProjectId(), user);
        String cmd = logsCommand(inst);
        return sshService.stream(inst.getServerId(), cmd, lineConsumer, onComplete);
    }

    /** 日志命令按 mode 分支：PROCESS→journalctl -f；DOCKER→compose logs -f；NGINX 共享 nginx 无站点专属日志文件，抛 BAD_REQUEST。 */
    private String logsCommand(ServiceInstanceEntity inst) {
        String mode = inst.getMode() == null ? "PROCESS" : inst.getMode();
        switch (mode) {
            case "NGINX" -> {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "nginx mode instances share the system nginx daemon; no per-site log file to tail");
            }
            case "DOCKER" -> {
                return buildDockerLogsCommand(inst.getWorkdir());
            }
            default -> {
                return buildJournalCommand(inst.getUnitName());
            }
        }
    }

    /** 实体 → VO（含 projectName，复用查库兜底逻辑）。 */
    private ServiceInstanceVO toVo(ServiceInstanceEntity e) {
        ServiceInstanceVO vo = new ServiceInstanceVO();
        vo.setId(e.getId());
        vo.setName(e.getName());
        vo.setProjectId(e.getProjectId());
        vo.setProjectName(projectName(e.getProjectId()));
        vo.setServiceId(e.getServiceId());
        vo.setServerId(e.getServerId());
        vo.setDeploymentId(e.getDeploymentId());
        vo.setUnitName(e.getUnitName());
        vo.setMode(e.getMode());
        vo.setWorkdir(e.getWorkdir());
        vo.setPort(e.getPort());
        vo.setHealthPath(e.getHealthPath());
        vo.setStatus(e.getStatus());
        vo.setOnline(e.getOnline());
        vo.setDeployedAt(e.getDeployedAt());
        vo.setUpdatedAt(e.getUpdatedAt());
        return vo;
    }

    private String projectName(long projectId) {
        return projectRepository.findById(projectId)
                .map(ProjectEntity::getName)
                .orElse("project-" + projectId);
    }
}
