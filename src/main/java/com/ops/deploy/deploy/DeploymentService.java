package com.ops.deploy.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.database.DatabaseService;
import com.ops.deploy.project.DetectedResult;
import com.ops.deploy.project.PackageVersionEntity;
import com.ops.deploy.project.PackageVersionRepository;
import com.ops.deploy.project.ProjectAccessService;
import com.ops.deploy.project.ProjectConfigFileRepository;
import com.ops.deploy.project.ProjectEntity;
import com.ops.deploy.project.ProjectRepository;
import com.ops.deploy.project.ProjectSubServiceEntity;
import com.ops.deploy.project.ProjectSubServiceManager;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.ssh.CommandResult;
import com.ops.deploy.ssh.SshService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 部署任务服务（Task 6 D）。
 *
 * <p>create 语义：
 * <ul>
 *   <li>versionId 必须属于某项目且存在，否则 NOT_FOUND；</li>
 *   <li>同 server 上若存在非终态部署（占位中）→ CONFLICT（同服务器串行部署）；</li>
 *   <li>startCmd / port / healthPath 三级兜底（请求 &gt; project 默认 &gt; detected 建议）；
 *       startCmd 三级皆缺且 mode=PROCESS → BAD_REQUEST("no start command available")，
 *       healthPath 缺省 "/"；</li>
 *   <li>oldVersionId 取当前 (project, server) 在线实例对应部署的 versionId（回滚目标）；</li>
 *   <li>mode 判定：请求 `mode` 手动指定优先（白名单 PROCESS/NGINX/DOCKER），否则 `resolveMode`
 *       （STATIC_WEB→NGINX、DOCKER→DOCKER（需 compose 配置文件）、其余→PROCESS）；
 *       workdir=/opt/deploy/{projectName}/{versionId}，落库 PENDING；</li>
 *   <li>事务提交后经 {@link DeploymentTrigger} 异步调用 {@link DeploymentRunner}。</li>
 * </ul>
 *
 * <p>cancel 仅允许 PENDING → CANCELLED（经状态机守卫）；get/list/getSteps 为只读查询。
 */
@Service
public class DeploymentService {

