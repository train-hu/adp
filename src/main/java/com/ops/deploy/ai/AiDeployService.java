package com.ops.deploy.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.crypto.CredentialCryptoService;
import com.ops.deploy.deploy.ConfigFileGenerator;
import com.ops.deploy.deploy.DeploymentCreateRequest;
import com.ops.deploy.deploy.DeploymentService;
import com.ops.deploy.deploy.DeploymentVO;
import com.ops.deploy.deploy.InstallStepExecutor;
import com.ops.deploy.deploy.NginxStartExecutor;
import com.ops.deploy.deploy.StartStepExecutor;
import com.ops.deploy.deploy.VerifyStepExecutor;
import com.ops.deploy.llm.LlmChatClient;
import com.ops.deploy.llm.LlmModelConfigEntity;
import com.ops.deploy.llm.LlmModelConfigRepository;
import com.ops.deploy.project.DetectedResult;
import com.ops.deploy.project.PackageVersionEntity;
import com.ops.deploy.project.PackageVersionRepository;
import com.ops.deploy.project.ProjectAccessService;
import com.ops.deploy.project.ProjectConfigFileRepository;
import com.ops.deploy.project.ProjectEntity;
import com.ops.deploy.project.ProjectRepository;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.server.ServerEntity;
import com.ops.deploy.server.ServerRepository;
import com.ops.deploy.ssh.CommandResult;
import com.ops.deploy.ssh.SshService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * AI 自主部署编排（M2 AI 能力，FR-02/FR-05）：AI 以 ReAct 循环解析部署包 + 只读探测目标机，
 * 产出结构化部署方案，人工确认（可编辑）后交由既有安全管线执行。
 *
 * <p>护栏（设计文档 3.5）：
 * <ul>
 *   <li><b>工具白名单</b>：仅 {@code inspect_package}（读规则识别结果）、{@code probe_server}
 *       （只读 SSH 探测，绝不写）、{@code submit_plan}（提交最终方案）三个只读工具；
 *       危险操作（上传/启动/删除）不走裸 SSH，而是确认后的既有部署管线（含回滚/健康检查）。</li>
 *   <li><b>步数限制</b>：单次生成最多 {@link #MAX_LLM_STEPS} 轮 LLM 调用，超限熔断。</li>
 *   <li><b>全程轨迹落库</b>：每步工具调用参数/结果写入 trajectory_json，可回放、可审计。</li>
 *   <li><b>可降级</b>：LLM 不可用（无模型配置 / 调用失败）时降级规则引擎模式（source=RULE）。</li>
 * </ul>
 *
 * <p>方案 mode 与 detectedType 强约束一致（STATIC_WEB→NGINX、DOCKER→DOCKER、其余→PROCESS），
 * 与 {@code DeploymentService.resolveMode} 语义对齐；确认执行时回填 project.detectedType，
 * 使既有管线推导的 mode 与 AI 方案一致。
 */
@Service
public class AiDeployService {

    private static final Logger log = LoggerFactory.getLogger(AiDeployService.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 方案专用反序列化器：宽容模式（不认的字段忽略）。
     * LLM 产出的 submit_plan JSON 常夹带 prompt 未要求的额外字段，裸 {@link #JSON}
     * 默认 FAIL_ON_UNKNOWN_PROPERTIES 会直接抛异常导致降级，这里放宽。
     */
    private static final ObjectMapper PLAN_JSON = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** 单次方案生成的最大 LLM 轮数（护栏：步数限制，防失控）。 */
    static final int MAX_LLM_STEPS = 10;

    /** 模型只回文本未调工具时的最大引导重试次数（避免无谓降级，又不无限循环）。 */
    static final int MAX_NUDGE = 2;

    /** 健康检查路径白名单（防 curl 注入，与 DeploymentService 一致）。 */
    private static final Pattern HEALTH_PATH_PATTERN = Pattern.compile("^/[A-Za-z0-9._/*-]*$");

    /** 部署模式白名单。 */
    private static final List<String> MODES = List.of("PROCESS", "NGINX", "DOCKER");

    private final AiDeploymentPlanRepository planRepository;
    private final PackageVersionRepository versionRepository;
    private final ProjectRepository projectRepository;
    private final ServerRepository serverRepository;
    private final LlmModelConfigRepository llmModelRepository;
    private final CredentialCryptoService crypto;
    private final LlmChatClient llmClient;
    private final SshService sshService;
    private final DeploymentService deploymentService;
    private final ProjectConfigFileRepository configFileRepository;
    private final ConfigFileGenerator configGenerator;
    private final ProjectAccessService accessService;

    public AiDeployService(AiDeploymentPlanRepository planRepository,
                           PackageVersionRepository versionRepository,
                           ProjectRepository projectRepository,
                           ServerRepository serverRepository,
                           LlmModelConfigRepository llmModelRepository,
                           CredentialCryptoService crypto,
                           LlmChatClient llmClient,
                           SshService sshService,
                           DeploymentService deploymentService,
                           ProjectConfigFileRepository configFileRepository,
                           ConfigFileGenerator configGenerator,
                           ProjectAccessService accessService) {
        this.planRepository = planRepository;
        this.versionRepository = versionRepository;
        this.projectRepository = projectRepository;
        this.serverRepository = serverRepository;
        this.llmModelRepository = llmModelRepository;
        this.crypto = crypto;
        this.llmClient = llmClient;
        this.sshService = sshService;
        this.deploymentService = deploymentService;
        this.configFileRepository = configFileRepository;
        this.configGenerator = configGenerator;
        this.accessService = accessService;
    }

    // ---------- 方案生成（ReAct）----------

    /**
     * AI 生成部署方案（DRAFT）。modelId 为空时取第一个启用模型；
     * 无可用模型或 LLM 调用失败时降级规则引擎（source=RULE），保证总能出方案。
     * userMode 非空（用户手选部署方式）：白名单校验 → 注入 prompt → 落库覆盖 AI 上报的 mode；
     * DOCKER 无 compose 文件在此阶段自动生成补齐（比等 confirm 时报错体验好）。
     */
    public AiPlanVO generate(Long versionId, Long serverId, Long modelId, AuthUser user,
                             String userMode, Integer userPort) {
        if (userMode != null && !userMode.isBlank() && !MODES.contains(userMode.trim().toUpperCase())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "invalid mode: " + userMode);
        }
        if (userPort != null && (userPort < 1 || userPort > 65535)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "invalid port: " + userPort);
        }
        PackageVersionEntity version = requireVersion(versionId);
        ProjectEntity project = requireProject(version.getProjectId());
        accessService.assertOperate(project.getId(), user);
        ServerEntity server = requireServer(serverId);

        DetectedResult detected = parseDetected(version.getDetectedJson());
        List<PlanStep> trajectory = new ArrayList<>();

        LlmModelConfigEntity model = resolveModel(modelId);
        AiPlanProposal proposal = null;
        String source = "AI";
        if (model != null) {
            try {
                proposal = reactLoop(model, project, version, server, detected, trajectory, userMode, userPort);
            } catch (BizException e) {
                log.warn("AI generation failed ({}), fallback to rule engine: {}", model.getName(), e.getMessage());
                proposal = null;
            }
        }

        // 降级规则引擎（护栏第 6 条）：LLM 不可用 / 未产出有效方案时，按识别结果 + 项目默认兜底
        if (proposal == null) {
            source = "RULE";
            proposal = ruleEngine(project, version, detected);
            trajectory.add(new PlanStep(1, "rule_engine", null,
                    "LLM 不可用或无有效产出，降级规则引擎生成方案"));
        }

        // 强约束：mode 与 detectedType 一致性（防 AI 幻觉给出矛盾组合）；用户手选模式优先
        String type = normalizeType(proposal.detectedType(), project, detected);
        String mode = userMode != null && !userMode.isBlank()
                ? userMode.trim().toUpperCase()
                : resolveModeFromType(type);
        if ("DOCKER".equals(mode)
                && configFileRepository.findByProjectIdAndFileKey(project.getId(), "compose").isEmpty()) {
            // 项目未配置 compose：AI 依据项目配置 + 部署方式自动生成并入库（无 LLM 时规则模板兜底），
            // 不再硬报错阻断方案生成。
            log.info("compose config missing for DOCKER plan of project {}, auto-generating", project.getId());
            configGenerator.ensureCompose(project, detected, version);
        }
        String startCmd = sanitizeStartCmd(proposal.startCmd());
        // 用户指定端口优先于 AI 建议（AI 在 prompt 中已被告知用户端口，正常会原样回传）
        Integer port = userPort != null ? userPort : sanitizePort(proposal.port());
        String healthPath = sanitizeHealthPath(proposal.healthPath());

        AiDeploymentPlanEntity plan = new AiDeploymentPlanEntity();
        plan.setProjectId(project.getId());
        plan.setVersionId(version.getId());
        plan.setServerId(server.getId());
        plan.setStatus("DRAFT");
        plan.setSource(source);
        plan.setDetectedType(type);
        plan.setBuildTool(detected == null ? null : detected.getBuildTool());
        plan.setMode(mode);
        plan.setStartCmd(startCmd);
        plan.setPort(port);
        plan.setHealthPath(healthPath);
        plan.setReasoning(normalizeReasoning(proposal.reasoning(), type, mode, startCmd, port));
        plan.setTrajectoryJson(toJson(trajectory));
        plan.setPlanJson(toJson(buildPlanSnapshot(project, version, server, type, mode, startCmd, port, healthPath)));
        plan.setOperator(user.getUsername());
        LocalDateTime now = LocalDateTime.now();
        plan.setCreatedAt(now);
        plan.setUpdatedAt(now);
        planRepository.save(plan);
        log.info("AI deployment plan {} generated (source={} mode={} type={}) for project {} v{} server {}",
                plan.getId(), source, mode, type, project.getName(), version.getId(), server.getName());
        return toVO(plan, project, version, server);
    }

    /**
     * ReAct 循环：LLM 通过工具调用获取信息，最终经 {@code submit_plan} 提交方案。
     * 每步记录轨迹；超过 MAX_LLM_STEPS 抛 BizException（触发降级）。
     * userPort 非空时注入 prompt，让 LLM 按用户指定端口出方案（避免 NGINX 默认 80 漂移）。
     */
    private AiPlanProposal reactLoop(LlmModelConfigEntity model, ProjectEntity project,
                                     PackageVersionEntity version, ServerEntity server,
                                     DetectedResult detected, List<PlanStep> trajectory,
                                     String userMode, Integer userPort) {
        String apiKey = crypto.decrypt(model.getApiKeyCipher());
        List<LlmChatClient.ToolMessage> messages = new ArrayList<>();
        messages.add(LlmChatClient.ToolMessage.system(buildSystemPrompt()));
        messages.add(LlmChatClient.ToolMessage.user(buildUserPrompt(project, version, server, userMode, userPort)));
        List<LlmChatClient.ToolDef> tools = toolDefs();

        int nudges = 0;
        for (int step = 1; step <= MAX_LLM_STEPS; step++) {
            LlmChatClient.ToolMessage resp =
                    llmClient.chatWithTools(model.getBaseUrl(), apiKey, model.getModel(), messages, tools);
            List<LlmChatClient.ToolCall> calls = resp.toolCalls();
            log.info("AI react step {} model={} toolCalls={} content={}",
                    step, model.getModel(),
                    calls == null ? 0 : calls.size(),
                    truncate(resp.content(), 500));
            if (calls == null || calls.isEmpty()) {
                // 未走 submit_plan 而直接给文本 → 先尝试从文本中解析 JSON 方案
                AiPlanProposal p = tryParseProposal(resp.content());
                if (p != null) {
                    return p;
                }
                // 模型只回了陈述文本（如"我将调用…"）而非真正发起工具调用：
                // 追加一条指令引导其真正调用工具，最多重试 2 次，避免直接降级
                if (nudges < MAX_NUDGE) {
                    nudges++;
                    messages.add(resp);
                    messages.add(LlmChatClient.ToolMessage.user(
                            "请直接调用工具：先调用 inspect_package 查看识别结果，再调用 probe_server 探测服务器，"
                                    + "最后调用 submit_plan 提交方案。请现在就发起工具调用，不要只描述你打算做什么。"));
                    continue;
                }
                throw new BizException(ErrorCode.LLM_ERROR,
                        "AI 未通过 submit_plan 提交有效方案（第 " + step + " 步）");
            }
            // 记录并执行每个工具调用
            messages.add(resp);
            for (LlmChatClient.ToolCall call : calls) {
                String name = call.function() != null ? call.function().name() : "";
                String args = call.function() != null ? call.function().arguments() : null;
                String result = executeTool(name, args, project, version, server, detected);
                trajectory.add(new PlanStep(step, name, args, truncate(result, 2000)));
                messages.add(LlmChatClient.ToolMessage.tool(call.id(), result));
                if ("submit_plan".equals(name)) {
                    AiPlanProposal p = tryParseProposal(args);
                    if (p == null) {
                        throw new BizException(ErrorCode.LLM_ERROR, "submit_plan 参数无法解析为方案 JSON");
                    }
                    return p;
                }
            }
        }
        throw new BizException(ErrorCode.LLM_ERROR, "AI 生成超过最大步数限制（" + MAX_LLM_STEPS + "）");
    }

    /** 规则引擎降级：识别结果 + 项目默认三级兜底。 */
    static AiPlanProposal ruleEngine(ProjectEntity project, PackageVersionEntity version, DetectedResult detected) {
        String type = project.getDetectedType() != null
                ? project.getDetectedType()
                : (detected == null ? "UNKNOWN" : detected.getDetectedType());
        String startCmd = firstNonBlank(
                detected == null ? null : detected.getSuggestedStartCmd(),
                project.getDefaultStartCmd());
        Integer port = detected != null && detected.getSuggestedPort() != null
                ? detected.getSuggestedPort() : project.getDefaultPort();
        String healthPath = firstNonBlank(
                detected == null ? null : detected.getSuggestedHealthPath(),
                project.getDefaultHealthPath(), "/");
        return new AiPlanProposal(type, null, startCmd, port, healthPath,
                "规则引擎生成（LLM 不可用）：识别类型 " + type + "，建议命令 " + (startCmd == null ? "无" : startCmd));
    }

    /**
     * 规范化推理说明：LLM 返回空 / 解析失败（如思考模型把内部标记或重复工具调用泄漏进
     * {@code submit_plan} 的 arguments，导致 reasoning 字段无法解析）时，用方案字段合成一条
     * 确定性摘要，保证人工审阅时<b>永不出现空白推理说明</b>（前端否则显示"（无）"）。
     */
    static String normalizeReasoning(String raw, String type, String mode, String startCmd, Integer port) {
        if (raw != null && !raw.isBlank()) {
            return raw.trim();
        }
        return "AI 未返回结构化推理说明，以下为方案摘要：识别类型 " + type
                + "，部署模式 " + mode
                + "，启动命令 " + (startCmd == null || startCmd.isBlank() ? "无" : startCmd)
                + "，服务端口 " + (port == null ? "未指定" : String.valueOf(port)) + "。";
    }

    /** 工具白名单执行器：仅只读工具；未知工具返回错误信息（不中断，让 AI 自纠）。 */
    private String executeTool(String name, String args, ProjectEntity project,
                               PackageVersionEntity version, ServerEntity server, DetectedResult detected) {
        return switch (name) {
            case "inspect_package" -> inspectPackage(version, detected);
            case "probe_server" -> probeServer(server, project);
            case "submit_plan" -> "{\"accepted\":true}"; // 实际方案在调用方解析
            default -> "{\"error\":\"unknown tool: " + name + "\"}";
        };
    }

    /** 只读工具 inspect_package：返回部署包的规则识别结果。 */
    static String inspectPackage(PackageVersionEntity version, DetectedResult detected) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fileName", version.getFileName());
        m.put("sizeBytes", version.getSizeBytes());
        m.put("detection", detected);
        return toJson(m);
    }

    /** 只读工具 probe_server：探测目标机环境（OS/架构/运行时/磁盘/已有部署），绝不执行写操作。 */
    String probeServer(ServerEntity server, ProjectEntity project) {
        String safeProject = sanitizeForPath(project.getName());
        String cmd = "echo '=== os ==='; head -3 /etc/os-release 2>/dev/null"
                + "; echo '=== arch ==='; uname -m 2>/dev/null"
                + "; echo '=== runtime ==='; "
                + "(command -v systemctl >/dev/null 2>&1 && echo systemd:yes || echo systemd:no)"
                + "; (command -v docker >/dev/null 2>&1 && echo docker:yes || echo docker:no)"
                + "; (command -v nginx >/dev/null 2>&1 && echo nginx:yes || echo nginx:no)"
                + "; (command -v java >/dev/null 2>&1 && echo java:yes || echo java:no)"
                + "; (command -v node >/dev/null 2>&1 && echo node:yes || echo node:no)"
                + "; (command -v python3 >/dev/null 2>&1 && echo python3:yes || echo python3:no)"
                + "; echo '=== disk ==='; df -h /opt 2>/dev/null | tail -1"
                + "; echo '=== existing ==='; ls -la /opt/deploy/" + safeProject
                + " 2>/dev/null || echo none";
        CommandResult r = probe(server.getId(), cmd);
        return r.getStdout() + (r.getStderr() == null || r.getStderr().isBlank() ? "" : "\n[stderr] " + r.getStderr());
    }

    // ---------- 确认执行 / 查询 / 放弃 ----------

    /**
     * 确认方案并执行：回填 project.detectedType（人工确认=认可 AI 判定的类型）→
     * 调既有 {@link DeploymentService#create}（三级兜底+安全校验+异步执行+回滚保护）→
     * 方案置 EXECUTED 并关联部署任务。可经 req 覆盖字段（人工微调）。
     */
    public AiPlanVO confirm(Long id, ConfirmRequest req, AuthUser user) {
        AiDeploymentPlanEntity plan = requirePlan(id);
        if (!"DRAFT".equals(plan.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "only a draft plan can be confirmed (current=" + plan.getStatus() + ")");
        }
        ProjectEntity project = requireProject(plan.getProjectId());
        accessService.assertOperate(project.getId(), user);

        // 人工确认 = 认可 AI 判定的项目类型 → 回填，使既有管线 resolveMode 与方案一致
        if (plan.getDetectedType() != null && !plan.getDetectedType().equals(project.getDetectedType())) {
            project.setDetectedType(plan.getDetectedType());
            projectRepository.save(project);
        }

        // 组装创建请求（AI 方案字段优先，人工可覆盖）
        DeploymentCreateRequest cr = new DeploymentCreateRequest();
        cr.setVersionId(plan.getVersionId());
        cr.setServerId(plan.getServerId());
        String startCmd = req != null && req.startCmd() != null ? req.startCmd() : plan.getStartCmd();
        Integer port = req != null && req.port() != null ? req.port() : plan.getPort();
        String healthPath = req != null && req.healthPath() != null ? req.healthPath() : plan.getHealthPath();
        String mode = req != null && req.mode() != null ? req.mode() : plan.getMode();
        Long databaseId = req != null ? req.databaseId() : null;
        cr.setStartCmd(startCmd);
        cr.setPort(port);
        cr.setHealthPath(healthPath);
        cr.setMode(mode);
        cr.setDatabaseId(databaseId);
        DeploymentVO dep = deploymentService.create(cr, user);

        plan.setDeploymentId(dep.getId());
        plan.setStatus("EXECUTED");
        plan.setConfirmedAt(LocalDateTime.now());
        plan.setUpdatedAt(LocalDateTime.now());
        if (startCmd != null) plan.setStartCmd(startCmd);
        if (port != null) plan.setPort(port);
        if (healthPath != null) plan.setHealthPath(healthPath);
        if (mode != null) plan.setMode(mode);
        planRepository.save(plan);
        log.info("AI plan {} confirmed, deployment {} created", id, dep.getId());
        return toVO(plan, project,
                versionRepository.findById(plan.getVersionId()).orElse(null),
                serverRepository.findById(plan.getServerId()).orElse(null));
    }

    /** 放弃方案（仅 DRAFT 可放弃）。 */
    public void abandon(Long id, AuthUser user) {
        AiDeploymentPlanEntity plan = requirePlan(id);
        if (!"DRAFT".equals(plan.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "only a draft plan can be abandoned (current=" + plan.getStatus() + ")");
        }
        accessService.assertOperate(plan.getProjectId(), user);
        plan.setStatus("ABANDONED");
        plan.setUpdatedAt(LocalDateTime.now());
        planRepository.save(plan);
    }

    public AiPlanVO get(Long id, AuthUser user) {
        AiDeploymentPlanEntity plan = requirePlan(id);
        accessService.assertView(plan.getProjectId(), user);
        ProjectEntity project = projectRepository.findById(plan.getProjectId()).orElse(null);
        PackageVersionEntity version = versionRepository.findById(plan.getVersionId()).orElse(null);
        ServerEntity server = serverRepository.findById(plan.getServerId()).orElse(null);
        return toVO(plan, project, version, server);
    }

    public List<AiPlanVO> listByProject(Long projectId, AuthUser user) {
        accessService.assertView(projectId, user);
        return planRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(p -> toVO(p,
                        projectRepository.findById(p.getProjectId()).orElse(null),
                        versionRepository.findById(p.getVersionId()).orElse(null),
                        serverRepository.findById(p.getServerId()).orElse(null)))
                .toList();
    }

    // ---------- 方案校验 / 清洗（纯函数，便于单测）----------

    /** detectedType 归一：AI 给的值若为空/异常，回退项目类型或识别类型。 */
    static String normalizeType(String aiType, ProjectEntity project, DetectedResult detected) {
        if (aiType != null && !aiType.isBlank()) {
            return aiType.trim().toUpperCase();
        }
        if (project.getDetectedType() != null) {
            return project.getDetectedType();
        }
        return detected == null ? "UNKNOWN" : detected.getDetectedType();
    }

    /** 与 DeploymentService.resolveMode 语义一致：STATIC_WEB→NGINX、DOCKER→DOCKER、其余→PROCESS。 */
    static String resolveModeFromType(String type) {
        if ("STATIC_WEB".equals(type)) {
            return "NGINX";
        }
        if ("DOCKER".equals(type)) {
            return "DOCKER";
        }
        return "PROCESS";
    }

    static String sanitizeStartCmd(String cmd) {
        if (cmd == null || cmd.isBlank()) {
            return null;
        }
        String c = cmd.trim();
        if (c.indexOf('\n') >= 0 || c.indexOf('\r') >= 0 || c.length() > 512) {
            return null; // 含换行（防 heredoc 逃逸）或过长 → 丢弃
        }
        return c;
    }

    static Integer sanitizePort(Integer port) {
        if (port == null || port < 1 || port > 65535) {
            return null;
        }
        return port;
    }

    static String sanitizeHealthPath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String p = path.trim();
        return HEALTH_PATH_PATTERN.matcher(p).matches() ? p : "/";
    }

    /** 命令预览：按 mode 生成各步骤将要执行的远端命令（供人工审阅，不实际执行）。 */
    static Map<String, String> buildCommandsPreview(String mode, String projectName, Long versionId,
                                                    String fileName, String startCmd, Integer port, String healthPath) {
        Map<String, String> m = new LinkedHashMap<>();
        String safeProject = sanitizeForPath(projectName);
        String workdir = "/opt/deploy/" + safeProject + "/" + versionId;
        switch (mode) {
            case "NGINX" -> {
                m.put("站点配置 /etc/nginx/conf.d/deploy-" + safeProject + ".conf",
                        NginxStartExecutor.buildDefaultNginxConf(safeProject, port));
                m.put("软链", "ln -sfn " + workdir + "/extract /opt/deploy/" + safeProject + "/current");
                m.put("重载", NginxStartExecutor.buildReloadCommand());
            }
            case "DOCKER" -> {
                m.put("docker-compose.yml", "（使用项目已配置的 compose 文件，下发到 " + workdir + "）");
                m.put("启动", "docker-compose -f " + workdir + "/docker-compose.yml up -d");
            }
            default -> {
                String unit = "deploy-" + safeProject + ".service";
                m.put("systemd unit 内容", StartStepExecutor.buildUnitContent(unit,
                        InstallStepExecutor.resolveRuntimeDir(workdir, fileName), startCmd));
                m.put("启动", "systemctl daemon-reload && systemctl enable " + unit + " && systemctl restart " + unit);
            }
        }
        if (port != null) {
            m.put("健康检查", VerifyStepExecutor.buildVerifyCommand(port,
                    healthPath == null || healthPath.isBlank() ? "/" : healthPath));
        }
        return m;
    }

    // ---------- LLM prompt / 工具定义（静态，便于测试）----------

    static String buildSystemPrompt() {
        return "你是自动化部署平台的部署规划专家。任务：分析给定的部署包和目标服务器，产出一份结构化部署方案，"
                + "供人工确认后由平台执行。\n"
                + "工作规则：\n"
                + "1. 先调用 inspect_package 查看部署包的规则识别结果（类型/构建工具/入口/建议命令/端口）。\n"
                + "2. 再调用 probe_server 查看目标服务器环境（OS/架构/可用运行时/磁盘/已有部署）。\n"
                + "3. 基于以上信息推理出部署方案，最后必须调用 submit_plan 提交最终方案。\n"
                + "submit_plan 的 arguments 必须是 JSON 对象，字段：\n"
                + "  - detectedType: 项目类型，取值 JAVA_MAVEN/JAVA_GRADLE/NODE/PYTHON/GO/STATIC_WEB/DOCKER/UNKNOWN\n"
                + "  - mode: 部署模式，取值 PROCESS(systemd 进程托管)/NGINX(静态站托管)/DOCKER(compose)；"
                + "约束：STATIC_WEB→NGINX，DOCKER→DOCKER，其余→PROCESS\n"
                + "  - startCmd: 启动命令（NGINX/DOCKER 模式可为空），如 \"java -jar app.jar\"、\"npm start\"、\"python3 app.py\"。\n"
                + "    <b>startCmd 硬性规则（违反必然导致部署失败）</b>：\n"
                + "    (1) 只允许使用你<b>确切知道存在</b>的参数/flag，<b>严禁凭印象编造</b>命令行选项。"
                + "例如 Go 程序的 flag 拼写（-configs 还是 -config）、Java 的 -jar/-cp 之外的 JVM 参数等，"
                + "除非包内 README/启动脚本/可执行文件的 --help 输出能佐证，否则<b>一律不加</b>。\n"
                + "    (2) 优先给出<b>最小可运行命令</b>：仅程序本体 + 明确必要的参数。大多数 CLI 自带合理默认值"
                + "（如配置目录默认 \"conf\"），默认值能覆盖就<b>不要传</b>。宁可少传（走默认）也不要传错。\n"
                + "    (3) 启动命令以<b>运行目录为相对基准</b>：可执行文件用 \"./bin/xxx\"、\"./xxx\" 或绝对路径；"
                + "不要假设当前目录，参数里的相对路径以部署包解压后的运行目录为准。\n"
                + "    (4) 无法确定正确 flag 时，给出\"最保守\"的命令（通常就是可执行文件本身、不带参数），"
                + "并在 reasoning 中说明\"未加参数、依赖程序默认值，如默认值不符需人工补充\"，交由人工确认，"
                + "而不是猜一个可能不存在的 flag。\n"
                + "  - port: 服务监听端口（整数）。选择规则：用户已指定则必须用该值；"
                + "NGINX 模式避免 80（80 是宿主机 nginx 默认端口），应基于 probe_server 探测结果选择空闲端口"
                + "（如 8080/8081/8021 等）；PROCESS 模式参考识别建议或常见默认（java 8080、node 3000、python 5000）；"
                + "DOCKER 模式可省略（由 compose 决定）\n"
                + "  - healthPath: 健康检查 URL 路径，以 / 开头，如 \"/\" 或 \"/health\"\n"
                + "  - reasoning: 用中文简要说明为何选择该类型/模式/命令/端口（供人工审阅）。"
                + "<b>必须是纯中文文本，严禁在字段值内混入 JSON 片段、XML/标签、工具调用语法或任何标记</b>，"
                + "整段作为一个完整 JSON 字符串值提交\n"
                + "4. 禁止编造。识别结果为 UNKNOWN 或证据不足时，基于包文件名/内容合理推断，并在 reasoning 中说明依据。\n"
                + "5. 只允许调用上述三个工具，绝不执行任何服务器写操作。";
    }

    static String buildUserPrompt(ProjectEntity project, PackageVersionEntity version,
                                  ServerEntity server, String userMode, Integer userPort) {
        String base = "请为以下部署任务生成部署方案：\n"
                + "- 项目：" + project.getName() + "\n"
                + "- 部署包：" + version.getFileName() + "（" + version.getSizeBytes() + " bytes）\n"
                + "- 目标服务器：" + server.getName() + "（" + server.getIp() + "）";
        if (userMode != null && !userMode.isBlank()) {
            base += "\n- 用户已指定部署方式：" + userMode + "（生成方案时必须围绕该方式，不要更改模式）";
        }
        if (userPort != null) {
            base += "\n- 用户已指定服务端口：" + userPort + "（生成方案时 port 必须使用该值，不要改回 80 或其他默认值）";
        }
        return base;
    }

    static List<LlmChatClient.ToolDef> toolDefs() {
        return List.of(
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction(
                        "inspect_package", "查看部署包的规则识别结果（类型/构建工具/入口/建议命令/端口）",
                        Map.of("type", "object", "properties", Map.of()))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction(
                        "probe_server", "只读探测目标服务器环境（OS/架构/可用运行时/磁盘/已有部署）",
                        Map.of("type", "object", "properties", Map.of()))),
                new LlmChatClient.ToolDef("function", new LlmChatClient.ToolDefFunction(
                        "submit_plan", "提交最终部署方案（JSON：detectedType/mode/startCmd/port/healthPath/reasoning）",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "detectedType", Map.of("type", "string"),
                                        "mode", Map.of("type", "string"),
                                        "startCmd", Map.of("type", "string"),
                                        "port", Map.of("type", "integer"),
                                        "healthPath", Map.of("type", "string"),
                                        "reasoning", Map.of("type", "string")),
                                "required", List.of("detectedType", "mode")))));
    }

    // ---------- 解析 / 辅助 ----------

    /**
     * 从 submit_plan 的 arguments（JSON 字符串）或自由文本中解析方案；失败返回 null。
     *
     * <p>兼容 LLM 常见的两种偏差：
     * <ol>
     *   <li>多包一层：{@code {"arguments":{...真正的方案...}}} —— 反复剥离 {@code arguments}
     *       层直到顶层即方案对象；</li>
     *   <li>夹带 prompt 未要求的额外字段 —— 用宽容的 {@link #PLAN_JSON}
     *       （FAIL_ON_UNKNOWN_PROPERTIES 关闭）反序列化。</li>
     * </ol>
     */
    static AiPlanProposal tryParseProposal(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String json = extractJson(text);
        if (json == null) {
            return null;
        }
        try {
            // 反复剥 "arguments" 包装层，直到顶层就是方案对象。
            // 兼容两种包装：{"arguments":{...}}（对象）与 {"arguments":"{...}"}（字符串化 JSON）。
            JsonNode node = PLAN_JSON.readTree(json);
            for (int depth = 0; depth < 5; depth++) {
                JsonNode inner = node.get("arguments");
                if (inner == null) {
                    break;
                }
                if (inner.isObject()) {
                    node = inner;
                } else if (inner.isTextual()) {
                    try {
                        node = PLAN_JSON.readTree(inner.asText());
                    } catch (Exception e) {
                        break; // 字符串不是合法 JSON，停止剥离
                    }
                } else {
                    break;
                }
            }
            AiPlanProposal p = PLAN_JSON.treeToValue(node, AiPlanProposal.class);
            // mode 白名单校验（缺失由后续 resolveModeFromType 补齐）
            if (p.mode() != null && !p.mode().isBlank()
                    && !MODES.contains(p.mode().trim().toUpperCase())) {
                return null;
            }
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    /** 提取文本中第一个 JSON 对象（容忍 ```json 代码块包裹或前后多余文本）。 */
    static String extractJson(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    private LlmModelConfigEntity resolveModel(Long modelId) {
        if (modelId != null) {
            return llmModelRepository.findById(modelId)
                    .filter(LlmModelConfigEntity::isEnabled)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "模型配置不存在或已禁用: " + modelId));
        }
        return llmModelRepository.findAll().stream()
                .filter(LlmModelConfigEntity::isEnabled)
                .findFirst()
                .orElse(null);
    }

    private PackageVersionEntity requireVersion(Long versionId) {
        return versionRepository.findById(versionId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "package version not found: " + versionId));
    }

    private ProjectEntity requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "project not found: " + projectId));
    }

    private ServerEntity requireServer(Long serverId) {
        return serverRepository.findById(serverId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "server not found: " + serverId));
    }

    private AiDeploymentPlanEntity requirePlan(Long id) {
        return planRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "AI deployment plan not found: " + id));
    }

    private static DetectedResult parseDetected(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, DetectedResult.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sanitizeForPath(String name) {
        return name == null ? "project" : name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    private Map<String, Object> buildPlanSnapshot(ProjectEntity project, PackageVersionEntity version,
                                                   ServerEntity server, String type, String mode,
                                                   String startCmd, Integer port, String healthPath) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("projectName", project.getName());
        m.put("fileName", version.getFileName());
        m.put("serverName", server.getName());
        m.put("detectedType", type);
        m.put("mode", mode);
        m.put("startCmd", startCmd);
        m.put("port", port);
        m.put("healthPath", healthPath);
        m.put("commands", buildCommandsPreview(mode, project.getName(), version.getId(),
                version.getFileName(), startCmd, port, healthPath));
        return m;
    }

    private AiPlanVO toVO(AiDeploymentPlanEntity p, ProjectEntity project,
                          PackageVersionEntity version, ServerEntity server) {
        String projectName = project != null ? project.getName() : null;
        Integer port = p.getPort();
        String healthPath = p.getHealthPath() == null || p.getHealthPath().isBlank() ? "/" : p.getHealthPath();
        String startCmd = p.getStartCmd();
        Map<String, String> preview = p.getMode() == null || projectName == null
                ? Map.of()
                : buildCommandsPreview(p.getMode(), projectName, p.getVersionId(),
                        version != null ? version.getFileName() : null, startCmd, port, healthPath);
        return new AiPlanVO(
                p.getId(), p.getProjectId(), projectName, p.getVersionId(),
                version != null ? version.getFileName() : null,
                p.getServerId(), server != null ? server.getName() : null,
                p.getStatus(), p.getSource(), p.getDetectedType(), p.getBuildTool(), p.getMode(),
                startCmd, port, healthPath, p.getReasoning(), p.getTrajectoryJson(),
                p.getDeploymentId(), p.getErrorSummary(), p.getCreatedAt(), p.getConfirmedAt(), preview);
    }

    // ---------- SSH 只读探测（可注入 fake 便于单测）----------

    interface SshProbe {
        CommandResult run(String command);
    }

    /** 默认实现：委托 SshService。 */
    private final class DefaultSshProbe implements SshProbe {
        private final Long serverId;

        private DefaultSshProbe(Long serverId) {
            this.serverId = serverId;
        }

        @Override
        public CommandResult run(String command) {
            return sshService.execute(serverId, command);
        }
    }

    // SshProbe 工厂：生产走 SshService，测试可替换
    private SshProbe newSshProbe(Long serverId) {
        return new DefaultSshProbe(serverId);
    }

    // 让 probeServer 可被测试替换（测试通过子类或直接测静态命令构造）
    CommandResult probe(Long serverId, String cmd) {
        return newSshProbe(serverId).run(cmd);
    }

    // ---------- 值对象 ----------

    /** AI 提交的方案（submit_plan 参数，Jackson 反序列化）。mode 可空（缺失按 detectedType 推导）。 */
    public record AiPlanProposal(String detectedType, String mode, String startCmd,
                                 Integer port, String healthPath, String reasoning) {
    }

    /** 确认请求（人工微调，字段均可空，空则用方案原值）。databaseId 语义同 DeploymentCreateRequest。 */
    public record ConfirmRequest(String startCmd, Integer port, String healthPath, String mode,
                                 Long databaseId) {
    }

    /** ReAct 轨迹单步。 */
    public record PlanStep(int step, String tool, String args, String result) {
    }

    /** 方案视图。 */
    public record AiPlanVO(Long id, Long projectId, String projectName, Long versionId, String fileName,
                           Long serverId, String serverName, String status, String source, String detectedType,
                           String buildTool, String mode, String startCmd, Integer port, String healthPath,
                           String reasoning, String trajectoryJson, Long deploymentId, String errorSummary,
                           LocalDateTime createdAt, LocalDateTime confirmedAt, Map<String, String> commands) {
    }
}
