package com.ops.deploy.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.notify.NotificationEntity;
import com.ops.deploy.notify.NotificationRepository;
import com.ops.deploy.notify.WebhookNotifier;
import com.ops.deploy.project.PackageVersionEntity;
import com.ops.deploy.project.PackageVersionRepository;
import com.ops.deploy.project.ProjectConfigFileEntity;
import com.ops.deploy.project.ProjectConfigFileRepository;
import com.ops.deploy.project.ProjectEntity;
import com.ops.deploy.project.ProjectRepository;
import com.ops.deploy.project.ProjectSubServiceEntity;
import com.ops.deploy.project.ProjectSubServiceManager;
import com.ops.deploy.ssh.CommandResult;
import com.ops.deploy.ssh.SshService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 部署执行引擎（Task 6 D）：异步跑 UPLOAD → INSTALL → START → VERIFY 四个步骤。
 *
 * <p>失败处理：
 * <ul>
 *   <li>有 oldVersionId → 置 ROLLING_BACK 并执行 ROLLBACK 步（按 mode 分支：PROCESS 把 unit
 *       改写指向旧版本 workdir 后 daemon-reload + restart；NGINX 软链切回旧版本 + reload；
 *       DOCKER 旧版本 compose down 后 up）。回滚成功 → ROLLED_BACK；回滚失败 → NEEDS_ATTENTION
 *       （error_summary 同时记录原始错误与回滚错误，合计 ≤1024 截断）。</li>
 *   <li>无 oldVersionId → 直接置 FAILED（见 {@link #handleFailure}）。</li>
 *   <li>最外层兜底：任何未预期的 RuntimeException → 非终态置 NEEDS_ATTENTION。</li>
 * </ul>
 *
 * <p>状态迁移经 {@link DeploymentStateMachine#assertTransition} 守卫（乐观转换）；
 * 唯一例外是"无旧版本失败"的 FAILED——转换表中 FAILED 无入边（X→FAILED 非法，且已被
 * {@code DeploymentStateMachineTest} 全矩阵锁定），故该处绕过守卫直接落库。
 *
 * <p>本类是独立 Bean，{@link #run(Long)} 上的 {@code @Async} 经代理生效（由
 * {@link DeploymentTrigger} 在事务提交后调用）。单测直接 {@code new} 并以同步方式调用。
 */
@Component
public class DeploymentRunner {

    private static final Logger log = LoggerFactory.getLogger(DeploymentRunner.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** error_summary 列上限（deployment.error_summary length=1024）。 */
    static final int ERROR_SUMMARY_MAX = 1024;
    /** log_text 单步上限（512KB）。 */
    static final int LOG_TEXT_MAX = 512 * 1024;

    private final DeploymentRepository deploymentRepository;
    private final DeploymentStepRepository stepRepository;
    private final ServiceInstanceService instanceService;
    private final UploadStepExecutor uploadExecutor;
    private final InstallStepExecutor installExecutor;
    private final StartStepExecutor startExecutor;
    private final NginxStartExecutor nginxExecutor;
    private final DockerStartExecutor dockerExecutor;
    private final VerifyStepExecutor verifyExecutor;
    private final ProjectRepository projectRepository;
    private final PackageVersionRepository versionRepository;
    private final ProjectConfigFileRepository configFileRepository;
    private final ProcessConfigOverwriteExecutor configOverwriteExecutor;
    private final SshService sshService;
    private final DeploymentDocumentService documentService;
    private final DatabaseProvisioner databaseProvisioner;
    private final ProjectSubServiceManager subServiceManager;
    private final WebhookNotifier webhookNotifier;
    private final NotificationRepository notificationRepository;
    private final DeploymentMetrics deploymentMetrics;

    public DeploymentRunner(DeploymentRepository deploymentRepository,
                            DeploymentStepRepository stepRepository,
                            ServiceInstanceService instanceService,
                            UploadStepExecutor uploadExecutor,
                            InstallStepExecutor installExecutor,
                            StartStepExecutor startExecutor,
                            NginxStartExecutor nginxExecutor,
                            DockerStartExecutor dockerExecutor,
                            VerifyStepExecutor verifyExecutor,
                            ProjectRepository projectRepository,
                            PackageVersionRepository versionRepository,
                            ProjectConfigFileRepository configFileRepository,
                            ProcessConfigOverwriteExecutor configOverwriteExecutor,
                            SshService sshService,
                            DeploymentDocumentService documentService,
                            DatabaseProvisioner databaseProvisioner,
                            ProjectSubServiceManager subServiceManager,
                            WebhookNotifier webhookNotifier,
                            NotificationRepository notificationRepository,
                            DeploymentMetrics deploymentMetrics) {
        this.deploymentRepository = deploymentRepository;
        this.stepRepository = stepRepository;
        this.instanceService = instanceService;
        this.uploadExecutor = uploadExecutor;
        this.installExecutor = installExecutor;
        this.startExecutor = startExecutor;
        this.nginxExecutor = nginxExecutor;
        this.dockerExecutor = dockerExecutor;
        this.verifyExecutor = verifyExecutor;
        this.projectRepository = projectRepository;
        this.versionRepository = versionRepository;
        this.configFileRepository = configFileRepository;
        this.configOverwriteExecutor = configOverwriteExecutor;
        this.sshService = sshService;
        this.documentService = documentService;
        this.databaseProvisioner = databaseProvisioner;
        this.subServiceManager = subServiceManager;
        this.webhookNotifier = webhookNotifier;
        this.notificationRepository = notificationRepository;
        this.deploymentMetrics = deploymentMetrics;
    }

    /**
     * 异步执行一个部署任务。
     *
     * <p>最外层捕获未预期的 {@link RuntimeException}：若部署仍处非终态，置 NEEDS_ATTENTION
     * （error_summary 截断 ≤1024），避免任务悬挂在中间态。
     */
    @Async("deployRunnerExecutor")
    public void run(Long deploymentId) {
        DeploymentEntity d = deploymentRepository.findById(deploymentId).orElse(null);
        if (d == null) {
            log.warn("deployment {} not found, skip run", deploymentId);
            return;
        }
        try {
            doRun(d);
        } catch (RuntimeException e) {
            log.error("deployment {} run failed unexpectedly: {}", deploymentId, e.getMessage(), e);
            markNeedsAttentionIfActive(d, e);
        }
    }

    /** 真正的执行流程（被 {@link #run} 包裹兜底）。 */
    private void doRun(DeploymentEntity d) {
        DeploymentStatus initial = DeploymentStatus.valueOf(d.getStatus());
        if (DeploymentStateMachine.isTerminal(initial)) {
            log.info("deployment {} already terminal ({}) before run, skip", d.getId(), initial);
            return;
        }

        long serverId = d.getServerId();
        String workdir = d.getWorkdir();
        ProjectEntity project = projectRepository.findById(d.getProjectId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "project not found: " + d.getProjectId()));
        String projectName = project.getName();
        // 解析 pathKey（默认服务 null → 旧口径 workdir/unit）
        String pathKey = resolvePathKey(d.getServiceId());

        PackageVersionEntity version = versionRepository.findById(d.getVersionId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "package version not found: " + d.getVersionId()));
        String fileName = version.getFileName();

        if (d.getStartedAt() == null) {
            d.setStartedAt(LocalDateTime.now());
            deploymentRepository.save(d);
        }

        // ---- 1. UPLOAD ----
        transition(d, DeploymentStatus.UPLOADING);
        DeploymentStepEntity up = beginStep(d, 1, DeploymentStepName.UPLOAD);
        try {
            Map<String, Object> detail = uploadExecutor.execute(
                    serverId, workdir, fileName, version.getStoragePath(),
                    version.getSizeBytes(), version.getSha256());
            finishStep(up, true, toJson(detail), null);
        } catch (RuntimeException e) {
            finishStep(up, false, null, e.getMessage());
            handleFailure(d, e, projectName);
            return;
        }

        // ---- 2. INSTALL ----
        transition(d, DeploymentStatus.INSTALLING);
        DeploymentStepEntity in = beginStep(d, 2, DeploymentStepName.INSTALL);
        try {
            Map<String, Object> detail = installExecutor.execute(serverId, workdir, fileName);
            finishStep(in, true, toJson(detail), null);
        } catch (RuntimeException e) {
            finishStep(in, false, null, e.getMessage());
            handleFailure(d, e, projectName);
            return;
        }

        // ---- 3. START（按 mode 分支） ----
        transition(d, DeploymentStatus.STARTING);
        DeploymentStepEntity st = beginStep(d, 3, DeploymentStepName.START);
        try {
            Map<String, Object> detail = executeStartStep(d, projectName, fileName, pathKey);
            finishStep(st, true, toJson(detail), null);
        } catch (RuntimeException e) {
            finishStep(st, false, null, e.getMessage());
            handleFailure(d, e, projectName);
            return;
        }

        // ---- 4. VERIFY（NGINX/DOCKER 模式 port 可能为 null，此时跳过 curl 健康检查） ----
        transition(d, DeploymentStatus.VERIFYING);
        DeploymentStepEntity vf = beginStep(d, 4, DeploymentStepName.VERIFY);
        try {
            Map<String, Object> detail;
            if (d.getPort() == null) {
                detail = Map.of("skipped", true,
                        "reason", "no port (mode=" + d.getMode() + "), skipping http health check");
            } else {
                // PROCESS 模式传 systemd 单元名：无 HTTP 端口的服务（监控 agent 等）curl 连接级失败时，
                // VERIFY 退化为进程存活校验（SubState=running 即通过），而非一律判失败触发回滚。
                // NGINX/DOCKER 无 systemd 单元（站点由 nginx 托管 / 容器化），传 null 不降级。
                String verifyUnit = "PROCESS".equals(d.getMode())
                        ? ProjectSubServiceManager.unitName(projectName, pathKey)
                        : null;
                detail = verifyExecutor.execute(serverId, d.getPort(), d.getHealthPath(), verifyUnit);
            }
            finishStep(vf, true, toJson(detail), null);
        } catch (RuntimeException e) {
            finishStep(vf, false, null, e.getMessage());
            handleFailure(d, e, projectName);
            return;
        }

        // ---- 成功 ----
        transition(d, DeploymentStatus.SUCCESS);
        d.setFinishedAt(LocalDateTime.now());
        deploymentRepository.save(d);
        recordTerminalMetric(d, DeploymentStatus.SUCCESS);
        instanceService.onSuccess(d);
        // 部署成功后自动生成部署/维护文档（Task 8）。
        // 文档生成失败绝不影响部署成功主流程：try-catch 隔离，仅 log.warn。
        try {
            documentService.generate(d.getId());
        } catch (RuntimeException e) {
            log.warn("auto-generate deployment document failed for deployment {}: {}",
                    d.getId(), e.getMessage(), e);
        }
    }

    /**
     * 步骤失败处理：有旧版本 → 回滚；无旧版本 → 直接置 FAILED。
     */
    private void handleFailure(DeploymentEntity d, RuntimeException original, String projectName) {
        Long oldVersionId = d.getOldVersionId();
        if (oldVersionId == null) {
            // 无旧版本可回滚 → 失败终态 FAILED。
            // 注意：状态机转换表中 FAILED 无入边（X→FAILED 非法，且已被
            // DeploymentStateMachineTest 全矩阵断言锁定），故此处绕过 assertTransition 直接落库，
            // 不改动状态机契约。
            d.setStatus(DeploymentStatus.FAILED.name());
            d.setErrorSummary(truncate(toMessage(original), ERROR_SUMMARY_MAX));
            d.setFinishedAt(LocalDateTime.now());
            deploymentRepository.save(d);
            recordTerminalMetric(d, DeploymentStatus.FAILED);
            notifyFailure(d, projectName, "部署失败",
                    "部署失败: " + d.getErrorSummary());
            return;
        }

        // 有旧版本 → 回滚
        transition(d, DeploymentStatus.ROLLING_BACK);
        DeploymentStepEntity rb = beginStep(d, 5, DeploymentStepName.ROLLBACK);
        String originalMsg = toMessage(original);
        try {
            doRollback(d, projectName);
            finishStep(rb, true, null, null);
            transition(d, DeploymentStatus.ROLLED_BACK);
            d.setFinishedAt(LocalDateTime.now());
            deploymentRepository.save(d);
            recordTerminalMetric(d, DeploymentStatus.ROLLED_BACK);
            instanceService.onRolledBack(d);
            notifyFailure(d, projectName, "部署回滚",
                    "部署失败已回滚到旧版本: " + originalMsg);
        } catch (RuntimeException rollbackEx) {
            finishStep(rb, false, null, toMessage(rollbackEx));
            transition(d, DeploymentStatus.NEEDS_ATTENTION);
            d.setErrorSummary(truncate(originalMsg + " | rollback failed: " + toMessage(rollbackEx),
                    ERROR_SUMMARY_MAX));
            d.setFinishedAt(LocalDateTime.now());
            deploymentRepository.save(d);
            recordTerminalMetric(d, DeploymentStatus.NEEDS_ATTENTION);
            notifyFailure(d, projectName, "部署需处理",
                    "部署失败且回滚失败，需人工处理: " + d.getErrorSummary());
        }
    }

    /**
     * 部署失败类终态（FAILED / ROLLED_BACK / NEEDS_ATTENTION）的告警：
     * 同步写一条站内通知（WARN，供前端铃铛）+ 经 {@link WebhookNotifier} 推送 webhook。
     * 成功/普通 ROLLING_BACK 中间态不告警。
     *
     * <p>告警失败绝不影响部署主流程：通知落库或 webhook 推送抛异常均被吞掉仅 log.warn
     * （单测直接 new 时两依赖可能为 null，同样走此兜底）。
     */
    private void notifyFailure(DeploymentEntity d, String projectName, String title, String detail) {
        String message = truncate(String.format("项目 %s 部署 %d %s",
                projectName, d.getId(), detail), 1024);
        try {
            if (notificationRepository != null) {
                NotificationEntity n = new NotificationEntity();
                n.setLevel("WARN");
                n.setTitle(title);
                n.setMessage(message);
                n.setProjectId(d.getProjectId());
                notificationRepository.save(n);
            }
        } catch (RuntimeException e) {
            log.warn("deploy failure notification save failed for deployment {}: {}",
                    d.getId(), e.getMessage());
        }
        if (webhookNotifier != null) {
            try {
                webhookNotifier.notify("WARN", title, message);
            } catch (RuntimeException e) {
                log.warn("webhook notify failed for deployment {}: {}", d.getId(), e.getMessage());
            }
        }
    }

    /**
     * 记录部署终态指标（Prometheus：deployment_terminal_total / deployment_duration）。
     * 指标失败绝不影响部署主流程；单测直接 new 时 metrics 可能为 null，同样兜底。
     */
    private void recordTerminalMetric(DeploymentEntity d, DeploymentStatus status) {
        if (deploymentMetrics == null) {
            return;
        }
        try {
            java.time.Duration duration = (d.getStartedAt() != null && d.getFinishedAt() != null)
                    ? java.time.Duration.between(d.getStartedAt(), d.getFinishedAt())
                    : null;
            deploymentMetrics.record(status.name(), duration);
        } catch (RuntimeException e) {
            log.warn("record deployment metric failed for deployment {}: {}", d.getId(), e.getMessage());
        }
    }

    /**
     * 回滚动作（按 mode 分支）：
     * <ul>
     *   <li>PROCESS：把 unit 文件内容改写为指向旧版本 workdir
     *       （{@code /opt/deploy/{projectName}/{oldVersionId}}），再 daemon-reload + restart；</li>
     *   <li>NGINX：软链 {@code current} 切回旧版本 extract + reload（站点配置 root 恒指软链）；</li>
     *   <li>DOCKER：旧版本 compose {@code down} 清容器后 {@code up -d}。</li>
     * </ul>
     *
     * <p>PROCESS 的 startCmd 取"旧版本最近一次 SUCCESS 部署"的值（恢复旧版本当时上线的启动配置，
     * 含端口等内联环境变量）；查不到该记录时才回退当前部署的 startCmd。
     * 不能直接沿用当前失败部署的 startCmd——它可能带新端口（如 {@code PORT=8091}），
     * 回滚后进程 bind 失败会进入崩溃循环（缺陷9：E2E 实锤回滚后 crash-loop，8001 未恢复）。
     * 失败抛 {@link BizException}(SSH_ERROR)。
     */
    private void doRollback(DeploymentEntity d, String projectName) {
        long serverId = d.getServerId();
        String pathKey = resolvePathKey(d.getServiceId());
        // 旧版本 workdir 按同 pathKey 口径计算（默认服务 → 旧路径；非默认 → 新路径）
        String oldWorkdir = ProjectSubServiceManager.workdir(projectName, pathKey, d.getOldVersionId());
        String mode = d.getMode() == null ? "PROCESS" : d.getMode();
        switch (mode) {
            case "NGINX" -> {
                String rbCmd = NginxStartExecutor.buildRollbackCommand(projectName, pathKey, oldWorkdir);
                CommandResult r = sshService.execute(serverId, rbCmd);
                if (r.getTimedOut() || r.getExitCode() != 0) {
                    throw new BizException(ErrorCode.SSH_ERROR,
                            "rollback nginx symlink+reload failed (exit=" + r.getExitCode() + "): "
                                    + r.getStdout() + " " + r.getStderr());
                }
            }
            case "DOCKER" -> {
                // 项目配置了 Dockerfile（file_key=dockerfile，含 AI 自动补生成）时先补写到旧版本
                // extract：compose 走 build.context: ./extract 本地构建，回滚 up 同样需要构建源
                String rbDockerfile = configFileRepository
                        .findByServiceIdAndFileKey(d.getServiceId(), "dockerfile")
                        .map(ProjectConfigFileEntity::getContent)
                        .orElse(null);
                if (rbDockerfile != null && !rbDockerfile.isBlank()) {
                    String extractDir = ShellQuoter.quote(oldWorkdir + "/extract");
                    String dfCmd = "mkdir -p " + extractDir + " && "
                            + DockerStartExecutor.buildWriteCommand(oldWorkdir,
                                    DockerStartExecutor.dockerfilePath(oldWorkdir),
                                    rbDockerfile, "DOCKERFILE_EOF_");
                    CommandResult dw = sshService.execute(serverId, dfCmd);
                    if (dw.getTimedOut() || dw.getExitCode() != 0) {
                        throw new BizException(ErrorCode.SSH_ERROR,
                                "rollback write extract/Dockerfile failed (exit=" + dw.getExitCode() + "): "
                                        + dw.getStderr());
                    }
                }
                String rbCmd = DockerStartExecutor.buildRollbackCommand(oldWorkdir);
                CommandResult r = sshService.executeWithRetry(serverId, rbCmd, 300_000, 2);
                if (r.getTimedOut() || r.getExitCode() != 0) {
                    String reason = r.getTimedOut() ? "timeout" : "exit=" + r.getExitCode();
                    throw new BizException(ErrorCode.SSH_ERROR,
                            "rollback docker-compose down+up failed (" + reason + "): " + r.getStderr());
                }
            }
            default -> doRollbackProcess(d, serverId, projectName, pathKey, oldWorkdir);
        }
    }

    /** PROCESS 模式回滚：unit 改写指向旧 workdir + daemon-reload + enable + restart。 */
    private void doRollbackProcess(DeploymentEntity d, long serverId,
                                   String projectName, String pathKey, String oldWorkdir) {
        String unitName = ProjectSubServiceManager.unitName(projectName, pathKey);
        String oldStartCmd = deploymentRepository
                .findFirstByProjectIdAndVersionIdAndServerIdAndStatusOrderByIdDesc(
                        d.getProjectId(), d.getOldVersionId(), d.getServerId(),
                        DeploymentStatus.SUCCESS.name())
                .map(DeploymentEntity::getStartCmd)
                .orElse(null);
        String startCmd = oldStartCmd != null ? oldStartCmd : d.getStartCmd();
        // 回滚 unit 的运行目录须按旧版本的包类型解析（jar/war 落 workdir 根，其余落 extract），
        // 与旧版本当时的 install 落盘位置一致，相对路径 startCmd 才能命中文件
        String oldFileName = versionRepository.findById(d.getOldVersionId())
                .map(PackageVersionEntity::getFileName)
                .orElse(null);
        String oldWorkingDir = InstallStepExecutor.resolveRuntimeDir(oldWorkdir, oldFileName);
        String content = StartStepExecutor.buildUnitContent(unitName, oldWorkingDir, startCmd);
        String unitPath = "/etc/systemd/system/" + unitName;
        String writeCmd = StartStepExecutor.buildWriteUnitCommand(unitPath, content);
        CommandResult w = sshService.execute(serverId, writeCmd);
        if (w.getTimedOut() || w.getExitCode() != 0) {
            throw new BizException(ErrorCode.SSH_ERROR,
                    "rollback write unit failed (exit=" + w.getExitCode() + "): " + w.getStderr());
        }
        String startLine = StartStepExecutor.buildStartCommand(unitName);
        CommandResult s = sshService.execute(serverId, startLine);
        if (s.getTimedOut() || s.getExitCode() != 0) {
            String reason = s.getTimedOut() ? "timeout" : "exit=" + s.getExitCode();
            throw new BizException(ErrorCode.SSH_ERROR,
                    "rollback restart " + unitName + " failed (" + reason + "): " + s.getStderr());
        }
    }

    /**
     * START 步按 mode 分发：
     * PROCESS → systemd unit；NGINX → 站点配置 + 软链 + reload（自定义 file_key=nginx 配置优先）；
     * DOCKER → 下发 compose/.env + up -d（compose 必填，缺失在 create 已拦截，此处兜底报错）。
     *
     * <p>数据库注入：若部署选择了数据库（databaseId 非 null/0），先经 {@link DatabaseProvisioner}
     * 解析为连接信息（-1 触发自动建库并回写真实 id），再把连接环境变量注入：
     * DOCKER 模式追加到 compose 各 service 的 {@code environment}；PROCESS 模式以 {@code KEY=VAL}
     * 内联前缀拼到 startCmd（由 {@code StartStepExecutor#splitInlineEnv} 转成 systemd {@code Environment=}）。
     */
    private Map<String, Object> executeStartStep(DeploymentEntity d, String projectName, String fileName, String pathKey) {
        long serverId = d.getServerId();
        String workdir = d.getWorkdir();
        String mode = d.getMode() == null ? "PROCESS" : d.getMode();
        Long serviceId = d.getServiceId();

        // 数据库解析（可能触发自动建库 + 回写真实 id）。NGINX 纯静态站不注入（无应用进程读 env）。
        DatabaseProvisioner.DbRef dbRef = null;
        if (!"NGINX".equals(mode) && d.getDatabaseId() != null && d.getDatabaseId() != 0L) {
            dbRef = databaseProvisioner.resolve(d.getDatabaseId(), serverId, projectName);
            if (dbRef != null && dbRef.databaseId() != null
                    && !dbRef.databaseId().equals(d.getDatabaseId())) {
                // 自动建库路径：把哨兵 -1 回写为 database_info 真实 id，供后续 redeploy/回滚复用
                d.setDatabaseId(dbRef.databaseId());
                deploymentRepository.save(d);
            }
        }

        switch (mode) {
            case "NGINX" -> {
                String customConf = configFileRepository
                        .findByServiceIdAndFileKey(serviceId, "nginx")
                        .map(ProjectConfigFileEntity::getContent)
                        .orElse(null);
                Map<String, Object> detail = nginxExecutor.execute(serverId, projectName, pathKey, d.getPort(), workdir, customConf);
                // 把实际下发的站点配置回写项目配置（file_key=nginx）：首次部署用默认配置时，
                // 用户也能在配置列表里看到/修改 nginx.conf（下次部署生效）。自定义配置时
                // 回写的是其原文（内容一致，幂等无害）。
                writeBackNginxConf(d.getProjectId(), serviceId, detail.get("confContent"));
                return detail;
            }
            case "DOCKER" -> {
                ProjectConfigFileEntity composeEntity = configFileRepository
                        .findByServiceIdAndFileKey(serviceId, "compose")
                        .orElse(null);
                String compose = composeEntity == null ? null : composeEntity.getContent();
                if (compose == null) {
                    throw new BizException(ErrorCode.BAD_REQUEST,
                            "DOCKER deployment requires a compose config file (file_key=compose) on the service");
                }
                String env = configFileRepository
                        .findByServiceIdAndFileKey(serviceId, "env")
                        .map(ProjectConfigFileEntity::getContent)
                        .orElse(null);
                if (dbRef != null) {
                    compose = injectDbEnvIntoCompose(compose, dbRef.toEnvVars());
                    // .env（file_key=env）同样同步数据库连接：compose 未显式声明 environment 的
                    // 服务（或应用直接读 .env 变量）依赖它拿到正确的库地址/账密
                    env = syncDbEnvIntoDotenv(env, dbRef.toEnvVars());
                }
                // 项目配置的 Dockerfile（含 AI 自动补生成，file_key=dockerfile）：compose 走
                // build.context: ./extract 本地构建时需要，部署时写入 {workdir}/extract/Dockerfile
                String dockerfile = configFileRepository
                        .findByServiceIdAndFileKey(serviceId, "dockerfile")
                        .map(ProjectConfigFileEntity::getContent)
                        .orElse(null);
                // DOCKER 模式同样执行包内文件覆盖（requirements.txt / conf/*.toml 等 pathKey 配置
                // 写入 {workdir}/extract/）：compose 走 build.context: ./extract 本地构建时，
                // 覆盖内容必须先进入构建上下文，否则 --build 出的镜像仍是包内旧配置
                // （szhmh 缺陷回归：requirements.txt 改了 PyJWT 但没进镜像，容器崩溃循环）。
                Map<String, Object> overwriteDetail =
                        configOverwriteExecutor.execute(serverId, workdir,
                                configFileRepository.findByServiceId(serviceId));
                Map<String, Object> dockerDetail = dockerExecutor.execute(serverId, workdir, compose, env, dockerfile, d.getPort());
                // 包成可变 map 以附加覆盖明细（execute 返回的 map 可能不可变，如单测的 Map.of）
                Map<String, Object> detail = new LinkedHashMap<>(dockerDetail);
                detail.put("configOverwrites", overwriteDetail);
                // 端口避让后实际生效的宿主机端口回写 deployment，后续 VERIFY 健康检查用该端口
                Object rp = detail.get("resolvedPort");
                Integer originalPort = d.getPort();
                if (rp instanceof Integer resolved) {
                    if (originalPort != null && resolved != originalPort) {
                        d.setPort(resolved);
                        deploymentRepository.save(d);
                    }
                    // 同步回写项目配置 compose 的 host 端口（如 "8080:8080" → "8082:8080"），
                    // 保证 UI 展示与部署实际生效端口一致。不能与"deployment.port 变化"绑死：
                    // deployment.port 可能已在上轮避让被回写为 8082，而项目配置 compose 仍是 8080，
                    // 此时 deployment 端口不变但配置漂移，同样需要回写（幂等：已一致时 no-op）。
                    writeBackProjectComposePort(composeEntity, originalPort, resolved);
                }
                return detail;
            }
            default -> {
                String unitName = ProjectSubServiceManager.unitName(projectName, pathKey);
                String startCmd = d.getStartCmd();
                if (dbRef != null) {
                    startCmd = prependDbEnv(startCmd, dbRef.toEnvVars());
                }
                // 方案 C：PROCESS 模式先按相对路径覆盖包内配置文件（解压后、启动前），
                // 使服务读到按环境定制的配置；4 个特殊 key 不含 '/'，由 pathKeys 过滤掉
                List<ProjectConfigFileEntity> allConfigs = configFileRepository.findByServiceId(serviceId);
                Map<String, Object> overwriteDetail = configOverwriteExecutor.execute(serverId, workdir, allConfigs);
                Map<String, Object> startDetail = new java.util.LinkedHashMap<>(
                        startExecutor.execute(serverId, unitName, workdir, fileName, startCmd));
                startDetail.put("configOverwrites", overwriteDetail);
                return startDetail;
            }
        }
    }

    /**
     * 把数据库连接环境变量注入 compose 各 service 的 {@code environment}（幂等：同名 key 覆盖旧值）。
     *
     * <p>用 snakeyaml 解析为对象树后注入再序列化回文本（相比纯文本行处理，能正确处理
     * environment 的三种形态：块映射 {@code KEY: VAL}、行内列表 {@code - KEY=VAL}、缺失需新建）。
     * 解析失败（非标准 compose 文本）时原样返回——数据库注入是增强而非必须，交由
     * docker-compose 自身校验，不因其失败阻断部署主流程。
     *
     * <p>注意：注入只作用于落盘到 workdir 的运行时 compose（{@code dockerExecutor.execute} 写入并
     * up -d），不改写项目配置里的 compose 原文，故序列化产生的格式/引号风格变化无副作用。
     */
    static String injectDbEnvIntoCompose(String compose, Map<String, String> dbEnv) {
        if (compose == null || dbEnv == null || dbEnv.isEmpty()) {
            return compose;
        }
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object root;
        try {
            root = yaml.load(compose);
        } catch (RuntimeException e) {
            log.warn("inject db env: compose yaml parse failed, skip injection: {}", e.getMessage());
            return compose;
        }
        if (!(root instanceof Map)) {
            return compose;
        }
        Map<?, ?> rootMap = (Map<?, ?>) root;
        Object servicesObj = rootMap.get("services");
        if (!(servicesObj instanceof Map)) {
            return compose;
        }
        Map<?, ?> services = (Map<?, ?>) servicesObj;
        for (Object svc : services.values()) {
            if (!(svc instanceof Map)) {
                continue;
            }
            Map<String, Object> service = (Map<String, Object>) svc;
            Object envObj = service.get("environment");
            if (envObj instanceof Map) {
                Map<String, Object> env = (Map<String, Object>) envObj;
                for (Map.Entry<String, String> e : dbEnv.entrySet()) {
                    env.put(e.getKey(), e.getValue());
                }
            } else if (envObj instanceof List) {
                List<Object> envList = (List<Object>) envObj;
                for (Map.Entry<String, String> e : dbEnv.entrySet()) {
                    String key = e.getKey();
                    envList.removeIf(it -> it instanceof String s && s.startsWith(key + "="));
                    envList.add(key + "=" + e.getValue());
                }
            } else {
                service.put("environment", new LinkedHashMap<>(dbEnv));
            }
        }
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setIndent(2);
        opts.setWidth(4096);
        return new Yaml(opts).dump(root);
    }

    /**
     * 把数据库连接环境变量以 {@code KEY=VAL} 内联前缀拼到 startCmd（PROCESS 模式）。
     * {@code StartStepExecutor#splitInlineEnv} 会把这些前导 {@code KEY=VAL} token 提取为 systemd
     * {@code Environment=} 指令，剩余部分作为 {@code ExecStart=}。
     *
     * <p>startCmd 本身可能已带内联 env（如 {@code PORT=8001 python app.py}），平台注入的数据库
     * env 拼在其前，二者同属前导 token 序列、都会被提取为 {@code Environment=}。若应用启动命令
     * 已显式定义同名 key（如手写 {@code DB_HOST=...}，罕见），按 systemd"后写生效"语义用户的
     * 内联值优先——视为用户对平台注入的显式覆盖。startCmd 为 null（不应发生，create 已兜底）时
     * 仅返回 env 串。
     */
    static String prependDbEnv(String startCmd, Map<String, String> dbEnv) {
        if (dbEnv == null || dbEnv.isEmpty()) {
            return startCmd;
        }
        StringBuilder envs = new StringBuilder();
        for (Map.Entry<String, String> e : dbEnv.entrySet()) {
            if (envs.length() > 0) {
                envs.append(' ');
            }
            envs.append(e.getKey()).append('=').append(e.getValue());
        }
        if (startCmd == null || startCmd.isBlank()) {
            return envs.toString();
        }
        return envs + " " + startCmd;
    }

    /**
     * 把数据库连接变量同步进 .env（file_key=env）文本：按行解析 {@code KEY=VALUE}（兼容
     * {@code export KEY=VALUE} 前缀与任意引号），key 命中 dbEnv 的按原行引号风格覆写值，
     * 未命中的 key 追加到文末。纯静态便于单测。
     *
     * <p>动机：compose 里用 {@code env_file: .env}（未逐条声明 environment）或应用进程直接读
     * .env 的部署形态，数据库注入到 compose {@code environment} 不会覆盖 .env 里的旧值
     * （应用读到的还是 .env 的 host/port/账密），导致连错库。把 dbEnv 同步进 .env 让两种
     * 注入路径一致生效。
     *
     * <p>dbEnv 为 null/空、env 为 null/空时原样返回（无库或无 .env 都不动）。
     */
    static String syncDbEnvIntoDotenv(String env, Map<String, String> dbEnv) {
        if (env == null || env.isBlank() || dbEnv == null || dbEnv.isEmpty()) {
            return env;
        }
        StringBuilder out = new StringBuilder();
        java.util.Set<String> handled = new java.util.HashSet<>();
        for (String line : env.split("\n", -1)) {
            String trimmed = line.strip();
            String body = trimmed;
            String prefix = "";
            if (body.startsWith("export ")) {
                prefix = "export ";
                body = body.substring(7);
            }
            int eq = body.indexOf('=');
            if (!trimmed.startsWith("#") && eq > 0) {
                String key = body.substring(0, eq).strip();
                if (dbEnv.containsKey(key)) {
                    String value = dbEnv.get(key);
                    String quote = value.isEmpty() ? "" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
                    out.append(prefix).append(key).append('=').append(quote).append('\n');
                    handled.add(key);
                    continue;
                }
            }
            out.append(line).append('\n');
        }
        // 去掉尾部多余空行（split(-1) 可能产生结尾空串），再追加未命中的 dbEnv 键
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n'
                && out.charAt(out.length() - 2) == '\n') {
            out.setLength(out.length() - 1);
        }
        for (Map.Entry<String, String> e : dbEnv.entrySet()) {
            if (handled.contains(e.getKey())) {
                continue;
            }
            String value = e.getValue() == null ? "" : e.getValue();
            String quote = value.isEmpty() ? "" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
            out.append(e.getKey()).append('=').append(quote).append('\n');
        }
        return out.toString();
    }

    /**
     * 端口避让后回写项目配置 compose 的 host 端口：把 host 侧等于原锚点（取
     * {@code min(compose 首个 host 端口, 部署原端口)}，与 {@code DockerStartExecutor}
     * 的避让锚点同口径）的映射重写为避让后的端口（如 {@code "8080:8080" → "8082:8080"}，
     * 容器侧不动），并刷新 updated_at 落库。改写失败（无可识别的端口映射）时仅记日志，
     * 不影响部署主流程（落盘 compose 已按避让端口写入并成功 up -d）。
     */
    private void writeBackProjectComposePort(ProjectConfigFileEntity composeEntity,
                                             Integer originalPort, int resolved) {
        if (composeEntity == null || originalPort == null) {
            return;
        }
        Integer composeHost = DockerStartExecutor.extractHostPort(composeEntity.getContent());
        int anchor = composeHost != null ? Math.min(composeHost, originalPort) : originalPort;
        String rewritten = DockerStartExecutor.rewriteComposePort(composeEntity.getContent(), anchor, resolved);
        if (rewritten == null) {
            log.warn("project compose has no recognizable port mapping for host port {}, skip config write-back", anchor);
            return;
        }
        composeEntity.setContent(rewritten);
        composeEntity.setUpdatedAt(LocalDateTime.now());
        configFileRepository.save(composeEntity);
        log.info("project compose host port write-back: {} -> {}", anchor, resolved);
    }

    /**
     * NGINX 部署后回写站点配置到项目配置（file_key=nginx）：把本次实际下发的站点配置全文
     * （用户自定义或默认生成）upsert 到该子服务，使其出现在配置列表可编辑——首次部署
     * 自动生成的默认配置也能被用户看到并修改（修改后下次部署生效）。
     *
     * <p>幂等：已存在且内容一致时不落库；失败仅记日志，不影响部署主流程（配置已下发成功）。
     */
    private void writeBackNginxConf(Long projectId, Long serviceId, Object confContent) {
        if (!(confContent instanceof String content) || content.isBlank()) {
            return;
        }
        try {
            configFileRepository.findByServiceIdAndFileKey(serviceId, "nginx")
                    .ifPresentOrElse(
                            e -> {
                                if (!content.equals(e.getContent())) {
                                    e.setContent(content);
                                    e.setUpdatedAt(LocalDateTime.now());
                                    configFileRepository.save(e);
                                }
                            },
                            () -> {
                                ProjectConfigFileEntity e = new ProjectConfigFileEntity();
                                e.setProjectId(projectId);
                                e.setServiceId(serviceId);
                                e.setFileKey("nginx");
                                e.setContent(content);
                                e.setCreatedAt(LocalDateTime.now());
                                e.setUpdatedAt(LocalDateTime.now());
                                configFileRepository.save(e);
                            });
        } catch (RuntimeException e) {
            log.warn("nginx conf write-back failed (service {}): {}", serviceId, e.getMessage());
        }
    }

    /** 解析部署所属子服务的 pathKey（默认服务 → null，走旧口径 workdir/unit）。 */
    private String resolvePathKey(Long serviceId) {
        return subServiceManager.resolveEntity(serviceId).getPathKey();
    }

    /** 守卫状态迁移并落库（乐观转换：先查当前态、assertTransition、再置新态）。 */
    private void transition(DeploymentEntity d, DeploymentStatus to) {
        DeploymentStatus from = DeploymentStatus.valueOf(d.getStatus());
        DeploymentStateMachine.assertTransition(from, to);
        d.setStatus(to.name());
        deploymentRepository.save(d);
    }

    /**
     * 最外层兜底：非终态置 NEEDS_ATTENTION（直接落库，不经过状态机——最后手段）。
     * 自保护：解析脏状态 / 兜底 save 本身失败都不允许逸出 run()（否则任务永久悬挂）。
     */
    private void markNeedsAttentionIfActive(DeploymentEntity d, RuntimeException e) {
        try {
            DeploymentStatus cur = parseStatus(d.getStatus());
            if (cur == null || DeploymentStateMachine.isTerminal(cur)) {
                return;
            }
            d.setStatus(DeploymentStatus.NEEDS_ATTENTION.name());
            d.setErrorSummary(truncate(toMessage(e), ERROR_SUMMARY_MAX));
            d.setFinishedAt(LocalDateTime.now());
            deploymentRepository.save(d);
        } catch (Exception ex) {
            log.error("last-resort fallback failed to mark deployment {} NEEDS_ATTENTION: {}",
                    d.getId(), ex.getMessage(), ex);
        }
    }

    /** 防御式解析状态：非法/未知值返回 null（不抛异常）。 */
    private static DeploymentStatus parseStatus(String s) {
        try {
            return DeploymentStatus.valueOf(s);
        } catch (Exception e) {
            return null;
        }
    }

    private DeploymentStepEntity beginStep(DeploymentEntity d, int seq, DeploymentStepName name) {
        DeploymentStepEntity s = new DeploymentStepEntity();
        s.setDeploymentId(d.getId());
        s.setSeq(seq);
        s.setName(name.name());
        s.setStatus(StepStatus.RUNNING.name());
        s.setStartedAt(LocalDateTime.now());
        return stepRepository.save(s);
    }

    private void finishStep(DeploymentStepEntity s, boolean ok, String detail, String logText) {
        s.setStatus(ok ? StepStatus.SUCCESS.name() : StepStatus.FAILED.name());
        if (detail != null) {
            s.setDetail(detail);
        }
        if (logText != null) {
            s.setLogText(truncate(logText, LOG_TEXT_MAX));
        }
        s.setFinishedAt(LocalDateTime.now());
        stepRepository.save(s);
    }

    private static String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String toMessage(Throwable t) {
        return t.getMessage() == null ? String.valueOf(t) : t.getMessage();
    }

    /** 截断到 max 长度（含末尾省略标记），null 原样返回。 */
    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max - 3) + "...";
    }
}