    private static final Logger log = LoggerFactory.getLogger(DeploymentService.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 部署终态集合（status 以 name 落库）。 */
    private static final List<String> TERMINAL_STATUSES = List.of(
            "SUCCESS", "FAILED", "ROLLED_BACK", "NEEDS_ATTENTION", "CANCELLED");

    /** healthPath 最终校验：必须是合法 URL 路径（以 / 开头，仅允许安全字符，防 curl 注入）。 */
    private static final Pattern HEALTH_PATH_PATTERN = Pattern.compile("^/[A-Za-z0-9._/*-]*$");

    /** 手动可选部署方式白名单（与 AI 侧 MODES 一致）。 */
    private static final List<String> MANUAL_MODES = List.of("PROCESS", "NGINX", "DOCKER");

    private final DeploymentRepository deploymentRepository;
    private final DeploymentStepRepository stepRepository;
    private final PackageVersionRepository versionRepository;
    private final ProjectRepository projectRepository;
    private final ProjectConfigFileRepository configFileRepository;
    private final ServiceInstanceRepository instanceRepository;
    private final DeploymentTrigger trigger;
    private final SshService sshService;
    private final ConfigFileGenerator configGenerator;
    private final DatabaseService databaseService;
    private final ProjectAccessService accessService;
    private final ProjectSubServiceManager subServiceManager;

    public DeploymentService(DeploymentRepository deploymentRepository,
                             DeploymentStepRepository stepRepository,
                             PackageVersionRepository versionRepository,
                             ProjectRepository projectRepository,
                             ProjectConfigFileRepository configFileRepository,
                             ServiceInstanceRepository instanceRepository,
                             DeploymentTrigger trigger,
                             SshService sshService,
                             ConfigFileGenerator configGenerator,
                             DatabaseService databaseService,
                             ProjectAccessService accessService,
                             ProjectSubServiceManager subServiceManager) {
        this.deploymentRepository = deploymentRepository;
        this.stepRepository = stepRepository;
        this.versionRepository = versionRepository;
        this.projectRepository = projectRepository;
        this.configFileRepository = configFileRepository;
        this.instanceRepository = instanceRepository;
        this.trigger = trigger;
        this.sshService = sshService;
        this.configGenerator = configGenerator;
        this.databaseService = databaseService;
        this.accessService = accessService;
        this.subServiceManager = subServiceManager;
    }

    /**
     * 创建部署任务并异步触发执行。
     *
     * @return 落库后（PENDING）的部署视图
     */
    @Transactional
    public DeploymentVO create(DeploymentCreateRequest req, AuthUser user) {
        // 1. 版本存在性 + 归属校验（versionId 决定项目）
        PackageVersionEntity version = versionRepository.findById(req.getVersionId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "package version not found: " + req.getVersionId()));
        long projectId = version.getProjectId();
        // 项目隔离：发起部署需为该成员且全局角色 ≥ OPS（非成员 → 404，成员但 < OPS → 403）
        accessService.assertOperate(projectId, user);
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "project not found: " + projectId));

        // 2. 同 server 串行互斥：存在非终态部署则拒绝
        if (deploymentRepository.existsByServerIdAndStatusNotIn(req.getServerId(), TERMINAL_STATUSES)) {
            throw new BizException(ErrorCode.CONFLICT,
                    "another deployment is in progress on server " + req.getServerId());
        }

        // 3. 三级兜底：请求 > project 默认 > detected 建议
        //    服务级参数（startCmd/port）优先于项目默认：1 项目 N 服务各自独立启动命令/端口。
        DetectedResult detected = parseDetected(version.getDetectedJson());
        Long serviceId = version.getServiceId();
        ProjectSubServiceEntity subService = subServiceManager.resolveEntity(serviceId);
        // 手动指定优先（前端"部署方式"下拉）；留空走既有推导。
        // 手动值白名单校验是 service 层兜底（@Pattern 只守 controller 入口，防程序化调用绕过）。
        String requestedMode = req.getMode();
        if (requestedMode != null && !requestedMode.isBlank() && !MANUAL_MODES.contains(requestedMode)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "invalid mode: " + requestedMode);
        }
        String mode = requestedMode != null && !requestedMode.isBlank()
                ? requestedMode
                : resolveMode(project, detected);
        String startCmd = firstNonBlank(
                req.getStartCmd(), subService.getStartCmd(), project.getDefaultStartCmd(),
                detected == null ? null : detected.getSuggestedStartCmd());
        if (startCmd == null) {
            if ("NGINX".equals(mode) || "DOCKER".equals(mode)) {
                startCmd = null; // 静态站/docker 部署由平台托管，无需启动命令
            } else {
                throw new BizException(ErrorCode.BAD_REQUEST, "no start command available");
            }
        }
        Integer port = firstNonNull(req.getPort(), subService.getPort(), project.getDefaultPort(),
                detected == null ? null : detected.getSuggestedPort());
        String healthPath = firstNonBlank(
                req.getHealthPath(), project.getDefaultHealthPath(),
                detected == null ? null : detected.getSuggestedHealthPath());
        if (healthPath == null) {
            healthPath = "/";
        }

        // 兜底解析完成后做最终安全校验：startCmd 不得含换行（防 heredoc 逃逸）；
        // healthPath 必须是合法 URL 路径（防 curl 命令注入）。@Pattern 只挡请求入口，
        // 这里兜住 project 默认值 / detected 建议值两条内来源。
        if (startCmd != null && (startCmd.indexOf('\n') >= 0 || startCmd.indexOf('\r') >= 0)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "startCmd must not contain line breaks");
        }
        if (!HEALTH_PATH_PATTERN.matcher(healthPath).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "invalid healthPath: " + healthPath);
        }

        // 数据库选择校验：null/0 = 不使用；-1 = 自动创建项目独立库（执行期 provision，此处放行）；
        // 其它正值必须是已登记数据库（存在性在此前置拦截，避免异步执行期才发现 404）。
        Long databaseId = req.getDatabaseId();
        if (databaseId != null && databaseId > 0) {
            databaseService.require(databaseId);
        }

        if ("DOCKER".equals(mode)
                && configFileRepository.findByServiceIdAndFileKey(serviceId, "compose").isEmpty()) {
            // 子服务未配置 compose：AI 依据项目配置 + 部署方式自动生成并入库（无 LLM 时规则模板兜底），
            // 不再硬报错阻断部署。端口取本次部署最终解析值（请求 > 服务级 > 项目默认 > 识别值），
            // 避免模板/LLM 用项目默认端口生成导致宿主机端口冲突。
            log.info("compose config missing for DOCKER deployment of service {}, auto-generating (port={})",
                    serviceId, port);
            configGenerator.ensureCompose(project, detected, version, port);
        }

        // 4. oldVersionId：当前 (project, server, service) 在线实例对应部署的 versionId（回滚目标）
        //    服务级在线唯一：同项目不同子服务在同一 server 上各自独立，互不影响。
        Long oldVersionId = instanceRepository
                .findFirstByProjectIdAndServerIdAndServiceIdAndOnlineTrue(projectId, req.getServerId(), serviceId)
                .map(ServiceInstanceEntity::getDeploymentId)
                .filter(java.util.Objects::nonNull)
                .flatMap(deploymentRepository::findById)
                .map(DeploymentEntity::getVersionId)
                .orElse(null);

        // 5. 组装并落库（PENDING）
        //    workdir 按 path_key 分叉：默认服务（pathKey=null）走旧口径
        //    /opt/deploy/{project}/{versionId}；非默认服务走 /opt/deploy/{project}/{pathKey}/{versionId}。
        //    保证 252 上已部署的存量（categraf 默认服务）行为完全不变。
        String workdir = ProjectSubServiceManager.workdir(project.getName(), subService.getPathKey(), version.getId());
        DeploymentEntity entity = new DeploymentEntity();
        entity.setProjectId(projectId);
        entity.setServiceId(serviceId);
        entity.setVersionId(version.getId());
        entity.setServerId(req.getServerId());
        entity.setStatus(DeploymentStatus.PENDING.name());
        entity.setMode(mode);
        entity.setStartCmd(startCmd);
        entity.setPort(port);
        entity.setHealthPath(healthPath);
        entity.setWorkdir(workdir);
        entity.setOldVersionId(oldVersionId);
        entity.setDatabaseId(databaseId);
        entity.setOperator(user.getUserId());
        entity.setCreatedAt(LocalDateTime.now());
        DeploymentEntity saved;
        try {
            saved = deploymentRepository.save(entity);
        } catch (DataIntegrityViolationException e) {
            // 并发下"同 server 非终态唯一"部分唯一索引兜底（existsByServerIdAndStatusNotIn 与插入间存在竞态）
            throw new BizException(ErrorCode.CONFLICT,
                    "another deployment is in progress on server " + req.getServerId());
        }

        // 6. 事务提交后异步执行（无事务上下文时直接同步执行，便于单测）
        trigger.trigger(saved.getId());
        log.info("deployment {} created (project={} server={} version={})",
                saved.getId(), projectId, req.getServerId(), version.getId());
        return DeploymentVO.from(saved);
    }

    /** 取消部署：仅 PENDING → CANCELLED。 */
    @Transactional
    public DeploymentVO cancel(Long id, AuthUser user) {
        DeploymentEntity entity = deploymentRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "deployment not found"));
        // 项目隔离：取消需对该项目有操作权
        accessService.assertOperate(entity.getProjectId(), user);
        if (!DeploymentStatus.PENDING.name().equals(entity.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "only a pending deployment can be cancelled (current=" + entity.getStatus() + ")");
        }
        DeploymentStateMachine.assertTransition(DeploymentStatus.PENDING, DeploymentStatus.CANCELLED);
        entity.setStatus(DeploymentStatus.CANCELLED.name());
        entity.setFinishedAt(LocalDateTime.now());
        deploymentRepository.save(entity);
        return DeploymentVO.from(entity);
    }

    /**
     * 重新部署：以原部署的同一批参数（versionId / serverId / startCmd / port / healthPath）
     * 创建一个全新部署任务并异步执行，用于失败后的原地重试（如修好目标机环境后重跑）。
     *
     * <p>原部署必须处于终态（进行中/排队中的部署不能重建，避免同 server 双跑）；
     * 原部署的 startCmd 原样传入（可能为 null，如 NGINX/DOCKER 模式），走 create 的
     * 三级兜底与安全校验。返回新创建的部署视图（原部署不受影响）。
     */
    @Transactional
    public DeploymentVO redeploy(Long id, AuthUser user) {
        DeploymentEntity origin = deploymentRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "deployment not found"));
        // 项目隔离：重跑需对该项目有操作权（create 内会再次 assertOperate，此处先行给出明确 404/403）
        accessService.assertOperate(origin.getProjectId(), user);
        if (!TERMINAL_STATUSES.contains(origin.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "only a finished deployment can be redeployed (current=" + origin.getStatus() + ")");
        }
        DeploymentCreateRequest req = new DeploymentCreateRequest();
        req.setVersionId(origin.getVersionId());
        req.setMode(origin.getMode());
        req.setServerId(origin.getServerId());
        req.setStartCmd(origin.getStartCmd());
        req.setPort(origin.getPort());
        req.setHealthPath(origin.getHealthPath());
        req.setDatabaseId(origin.getDatabaseId());
        return create(req, user);
    }

    /**
     * 一键版本回退：把一次（已完成的）部署所引入的版本回退到它替代的旧版本。
     *
     * <p>语义：以 origin.oldVersionId（部署前在线实例对应的版本）为目标，沿用该旧版本
     * 最近一次 SUCCESS 部署的启动配置（startCmd/port/healthPath/mode）重建一个新部署任务并
     * 异步执行。复用 {@link #create} 全链路：上传/安装/启动/验证/实例切换——create 内部会把
     * oldVersionId 重新取为当前在线版本（即 origin 部署的版本），成功后旧实例自动切回在线，
     * 形成"回退"闭环。
     *
     * <p>约束：origin 必须为终态（进行中/排队中的不能回退，避免同 server 双跑）；
     * oldVersionId 为 null（首次部署、无上一版本）→ BAD_REQUEST。
     */
    @Transactional
    public DeploymentVO rollback(Long id, AuthUser user) {
        DeploymentEntity origin = deploymentRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "deployment not found"));
        // 项目隔离：回退需对该项目有操作权（create 内会再次 assertOperate，此处先行给出明确 404/403）
        accessService.assertOperate(origin.getProjectId(), user);
        if (origin.getOldVersionId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "no previous version to roll back to");
        }
        if (!TERMINAL_STATUSES.contains(origin.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "only a finished deployment can be rolled back (current=" + origin.getStatus() + ")");
        }

        Long targetVersionId = origin.getOldVersionId();
        long projectId = origin.getProjectId();
        long serverId = origin.getServerId();

        // 旧版本最近一次 SUCCESS 部署的启动配置（与回滚 unit 重建取 startCmd 的口径一致）。
        // 找不到旧部署（理论上 oldVersionId 有值就应有历史）时 startCmd/port/healthPath 留空，
        // 交 create 的三级兜底（请求 > project 默认 > detected）补齐。
        DeploymentEntity oldDeploy = deploymentRepository
                .findFirstByProjectIdAndVersionIdAndServerIdAndStatusOrderByIdDesc(
                        projectId, targetVersionId, serverId, "SUCCESS")
                .orElse(null);

        DeploymentCreateRequest req = new DeploymentCreateRequest();
        req.setVersionId(targetVersionId);
        req.setServerId(serverId);
        req.setMode(oldDeploy == null ? null : oldDeploy.getMode());
        req.setStartCmd(oldDeploy == null ? null : oldDeploy.getStartCmd());
        req.setPort(oldDeploy == null ? null : oldDeploy.getPort());
        req.setHealthPath(oldDeploy == null ? null : oldDeploy.getHealthPath());
        req.setDatabaseId(origin.getDatabaseId());
        log.info("rollback deployment {} to version {} (project={} server={})",
                id, targetVersionId, projectId, serverId);
        return create(req, user);
    }

    public DeploymentVO get(Long id, AuthUser user) {
        DeploymentEntity entity = deploymentRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "deployment not found"));
        // 项目隔离：查看部署需对该项目可见
        accessService.assertView(entity.getProjectId(), user);
        return DeploymentVO.from(entity);
    }

    /** 部署列表（按可见项目隔离），按创建时间倒序。 */
    public List<DeploymentVO> list(AuthUser user) {
        List<Long> visibleIds = accessService.visibleProjectIds(user);
        if (visibleIds != null && visibleIds.isEmpty()) {
            return List.of();
        }
        return deploymentRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(d -> visibleIds == null || visibleIds.contains(d.getProjectId()))
                .map(DeploymentVO::from)
                .collect(Collectors.toList());
    }

    /** 某部署的步骤列表，按 seq 升序（部署不存在 → NOT_FOUND）。 */
    public List<DeploymentStepVO> getSteps(Long deploymentId, AuthUser user) {
        DeploymentEntity entity = deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "deployment not found"));
        // 项目隔离：查看步骤需对该项目可见
        accessService.assertView(entity.getProjectId(), user);
        return stepRepository.findByDeploymentIdOrderBySeq(deploymentId).stream()
                .map(DeploymentStepVO::from)
                .collect(Collectors.toList());
    }

    private static DetectedResult parseDetected(String detectedJson) {
        if (detectedJson == null || detectedJson.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(detectedJson, DetectedResult.class);
        } catch (Exception e) {
            log.warn("failed to parse detected_json, treat as absent: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 部署模式判定：project.detectedType 优先（人工可修正），其次版本识别结果。
     * STATIC_WEB → NGINX（平台托管 nginx 站点）；DOCKER → DOCKER（compose 部署）；其余 → PROCESS。
     */
    static String resolveMode(ProjectEntity project, DetectedResult detected) {
        String type = project.getDetectedType() != null
                ? project.getDetectedType()
                : (detected == null ? null : detected.getDetectedType());
        if ("STATIC_WEB".equals(type)) {
            return "NGINX";
        }
        if ("DOCKER".equals(type)) {
            return "DOCKER";
        }
        return "PROCESS";
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static Integer firstNonNull(Integer... values) {
        for (Integer v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    // ---------- 端口预检（Task：手动模式 + 端口占用检测）----------

    /** 端口预检 SSH 超时（ms）。 */
    private static final int PORT_PROBE_TIMEOUT_MS = 10_000;

    /** 推荐空闲端口从 port+1 递增的探测上限（防 65535 附近长时间空转）。 */
    private static final int FREE_PORT_SCAN_LIMIT = 100;

    /** ss 输出中占用进程字段：users:(("nginx",pid=1234,fd=6))。 */
    private static final Pattern SS_USERS_PATTERN =
            Pattern.compile("users:\\(\\(\"([^\"]+)\",pid=(\\d+)");

    /**
     * 端口预检：SSH 到目标机执行 {@code ss -lntp} 解析监听端口（只读，10s 超时）。
     *
     * <p>尽力而为语义：SSH 失败/超时/非 0 退出码 → {@code probed=false}，由前端决定放行，
     * 本方法不抛业务异常。被占时从 port+1 递增找推荐端口（上限 100 次、65535 截断）。
     */
    public PortCheckResult checkPort(Long serverId, int port) {
        CommandResult r;
        try {
            r = sshService.execute(serverId, "ss -lntp", PORT_PROBE_TIMEOUT_MS);
        } catch (Exception e) {
            log.warn("check-port ssh failed for server {}: {}", serverId, e.getMessage());
            return PortCheckResult.notProbed(port);
        }
        if (r == null || r.getTimedOut() || r.getExitCode() != 0) {
            return PortCheckResult.notProbed(port);
        }
        ListenerReport report = parseListeners(r.getStdout());
        if (!report.isListening(port)) {
            return PortCheckResult.free(port);
        }
        // 被占：补 user 信息（ss 不含 user，经 ps 查询；失败则只显示进程名+pid）
        String base = report.processInfo().get(port);
        String processInfo = base == null ? null : enrichProcessUser(serverId, base);
        Integer recommended = findFreePort(port + 1, report.ports(), FREE_PORT_SCAN_LIMIT);
        return PortCheckResult.occupied(port, processInfo, recommended);
    }

    /** 经 {@code ps -o user=} 补充占用进程的用户；失败/无 pid 时原样返回。 */
    private String enrichProcessUser(Long serverId, String baseInfo) {
        Matcher m = Pattern.compile("pid (\\d+)").matcher(baseInfo);
        if (!m.find()) {
            return baseInfo;
        }
        try {
            CommandResult ps = sshService.execute(serverId, "ps -o user= -p " + m.group(1), 5_000);
            if (ps != null && ps.getExitCode() == 0 && ps.getStdout() != null && !ps.getStdout().isBlank()) {
                String user = ps.getStdout().trim();
                // "java (pid 5678)" → "java (pid 5678, user root)"；无结尾括号时直接拼接
                if (baseInfo.endsWith(")")) {
                    return baseInfo.substring(0, baseInfo.length() - 1) + ", user " + user + ")";
                }
                return baseInfo + ", user " + user;
            }
        } catch (Exception e) {
            log.warn("check-port ps failed for server {}: {}", serverId, e.getMessage());
        }
        return baseInfo;
    }

    /**
     * 解析 {@code ss -lntp} 输出：每行 LISTEN 取第 4 列 Local Address:Port 的端口
     * （IPv4 {@code 0.0.0.0:8080}、通配 {@code *:8080}、IPv6 {@code [::]:8080} 均兼容），
     * 有 {@code users:((...))} 字段时记录"进程名 (pid N)"。异常行静默跳过。
     */
    static ListenerReport parseListeners(String ssOutput) {
        Set<Integer> ports = new LinkedHashSet<>();
        Map<Integer, String> info = new HashMap<>();
        if (ssOutput == null || ssOutput.isBlank()) {
            return new ListenerReport(ports, info);
        }
        for (String line : ssOutput.split("\\r?\\n")) {
            if (!line.startsWith("LISTEN")) {
                continue;
            }
            String[] cols = line.split("\\s+");
            if (cols.length < 4) {
                continue;
            }
            String local = cols[3];
            int idx = local.lastIndexOf(':');
            if (idx < 0 || idx == local.length() - 1) {
                continue;
            }
            int p;
            try {
                p = Integer.parseInt(local.substring(idx + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            ports.add(p);
            Matcher m = SS_USERS_PATTERN.matcher(line);
            if (m.find()) {
                info.putIfAbsent(p, m.group(1) + " (pid " + m.group(2) + ")");
            }
        }
        return new ListenerReport(ports, info);
    }

    /** 从 start 起递增找第一个空闲端口（最多 limit 次，超 65535 截断）；耗尽返回 null。 */
    static Integer findFreePort(int start, Set<Integer> busy, int limit) {
        for (int i = 0; i < limit; i++) {
            int p = start + i;
            if (p > 65535) {
                return null;
            }
            if (!busy.contains(p)) {
                return p;
            }
        }
        return null;
    }

    /** ss 监听解析结果：端口集合 + 端口→进程信息（无 users 字段时无该 key）。 */
    public record ListenerReport(Set<Integer> ports, Map<Integer, String> processInfo) {
        boolean isListening(int port) {
            return ports.contains(port);
        }
    }

    /** 端口预检结果。{@code probed=false} 表示 SSH 探测失败（前端放行，不阻断部署）。 */
    public record PortCheckResult(boolean probed, Integer port, boolean occupied,
                                  String processInfo, Integer recommendedPort) {
        static PortCheckResult notProbed(int port) {
            return new PortCheckResult(false, port, false, null, null);
        }

        static PortCheckResult free(int port) {
            return new PortCheckResult(true, port, false, null, null);
        }

        static PortCheckResult occupied(int port, String processInfo, Integer recommendedPort) {
            return new PortCheckResult(true, port, true, processInfo, recommendedPort);
        }
    }
}
