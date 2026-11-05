package com.ops.deploy.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ops.deploy.ai.AiDeploymentPlanEntity;
import com.ops.deploy.ai.AiDeploymentPlanRepository;
import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.project.DetectedResult;
import com.ops.deploy.project.PackageVersionEntity;
import com.ops.deploy.project.PackageVersionRepository;
import com.ops.deploy.project.ProjectEntity;
import com.ops.deploy.project.ProjectRepository;
import com.ops.deploy.server.ServerEntity;
import com.ops.deploy.server.ServerRepository;
import com.ops.deploy.user.UserEntity;
import com.ops.deploy.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 部署文档服务（Task 8）：部署成功后自动生成部署/维护两份 Markdown 全文并落库（TEXT 列），
 * 支持在线查看 / 下载 / 手动重新生成。
 *
 * <p>核心能力：
 * <ul>
 *   <li>{@link #redact(String)}：通用脱敏纯函数（-p/-P/-a、--password=、password=、secret=、token=、
 *       Bearer、≥24 位混合大小写长随机串 → {@code ***}），不引入新依赖，可单测；
 *       正常路径/端口/URL/纯小写 hex 指纹不误伤。</li>
 *   <li>{@link #generate(Long)}：查 deployment（不存在 → NOT_FOUND）→ 拉 project/version/server/
 *       操作人用户名/步骤 → 按部署方式（PROCESS/DOCKER/NGINX）组装变量表 → {@link DocumentRenderer}
 *       渲染两份文档 → upsert 落库。环境快照（M1 固定占位"部署时未采集"）为模板内置固定文本；
 *       AI 决策记录经 {@link AiDeploymentPlanRepository#findByDeploymentId} 反查本次部署关联的 AI
 *       方案（来源/推理/关键决策），无则说明为人工配置。</li>
 *   <li>{@link #get(Long, String)}：type ∈ {deploy, maintain}（小写），非法 → BAD_REQUEST；
 *       文档未生成 → NOT_FOUND（提示重新生成）。</li>
 * </ul>
 *
 * <p>维护文档的启停/重启/日志/状态命令、产物路径、FAQ 均按部署方式分支（PROCESS=systemctl/journalctl；
 * DOCKER=docker-compose；NGINX=站点 conf+软链+reload），命令复用 {@link ServiceInstanceService} 及
 * 各执行器（{@link DockerStartExecutor}/{@link NginxStartExecutor}）静态方法，保证与真实运维命令一致。
 */
@Service
public class DeploymentDocumentService {

    private static final Logger log = LoggerFactory.getLogger(DeploymentDocumentService.class);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Bearer 令牌：Authorization: Bearer <token>（值遇空白/引号停止）。 */
    private static final Pattern BEARER =
            Pattern.compile("Bearer\\s+[^\\s'\"]*");
    /** --password=/--password: 长旗标（值遇 ;#& 空白引号停止）。 */
    private static final Pattern PASSWORD_FLAG =
            Pattern.compile("--password[=:]\\S*");
    /** 单字符敏感旗标 -p / -P / -a（如 psql -p / redis-cli -a；值遇空白停止）。
     *  负向后顾排除 --password 这类长旗标前缀，避免误吞长旗标本身；
     *  负向前瞻 (?!/) 跳过以 / 开头的路径值（如 mkdir -p /opt/...），避免误伤正常路径。 */
    private static final Pattern FLAG_P =
            Pattern.compile("(?<![\\w-])-(p|P|a)\\s+(?!/)\\S+");
    /** key=value 形式的密钥字段（password/secret/token/passwd/pwd，大小写不敏感；
     *  值遇空白/引号/&/;/# 停止，避免误吞 URL 查询串中后续的 &x=1 等参数）。 */
    private static final Pattern KEY_VALUE =
            Pattern.compile("(?i)(password|passwd|pwd|secret|token)[=:]([^\\s&;'\"#]*)");
    /** ≥24 位连续字母数字（可含 _ -）且必须同时含大小写字母的"长随机串"（排除纯小写 hex 指纹）。 */
    private static final Pattern LONG_RANDOM =
            Pattern.compile("\\b[A-Za-z0-9_-]{24,}\\b");

    private final DeploymentRepository deploymentRepository;
    private final DeploymentStepRepository stepRepository;
    private final ProjectRepository projectRepository;
    private final PackageVersionRepository versionRepository;
    private final ServerRepository serverRepository;
    private final UserRepository userRepository;
    private final DeploymentDocumentRepository documentRepository;
    private final AiDeploymentPlanRepository planRepository;

    public DeploymentDocumentService(DeploymentRepository deploymentRepository,
                                     DeploymentStepRepository stepRepository,
                                     ProjectRepository projectRepository,
                                     PackageVersionRepository versionRepository,
                                     ServerRepository serverRepository,
                                     UserRepository userRepository,
                                     DeploymentDocumentRepository documentRepository,
                                     AiDeploymentPlanRepository planRepository) {
        this.deploymentRepository = deploymentRepository;
        this.stepRepository = stepRepository;
        this.projectRepository = projectRepository;
        this.versionRepository = versionRepository;
        this.serverRepository = serverRepository;
        this.userRepository = userRepository;
        this.documentRepository = documentRepository;
        this.planRepository = planRepository;
    }

    // ---------- 脱敏（纯函数，可单测） ----------

    /**
     * 通用脱敏：把命令/URL 文本中的敏感片段替换为 {@code ***}。
     *
     * <p>规则（顺序执行）：
     * <ol>
     *   <li>{@code Bearer <token>} → {@code Bearer ***}；</li>
     *   <li>{@code --password[=:]<v>} → {@code --password=***}；</li>
     *   <li>单字符旗标 {@code -p/-P/-a <v>} → {@code -p ***}（排除 --password 长旗标）；</li>
     *   <li>{@code password=/passwd=/pwd=/secret=/token= <v>} → {@code key=***}（大小写不敏感）；</li>
     *   <li>≥24 位混合大小写长随机串（排除纯小写 hex，如 sha256 指纹）→ {@code ***}。</li>
     * </ol>
     * 正常路径/端口/URL/普通命令文本原样保留；null/空串安全。
     */
    public static String redact(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        String out = BEARER.matcher(s).replaceAll("Bearer ***");
        out = PASSWORD_FLAG.matcher(out).replaceAll("--password=***");
        out = FLAG_P.matcher(out).replaceAll("-$1 ***");
        out = KEY_VALUE.matcher(out).replaceAll(m -> m.group(1) + "=***");
        out = LONG_RANDOM.matcher(out).replaceAll(m -> {
            String v = m.group();
            if (hasMixedCase(v)) {
                return "***";
            }
            return v; // 纯小写（如 sha256 hex 指纹）/纯大写/纯数字 → 保留
        });
        return out;
    }

    private static boolean hasMixedCase(String v) {
        boolean lower = false;
        boolean upper = false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (Character.isLowerCase(c)) {
                lower = true;
            } else if (Character.isUpperCase(c)) {
                upper = true;
            }
        }
        return lower && upper;
    }

    // ---------- 生成 / 查询 ----------

    /**
     * 为指定部署生成（或重新生成）部署/维护两份文档并落库（upsert）。
     * deployment 不存在 → {@code BizException(NOT_FOUND)}。
     *
     * @return 部署文档的 VO（type="deploy"，含全文与更新时间）
     */
    @Transactional
    public DeploymentDocumentVO generate(Long deploymentId) {
        DeploymentEntity d = deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "deployment not found: " + deploymentId));
        ProjectEntity project = projectRepository.findById(d.getProjectId())
                .orElseGet(() -> fallbackProject(d.getProjectId()));
        String projectName = project.getName();
        PackageVersionEntity version = versionRepository.findById(d.getVersionId()).orElse(null);
        String versionName = version != null ? version.getFileName() : ("version-" + d.getVersionId());
        String projectType = resolveProjectType(project, version);
        ServerEntity server = serverRepository.findById(d.getServerId()).orElse(null);
        String serverName = server != null ? server.getName() : ("server-" + d.getServerId());
        String serverHost = server != null ? server.getIp() : "-";
        String sshUser = server != null ? server.getSshUser() : "-";
        String operator = resolveOperator(d.getOperator());

        String mode = d.getMode() == null ? "PROCESS" : d.getMode();
        String modeLabel = switch (mode) {
            case "DOCKER" -> "docker-compose 容器";
            case "NGINX" -> "nginx 静态站点";
            default -> "systemd 进程";
        };
        String unitName = ServiceInstanceService.unitName(projectName);
        String workdir = d.getWorkdir() == null ? "" : d.getWorkdir();
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("projectName", projectName);
        vars.put("versionName", versionName);
        vars.put("projectType", projectType == null ? "UNKNOWN" : projectType);
        vars.put("modeLabel", modeLabel);
        vars.put("serverName", serverName);
        vars.put("serverHost", serverHost);
        vars.put("sshUser", sshUser);
        vars.put("workdir", workdir);
        vars.put("unitName", "PROCESS".equals(mode) ? unitName : "");
        vars.put("port", d.getPort() == null ? "" : String.valueOf(d.getPort()));
        vars.put("healthPath", d.getHealthPath() == null ? "" : d.getHealthPath());
        vars.put("operator", operator);
        vars.put("startedAt", fmt(d.getStartedAt()));
        vars.put("finishedAt", fmt(d.getFinishedAt()));
        vars.put("duration", durationText(d.getStartedAt(), d.getFinishedAt()));
        // 启停/重启/日志/状态命令按部署方式分支，与 ServiceInstanceService 真实运维命令保持一致
        applyModeCommands(vars, mode, projectName, unitName, workdir, d);
        // 部署产物 / 维护目录说明 / 日志指引按部署方式差异化（进程=unit；容器=compose；站点=conf+软链）
        vars.put("deployArtifacts", buildDeployArtifacts(mode, projectName, unitName, workdir));
        vars.put("maintainArtifacts", buildMaintainArtifacts(mode, projectName, unitName, workdir));
        vars.put("logGuide", buildLogGuide(mode, unitName, workdir));
        vars.put("startupCommand", startupCommand(project, d, mode));
        vars.put("timeline", buildTimeline(stepRepository.findByDeploymentIdOrderBySeq(deploymentId)));
        vars.put("rollback", buildRollback(projectName, d, mode, unitName));
        vars.put("aiDecision", buildAiDecision(deploymentId));
        vars.put("faq", DocumentRenderer.faqForMode(mode, projectType));
        // DOCKER FAQ 引用 {workdir}（已填）、NGINX FAQ 引用 {symlinkPath}/{confPath}，补入嵌套占位
        if ("NGINX".equals(mode)) {
            vars.put("symlinkPath", NginxStartExecutor.symlinkPath(projectName));
            vars.put("confPath", NginxStartExecutor.confPath(projectName));
        }

        String deployMarkdown = DocumentRenderer.renderDeploy(vars);
        String maintainMarkdown = DocumentRenderer.renderMaintain(vars);

        LocalDateTime now = LocalDateTime.now();
        DeploymentDocumentEntity doc = documentRepository.findByDeploymentId(deploymentId)
                .orElseGet(() -> {
                    DeploymentDocumentEntity e = new DeploymentDocumentEntity();
                    e.setDeploymentId(deploymentId);
                    e.setCreatedAt(now);
                    return e;
                });
        doc.setDeployMarkdown(deployMarkdown);
        doc.setMaintainMarkdown(maintainMarkdown);
        doc.setUpdatedAt(now);
        documentRepository.save(doc);

        DeploymentDocumentVO vo = new DeploymentDocumentVO();
        vo.setDeploymentId(deploymentId);
        vo.setType("deploy");
        vo.setContent(deployMarkdown);
        vo.setUpdatedAt(now);
        return vo;
    }

    /**
     * 查询指定部署的文档全文。
     *
     * @param type "deploy"（部署文档）或 "maintain"（维护文档），其他值 → {@code BizException(BAD_REQUEST)}
     * @return 对应 Markdown 全文；文档未生成 → {@code BizException(NOT_FOUND)}（提示重新生成）
     */
    @Transactional(readOnly = true)
    public String get(Long deploymentId, String type) {
        String t = type == null ? "" : type.toLowerCase();
        if (!"deploy".equals(t) && !"maintain".equals(t)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "invalid document type: " + type + " (expected deploy or maintain)");
        }
        DeploymentDocumentEntity doc = documentRepository.findByDeploymentId(deploymentId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "文档尚未生成，请先重新生成"));
        return "deploy".equals(t) ? doc.getDeployMarkdown() : doc.getMaintainMarkdown();
    }

    /** 供下载端点拼文件名：查项目名，查不到回退 {@code project-{id}}（deployment 须已存在）。 */
    @Transactional(readOnly = true)
    public String projectNameOf(Long deploymentId) {
        DeploymentEntity d = deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "deployment not found: " + deploymentId));
        return projectRepository.findById(d.getProjectId())
                .map(ProjectEntity::getName)
                .orElse("project-" + d.getProjectId());
    }

    // ---------- 组装辅助 ----------

    private static ProjectEntity fallbackProject(long projectId) {
        ProjectEntity p = new ProjectEntity();
        p.setId(projectId);
        p.setName("project-" + projectId);
        return p;
    }

    /** 项目类型：以 project.detectedType 为主，缺失时回退 version.detectedJson 内识别结果。 */
    private static String resolveProjectType(ProjectEntity project, PackageVersionEntity version) {
        if (project.getDetectedType() != null && !project.getDetectedType().isBlank()) {
            return project.getDetectedType();
        }
        if (version == null || version.getDetectedJson() == null) {
            return "UNKNOWN";
        }
        try {
            DetectedResult r = JSON.readValue(version.getDetectedJson(), DetectedResult.class);
            return r.getDetectedType() == null ? "UNKNOWN" : r.getDetectedType();
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    /** 操作人显示名：查不到回退 user-{id}，无操作人回退 "-"。 */
    private String resolveOperator(Long operatorId) {
        if (operatorId == null) {
            return "-";
        }
        return userRepository.findById(operatorId)
                .map(UserEntity::getUsername)
                .orElse("user-" + operatorId);
    }

    /**
     * 手动启动命令（应急用，不经过服务管理器）：
     * <ul>
     *   <li>PROCESS：部署 startCmd（脱敏）回退项目默认启动命令，前缀 {@code cd {workdir} &&}；</li>
     *   <li>DOCKER：compose up -d（容器化拉起）；</li>
     *   <li>NGINX：校验并热加载站点（静态站无独立进程，靠 nginx 托管）。</li>
     * </ul>
     */
    private static String startupCommand(ProjectEntity project, DeploymentEntity d, String mode) {
        switch (mode) {
            case "DOCKER" -> {
                return ServiceInstanceService.buildDockerStartCommand(d.getWorkdir() == null ? "" : d.getWorkdir());
            }
            case "NGINX" -> {
                return ServiceInstanceService.buildNginxStartCommand(project.getName(), d.getWorkdir() == null ? "" : d.getWorkdir());
            }
            default -> {
                String cmd = d.getStartCmd() != null && !d.getStartCmd().isBlank()
                        ? d.getStartCmd()
                        : project.getDefaultStartCmd();
                if (cmd == null || cmd.isBlank()) {
                    return "（未配置启动命令）";
                }
                String redacted = redact(cmd);
                String workdir = d.getWorkdir();
                return workdir == null || workdir.isBlank() ? redacted : "cd " + workdir + " && " + redacted;
            }
        }
    }

    /**
     * 按部署方式填充维护文档的启停/重启/日志/状态命令（与 {@link ServiceInstanceService}
     * 真实运维命令一致，避免容器/静态站误用 systemd 命令）。
     */
    private static void applyModeCommands(Map<String, String> vars, String mode,
                                          String projectName, String unitName,
                                          String workdir, DeploymentEntity d) {
        String port = d.getPort() == null ? "" : String.valueOf(d.getPort());
        String healthPath = d.getHealthPath() == null ? "" : d.getHealthPath();
        String statusCmd = "curl -sf -o /dev/null -w '%{http_code}' --max-time 5 "
                + "http://127.0.0.1:" + port + healthPath;
        switch (mode) {
            case "DOCKER" -> {
                vars.put("startCommand", ServiceInstanceService.buildDockerStartCommand(workdir));
                vars.put("stopCommand", ServiceInstanceService.buildDockerStopCommand(workdir));
                vars.put("restartCommand", ServiceInstanceService.buildDockerRestartCommand(workdir));
                vars.put("journalCommand", ServiceInstanceService.buildDockerLogsCommand(workdir));
                vars.put("statusCommand", statusCmd);
            }
            case "NGINX" -> {
                vars.put("startCommand", ServiceInstanceService.buildNginxStartCommand(projectName, workdir));
                vars.put("stopCommand", "不支持（nginx 为共享守护进程，停止将影响全部站点）");
                vars.put("restartCommand", ServiceInstanceService.buildNginxStartCommand(projectName, workdir));
                vars.put("journalCommand", "tail -f /var/log/nginx/access.log /var/log/nginx/error.log");
                vars.put("statusCommand", "nginx -t && " + statusCmd);
            }
            default -> {
                vars.put("startCommand", ServiceInstanceService.buildStartCommand(unitName));
                vars.put("stopCommand", ServiceInstanceService.buildStopCommand(unitName));
                vars.put("restartCommand", ServiceInstanceService.buildRestartCommand(unitName));
                vars.put("journalCommand", ServiceInstanceService.buildJournalCommand(unitName));
                vars.put("statusCommand", "systemctl status " + ShellQuoter.quote(unitName));
            }
        }
    }

    /** 部署文档「产物与路径」章节：按部署方式列出关键文件。 */
    private static String buildDeployArtifacts(String mode, String projectName, String unitName, String workdir) {
        StringBuilder sb = new StringBuilder();
        sb.append("- 部署目录：`").append(workdir).append("`\n");
        sb.append("- 解压产物：`").append(workdir).append("/extract`\n");
        switch (mode) {
            case "DOCKER" -> {
                sb.append("- compose 文件：`").append(workdir).append("/docker-compose.yml`\n");
                sb.append("- 环境变量：`").append(workdir).append("/.env`（如有）\n");
                sb.append("- Dockerfile：`").append(workdir).append("/extract/Dockerfile`（本地构建时）\n");
            }
            case "NGINX" -> {
                sb.append("- 站点配置：`").append(NginxStartExecutor.confPath(projectName)).append("`\n");
                sb.append("- 版本软链：`").append(NginxStartExecutor.symlinkPath(projectName))
                        .append("` → `").append(workdir).append("/extract`\n");
            }
            default -> {
                sb.append("- systemd unit：`/etc/systemd/system/").append(unitName).append("`\n");
            }
        }
        return sb.toString().stripTrailing();
    }

    /** 维护文档「目录与配置说明」章节：按部署方式说明日常需关注的文件。 */
    private static String buildMaintainArtifacts(String mode, String projectName, String unitName, String workdir) {
        StringBuilder sb = new StringBuilder();
        sb.append("- 部署目录：`").append(workdir).append("`（应用文件位于 `extract` 子目录）\n");
        switch (mode) {
            case "DOCKER" -> {
                sb.append("- 容器编排：`").append(workdir).append("/docker-compose.yml`——修改后 `docker-compose up -d` 生效\n");
                sb.append("- 环境变量：`").append(workdir).append("/.env`（端口/镜像等可调参数）\n");
                sb.append("- 查看容器：`docker-compose -f ").append(workdir)
                        .append("/docker-compose.yml ps`\n");
            }
            case "NGINX" -> {
                sb.append("- 站点配置：`").append(NginxStartExecutor.confPath(projectName)).append("`（listen/root 等）\n");
                sb.append("- 版本软链：`").append(NginxStartExecutor.symlinkPath(projectName))
                        .append("`——指向当前版本 extract，切换版本/回滚仅换软链\n");
            }
            default -> {
                sb.append("- 服务单元：`/etc/systemd/system/").append(unitName)
                        .append("`——启停/重启经 systemctl 操作该 unit\n");
            }
        }
        return sb.toString().stripTrailing();
    }

    /** 排障「日志」指引：按部署方式给出查看日志的入口。 */
    private static String buildLogGuide(String mode, String unitName, String workdir) {
        return switch (mode) {
            case "DOCKER" -> "用上方“实时日志”命令 `docker-compose logs -f` 跟踪容器输出，"
                    + "或用 `docker-compose -f " + workdir + "/docker-compose.yml logs --tail 200 <服务名>` 看最近日志。";
            case "NGINX" -> "静态站无独立应用进程，访问/错误见 nginx 日志："
                    + "`tail -f /var/log/nginx/access.log /var/log/nginx/error.log`。";
            default -> "用上方“实时日志”命令 `journalctl -u " + unitName + " -f` 跟踪，"
                    + "或查看 " + workdir + " 下的应用自身日志文件。";
        };
    }

    /**
     * 部署文档「AI 决策记录」章节：反查本次部署关联的 AI 方案（确认执行后 deployment_id 回写），
     * 有则渲染方案来源 / AI 推理 / 关键决策；无（人工配置部署）则说明非 AI 生成。
     */
    private String buildAiDecision(Long deploymentId) {
        return planRepository.findByDeploymentId(deploymentId).stream().findFirst().map(plan -> {
            StringBuilder sb = new StringBuilder();
            sb.append("- **方案来源**：").append("AI".equals(plan.getSource()) ? "AI（LLM 生成）" : "规则引擎（降级）")
                    .append("（方案 ID：").append(plan.getId()).append("）\n");
            if (plan.getReasoning() != null && !plan.getReasoning().isBlank()) {
                sb.append("- **AI 推理**：").append(plan.getReasoning().strip()).append("\n");
            }
            sb.append("- **关键决策**：\n");
            sb.append("  - 项目类型：").append(plan.getDetectedType() == null ? "-" : plan.getDetectedType()).append("\n");
            sb.append("  - 部署方式：").append(plan.getMode() == null ? "-" : plan.getMode()).append("\n");
            if (plan.getStartCmd() != null && !plan.getStartCmd().isBlank()) {
                sb.append("  - 启动命令：`").append(redact(plan.getStartCmd())).append("`\n");
            }
            sb.append("  - 端口：").append(plan.getPort() == null ? "-" : plan.getPort())
                    .append("，健康检查：`").append(plan.getHealthPath() == null ? "-" : plan.getHealthPath()).append("`\n");
            return sb.toString().stripTrailing();
        }).orElse("本部署非 AI 方案生成（人工配置），无 AI 决策记录。");
    }

    /**
     * 回滚步骤命令块：按部署方式分支（与 {@code DeploymentRunner.doRollback} 真实回滚动作一致）。
     * <ul>
     *   <li>PROCESS：有旧版本 → 写 unit 指回旧 workdir + 重启；无 → stop + disable。</li>
     *   <li>DOCKER：有旧版本 → 当前 compose {@code down}（清掉同名容器）+ 旧版本 compose {@code up -d}；
     *       无 → 当前 compose {@code down}。</li>
     *   <li>NGINX：有旧版本 → 软链切回旧版本 extract + reload；无 → 提示站点删除（共享守护进程不可单站停止）。</li>
     * </ul>
     *
     * <p>PROCESS 回滚 unit 的 startCmd 取"旧版本最近一次 SUCCESS 部署"的值，查不到才回退当前部署的
     * startCmd——沿用失败部署的 startCmd 会把新端口写进回滚 unit，误导人工回滚（缺陷9）。
     */
    String buildRollback(String projectName, DeploymentEntity d, String mode, String unitName) {
        Long oldVersionId = d.getOldVersionId();
        return switch (mode) {
            case "DOCKER" -> {
                if (oldVersionId != null) {
                    String oldWorkdir = "/opt/deploy/" + projectName + "/" + oldVersionId;
                    yield ServiceInstanceService.buildDockerStopCommand(d.getWorkdir())
                            + "\n" + DockerStartExecutor.buildRollbackCommand(oldWorkdir);
                }
                yield ServiceInstanceService.buildDockerStopCommand(d.getWorkdir());
            }
            case "NGINX" -> {
                if (oldVersionId != null) {
                    String oldWorkdir = "/opt/deploy/" + projectName + "/" + oldVersionId;
                    yield NginxStartExecutor.buildRollbackCommand(projectName, oldWorkdir);
                }
                yield "（无更早版本可回滚；如需下线该站点，删除站点配置 "
                        + NginxStartExecutor.confPath(projectName) + " 与软链 "
                        + NginxStartExecutor.symlinkPath(projectName) + " 后 "
                        + NginxStartExecutor.buildReloadCommand() + "。）";
            }
            default -> {
                if (oldVersionId != null) {
                    String oldWorkdir = "/opt/deploy/" + projectName + "/" + oldVersionId;
                    String unitPath = "/etc/systemd/system/" + unitName;
                    String oldStartCmd = deploymentRepository
                            .findFirstByProjectIdAndVersionIdAndServerIdAndStatusOrderByIdDesc(
                                    d.getProjectId(), oldVersionId, d.getServerId(),
                                    "SUCCESS")
                            .map(DeploymentEntity::getStartCmd)
                            .orElse(null);
                    String startCmd = oldStartCmd != null ? oldStartCmd : d.getStartCmd();
                    // 回滚块的 unit 内容同样会把 startCmd 写入 ExecStart，必须与手动启动块一致地脱敏，
                    // 避免 --password=xxx / token=xxx 等凭据经文档（对所有已登录角色可读）明文暴露。
                    String redactedStartCmd = startCmd == null ? null : redact(startCmd);
                    // 回滚 unit 的运行目录按旧版本包类型解析（jar/war 落 workdir 根，其余落 extract），
                    // 与 DeploymentRunner.doRollbackProcess 保持一致
                    String oldFileName = versionRepository.findById(oldVersionId)
                            .map(PackageVersionEntity::getFileName)
                            .orElse(null);
                    String oldWorkingDir = InstallStepExecutor.resolveRuntimeDir(oldWorkdir, oldFileName);
                    String writeCmd = StartStepExecutor.buildWriteUnitCommand(
                            unitPath, StartStepExecutor.buildUnitContent(unitName, oldWorkingDir, redactedStartCmd));
                    String restart = StartStepExecutor.buildStartCommand(unitName);
                    yield ServiceInstanceService.buildStopCommand(unitName) + "\n" + writeCmd + "\n" + restart;
                }
                yield ServiceInstanceService.buildStopCommand(unitName)
                        + "\nsystemctl disable " + ShellQuoter.quote(unitName);
            }
        };
    }

    /** 部署过程时间线：每步名称/状态/时间 + 关键命令（经 {@link #redact} 脱敏）。 */
    private static String buildTimeline(List<DeploymentStepEntity> steps) {
        if (steps == null || steps.isEmpty()) {
            return "（无步骤记录）";
        }
        StringBuilder sb = new StringBuilder();
        for (DeploymentStepEntity s : steps) {
            String range = fmtTime(s.getStartedAt()) + " ~ " + fmtTime(s.getFinishedAt());
            sb.append("- **").append(s.getSeq() == null ? "" : s.getSeq()).append(". ")
                    .append(s.getName()).append("** — `")
                    .append(s.getStatus() == null ? "" : s.getStatus()).append("`（")
                    .append(range).append("）\n");
            String cmd = keyCommand(s);
            if (cmd != null) {
                sb.append("  - 关键命令：`").append(cmd).append("`\n");
            }
        }
        return sb.toString().stripTrailing();
    }

    /** 从步骤 detail（JSONB 文本）提取关键命令（INSTALL→command、VERIFY→url），脱敏后返回。 */
    private static String keyCommand(DeploymentStepEntity s) {
        if (s.getDetail() == null || s.getDetail().isBlank()) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(s.getDetail());
            String raw = null;
            if (s.getName() != null) {
                switch (s.getName()) {
                    case "INSTALL" -> raw = text(n, "command");
                    case "VERIFY" -> raw = text(n, "url");
                    default -> { /* UPLOAD/START 无单行关键命令，省略 */ }
                }
            }
            return raw == null ? null : redact(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? "-" : TS.format(t);
    }

    private static String fmtTime(LocalDateTime t) {
        return t == null ? "-" : TS.format(t).substring(11);
    }

    private static String durationText(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return "-";
        }
        Duration dur = Duration.between(start, end);
        long total = dur.getSeconds();
        if (total < 0) {
            return "-";
        }
        return (total / 60) + "分" + (total % 60) + "秒";
    }
}
