package com.ops.deploy.ai;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.crypto.CredentialCryptoService;
import com.ops.deploy.deploy.ConfigFileGenerator;
import com.ops.deploy.deploy.DeploymentCreateRequest;
import com.ops.deploy.deploy.DeploymentService;
import com.ops.deploy.deploy.DeploymentVO;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 自主部署 {@link AiDeployService} 单测（mock 仓库 / LLM / SSH / 部署服务）。
 *
 * <p>覆盖：纯函数族（类型归一 / mode 推导 / 命令/端口/健康路径清洗）、命令预览构造、
 * tryParseProposal / extractJson 解析、规则引擎降级、generate 降级与 AI 两条路径、
 * confirm 状态机（含类型回填）与 abandon，probe_server 只读命令构造。
 */
@ExtendWith(MockitoExtension.class)
class AiDeployServiceTest {

    @Mock
    private AiDeploymentPlanRepository planRepository;
    @Mock
    private PackageVersionRepository versionRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ServerRepository serverRepository;
    @Mock
    private LlmModelConfigRepository llmModelRepository;
    @Mock
    private CredentialCryptoService crypto;
    @Mock
    private LlmChatClient llmClient;
    @Mock
    private SshService sshService;
    @Mock
    private DeploymentService deploymentService;
    @Mock
    private ProjectConfigFileRepository configFileRepository;
    @Mock
    private ConfigFileGenerator configGenerator;
    // 新增：项目访问控制 mock，void 断言方法默认 no-op，鉴权天然放行
    @Mock
    private ProjectAccessService accessService;

    @Captor
    private ArgumentCaptor<AiDeploymentPlanEntity> planCaptor;
    @Captor
    private ArgumentCaptor<DeploymentCreateRequest> reqCaptor;

    private AiDeployService service;

    private final AuthUser user = new AuthUser(1L, "ops", "OPS");

    @BeforeEach
    void setUp() {
        service = new AiDeployService(planRepository, versionRepository, projectRepository,
                serverRepository, llmModelRepository, crypto, llmClient, sshService, deploymentService,
                configFileRepository, configGenerator, accessService);
        lenient().when(planRepository.save(any(AiDeploymentPlanEntity.class)))
                .thenAnswer(inv -> {
                    AiDeploymentPlanEntity e = inv.getArgument(0);
                    if (e.getId() == null) {
                        e.setId(500L);
                    }
                    return e;
                });
    }

    // ---------- 纯函数：类型 / mode ----------

    @Test
    @DisplayName("normalizeType：AI 值非空 → 大写归一")
    void normalizeType_aiValueUppercased() {
        assertThat(AiDeployService.normalizeType(" java_maven ", project(null), null))
                .isEqualTo("JAVA_MAVEN");
    }

    @Test
    @DisplayName("normalizeType：AI 值空 → 回退项目类型")
    void normalizeType_fallbackToProject() {
        assertThat(AiDeployService.normalizeType(null, project("NODE"), null)).isEqualTo("NODE");
        assertThat(AiDeployService.normalizeType("  ", project("GO"), null)).isEqualTo("GO");
    }

    @Test
    @DisplayName("normalizeType：AI 与项目都空 → 回退识别类型 / UNKNOWN")
    void normalizeType_fallbackToDetected() {
        DetectedResult d = detected("PYTHON");
        assertThat(AiDeployService.normalizeType(null, project(null), d)).isEqualTo("PYTHON");
        assertThat(AiDeployService.normalizeType(null, project(null), null)).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("resolveModeFromType：STATIC_WEB→NGINX / DOCKER→DOCKER / 其余→PROCESS")
    void resolveModeFromType_mapping() {
        assertThat(AiDeployService.resolveModeFromType("STATIC_WEB")).isEqualTo("NGINX");
        assertThat(AiDeployService.resolveModeFromType("DOCKER")).isEqualTo("DOCKER");
        assertThat(AiDeployService.resolveModeFromType("JAVA_MAVEN")).isEqualTo("PROCESS");
        assertThat(AiDeployService.resolveModeFromType("UNKNOWN")).isEqualTo("PROCESS");
    }

    // ---------- 纯函数：清洗 ----------

    @Test
    @DisplayName("sanitizeStartCmd：null/空→null；含换行→null；超长→null；正常→trim")
    void sanitizeStartCmd_rules() {
        assertThat(AiDeployService.sanitizeStartCmd(null)).isNull();
        assertThat(AiDeployService.sanitizeStartCmd("   ")).isNull();
        assertThat(AiDeployService.sanitizeStartCmd("java -jar a.jar\nrm -rf /")).isNull();
        assertThat(AiDeployService.sanitizeStartCmd("x".repeat(513))).isNull();
        assertThat(AiDeployService.sanitizeStartCmd("  java -jar a.jar  ")).isEqualTo("java -jar a.jar");
    }

    @Test
    @DisplayName("sanitizePort：1-65535 保留，越界/null→null")
    void sanitizePort_rules() {
        assertThat(AiDeployService.sanitizePort(8080)).isEqualTo(8080);
        assertThat(AiDeployService.sanitizePort(1)).isEqualTo(1);
        assertThat(AiDeployService.sanitizePort(65535)).isEqualTo(65535);
        assertThat(AiDeployService.sanitizePort(0)).isNull();
        assertThat(AiDeployService.sanitizePort(65536)).isNull();
        assertThat(AiDeployService.sanitizePort(null)).isNull();
    }

    @Test
    @DisplayName("sanitizeHealthPath：null/空→/；非法（含空格/无斜杠）→/；合法保留")
    void sanitizeHealthPath_rules() {
        assertThat(AiDeployService.sanitizeHealthPath(null)).isEqualTo("/");
        assertThat(AiDeployService.sanitizeHealthPath(" ")).isEqualTo("/");
        assertThat(AiDeployService.sanitizeHealthPath("health")).isEqualTo("/");
        assertThat(AiDeployService.sanitizeHealthPath("/a b")).isEqualTo("/");
        assertThat(AiDeployService.sanitizeHealthPath("/health/z?x=1")).isEqualTo("/");
        assertThat(AiDeployService.sanitizeHealthPath("/health")).isEqualTo("/health");
        assertThat(AiDeployService.sanitizeHealthPath("/api/v1")).isEqualTo("/api/v1");
    }

    // ---------- 命令预览构造 ----------

    @Test
    @DisplayName("buildCommandsPreview PROCESS：unit 内容 + 启动 + 健康检查")
    void preview_process() {
        Map<String, String> m = AiDeployService.buildCommandsPreview(
                "PROCESS", "demo", 10L, "app.jar", "java -jar app.jar", 8080, "/health");
        assertThat(m).containsKeys("systemd unit 内容", "启动", "健康检查");
        assertThat(m.get("systemd unit 内容")).contains("ExecStart=").contains("java -jar app.jar");
        // jar 包落 workdir 根 → WorkingDirectory 不带 extract
        assertThat(m.get("systemd unit 内容")).contains("WorkingDirectory=/opt/deploy/demo/10");
        assertThat(m.get("启动")).contains("systemctl restart deploy-demo.service");
        assertThat(m.get("健康检查")).contains("8080").contains("/health");
    }

    @Test
    @DisplayName("buildCommandsPreview NGINX：站点 conf + 软链 + 重载")
    void preview_nginx() {
        Map<String, String> m = AiDeployService.buildCommandsPreview(
                "NGINX", "site", 10L, "site.zip", null, 80, "/");
        assertThat(m).containsKeys("站点配置 /etc/nginx/conf.d/deploy-site.conf", "软链", "重载");
        assertThat(m.get("软链")).contains("/opt/deploy/site/10/extract");
        assertThat(m.get("重载")).contains("nginx");
    }

    @Test
    @DisplayName("buildCommandsPreview DOCKER：compose 启动（无端口则无健康检查）")
    void preview_docker() {
        Map<String, String> m = AiDeployService.buildCommandsPreview(
                "DOCKER", "svc", 10L, "svc.tar.gz", null, null, null);
        assertThat(m).containsKeys("docker-compose.yml", "启动");
        assertThat(m.get("启动")).contains("docker-compose").contains("up -d");
        assertThat(m).doesNotContainKey("健康检查");
    }

    // ---------- 解析 ----------

    @Test
    @DisplayName("tryParseProposal：完整 JSON 解析成功，mode 白名单校验")
    void tryParseProposal_valid() {
        AiDeployService.AiPlanProposal p = AiDeployService.tryParseProposal(
                "{\"detectedType\":\"JAVA_MAVEN\",\"mode\":\"PROCESS\",\"startCmd\":\"java -jar app.jar\","
                        + "\"port\":8080,\"healthPath\":\"/health\",\"reasoning\":\"r\"}");
        assertThat(p).isNotNull();
        assertThat(p.detectedType()).isEqualTo("JAVA_MAVEN");
        assertThat(p.mode()).isEqualTo("PROCESS");
        assertThat(p.port()).isEqualTo(8080);
    }

    @Test
    @DisplayName("tryParseProposal：容忍前后多余文本/代码块包裹")
    void tryParseProposal_surroundedText() {
        AiDeployService.AiPlanProposal p = AiDeployService.tryParseProposal(
                "好的，方案如下：\n```json\n{\"detectedType\":\"NODE\",\"mode\":\"PROCESS\"}\n```\n以上");
        assertThat(p).isNotNull();
        assertThat(p.detectedType()).isEqualTo("NODE");
    }

    @Test
    @DisplayName("tryParseProposal：LLM 多包一层 {\"arguments\":{...}} 能自动剥离解析（plan 31 实测复现）")
    void tryParseProposal_unwrapsArgumentsLayer() {
        AiDeployService.AiPlanProposal p = AiDeployService.tryParseProposal(
                "{\"arguments\":{\"detectedType\":\"STATIC_WEB\",\"mode\":\"NGINX\",\"startCmd\":\"\","
                        + "\"port\":80,\"healthPath\":\"/\",\"reasoning\":\"r\"}}");
        assertThat(p).isNotNull();
        assertThat(p.detectedType()).isEqualTo("STATIC_WEB");
        assertThat(p.mode()).isEqualTo("NGINX");
        assertThat(p.port()).isEqualTo(80);
        assertThat(p.healthPath()).isEqualTo("/");
    }

    @Test
    @DisplayName("tryParseProposal：arguments 层内为字符串化的 JSON 也能解析（双重包装）")
    void tryParseProposal_unwrapsArgumentsAsJsonString() {
        AiDeployService.AiPlanProposal p = AiDeployService.tryParseProposal(
                "{\"arguments\":\"{\\\"detectedType\\\":\\\"JAVA_MAVEN\\\",\\\"mode\\\":\\\"PROCESS\\\"}\"}");
        assertThat(p).isNotNull();
        assertThat(p.detectedType()).isEqualTo("JAVA_MAVEN");
    }

    @Test
    @DisplayName("tryParseProposal：夹带 prompt 未要求的额外字段不致失败（宽容反序列化）")
    void tryParseProposal_ignoresUnknownFields() {
        AiDeployService.AiPlanProposal p = AiDeployService.tryParseProposal(
                "{\"detectedType\":\"NODE\",\"mode\":\"PROCESS\",\"confidence\":90,\"extraNote\":\"x\"}");
        assertThat(p).isNotNull();
        assertThat(p.detectedType()).isEqualTo("NODE");
        assertThat(p.mode()).isEqualTo("PROCESS");
    }

    @Test
    @DisplayName("tryParseProposal：mode 不在白名单 / 非 JSON / 空 → null")
    void tryParseProposal_invalid() {
        assertThat(AiDeployService.tryParseProposal(
                "{\"detectedType\":\"NODE\",\"mode\":\"BARE_SSH\"}")).isNull();
        assertThat(AiDeployService.tryParseProposal("不是 JSON")).isNull();
        assertThat(AiDeployService.tryParseProposal("")).isNull();
        assertThat(AiDeployService.tryParseProposal(null)).isNull();
    }

    @Test
    @DisplayName("extractJson：取第一个 { 到最后一个 }；无 JSON → null")
    void extractJson_rules() {
        assertThat(AiDeployService.extractJson("xx {\"a\":1} yy")).isEqualTo("{\"a\":1}");
        assertThat(AiDeployService.extractJson("no braces")).isNull();
        assertThat(AiDeployService.extractJson(null)).isNull();
    }

    // ---------- 规则引擎降级 ----------

    @Test
    @DisplayName("ruleEngine：项目类型优先 + 三级兜底（命令/端口/健康路径）")
    void ruleEngine_fallbackChain() {
        ProjectEntity p = project("JAVA_MAVEN");
        p.setDefaultStartCmd("java -jar app.jar");
        p.setDefaultPort(8080);
        p.setDefaultHealthPath("/actuator/health");
        DetectedResult d = detected("JAVA_MAVEN");
        d.setSuggestedStartCmd(null); // 项目默认兜底
        d.setSuggestedPort(null);
        d.setSuggestedHealthPath(null);

        AiDeployService.AiPlanProposal prop = AiDeployService.ruleEngine(p, version("app.jar", null), d);
        assertThat(prop.detectedType()).isEqualTo("JAVA_MAVEN");
        assertThat(prop.startCmd()).isEqualTo("java -jar app.jar");
        assertThat(prop.port()).isEqualTo(8080);
        assertThat(prop.healthPath()).isEqualTo("/actuator/health");
    }

    @Test
    @DisplayName("ruleEngine：无项目类型用识别类型；无识别则 UNKNOWN")
    void ruleEngine_typeFallback() {
        DetectedResult d = detected("PYTHON");
        assertThat(AiDeployService.ruleEngine(project(null), version("a.zip", null), d)
                .detectedType()).isEqualTo("PYTHON");
        assertThat(AiDeployService.ruleEngine(project(null), version("a.zip", null), null)
                .detectedType()).isEqualTo("UNKNOWN");
    }

    // ---------- generate：降级（无模型） ----------

    @Test
    @DisplayName("generate：无可用模型 → 降级 RULE，落库 DRAFT")
    void generate_noModel_fallsBackToRule() {
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version("app.jar", null)));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("JAVA_MAVEN")));
        when(serverRepository.findById(2L)).thenReturn(Optional.of(server()));
        when(llmModelRepository.findAll()).thenReturn(List.of());

        AiDeployService.AiPlanVO vo = service.generate(10L, 2L, null, user, null, null);

        assertThat(vo.source()).isEqualTo("RULE");
        assertThat(vo.status()).isEqualTo("DRAFT");
        assertThat(vo.mode()).isEqualTo("PROCESS");
        verify(planRepository).save(planCaptor.capture());
        AiDeploymentPlanEntity saved = planCaptor.getValue();
        assertThat(saved.getSource()).isEqualTo("RULE");
        assertThat(saved.getOperator()).isEqualTo("ops");
        assertThat(saved.getTrajectoryJson()).contains("rule_engine");
        verify(llmClient, never()).chatWithTools(anyString(), anyString(), anyString(), any(), any());
    }

    // ---------- generate：AI 路径（submit_plan） ----------

    @Test
    @DisplayName("generate：LLM 经 submit_plan 提交 → source=AI，mode/type 强约束")
    void generate_aiSubmitPlan() {
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version("app.jar", null)));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project(null)));
        when(serverRepository.findById(2L)).thenReturn(Optional.of(server()));
        when(llmModelRepository.findAll()).thenReturn(List.of(model()));
        when(crypto.decrypt(any(byte[].class))).thenReturn("sk-test");
        String args = "{\"detectedType\":\"java_maven\",\"mode\":\"PROCESS\","
                + "\"startCmd\":\"java -jar app.jar\",\"port\":9090,\"healthPath\":\"/health\","
                + "\"reasoning\":\"jar 包 → JAVA_MAVEN\"}";
        when(llmClient.chatWithTools(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(assistantWithSubmitPlan(args));

        AiDeployService.AiPlanVO vo = service.generate(10L, 2L, null, user, null, null);

        assertThat(vo.source()).isEqualTo("AI");
        assertThat(vo.status()).isEqualTo("DRAFT");
        assertThat(vo.detectedType()).isEqualTo("JAVA_MAVEN"); // 归一为大写
        assertThat(vo.mode()).isEqualTo("PROCESS");
        assertThat(vo.port()).isEqualTo(9090);
        verify(llmClient).chatWithTools(anyString(), eq("sk-test"), anyString(), any(), any());
    }

    @Test
    @DisplayName("generate：LLM 调用抛 BizException → 降级 RULE（不中断）")
    void generate_aiFailureFallsBack() {
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version("app.jar", null)));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("NODE")));
        when(serverRepository.findById(2L)).thenReturn(Optional.of(server()));
        when(llmModelRepository.findAll()).thenReturn(List.of(model()));
        when(crypto.decrypt(any(byte[].class))).thenReturn("sk-test");
        when(llmClient.chatWithTools(anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new BizException(ErrorCode.LLM_ERROR, "boom"));

        AiDeployService.AiPlanVO vo = service.generate(10L, 2L, null, user, null, null);

        assertThat(vo.source()).isEqualTo("RULE");
        assertThat(vo.status()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("generate：指定 modelId 但禁用 → NOT_FOUND")
    void generate_disabledModelNotFound() {
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version("app.jar", null)));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project(null)));
        when(serverRepository.findById(2L)).thenReturn(Optional.of(server()));
        LlmModelConfigEntity off = model();
        off.setEnabled(false);
        when(llmModelRepository.findById(9L)).thenReturn(Optional.of(off));

        assertThatThrownBy(() -> service.generate(10L, 2L, 9L, user, null, null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ---------- generate：userMode（用户手选部署方式）----------

    @Test
    @DisplayName("generate：userMode 不在白名单 → BAD_REQUEST（先于一切查询）")
    void generate_illegalUserMode_throwsBadRequest() {
        assertThatThrownBy(() -> service.generate(10L, 2L, null, user, "K8S", null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        verify(versionRepository, never()).findById(any());
    }

    @Test
    @DisplayName("generate：userMode=DOCKER 无 compose 文件 → 自动生成补齐，方案继续生成")
    void generate_userModeDockerMissingCompose_autoGenerates() {
        // 合法的 DOCKER 包（识别类型 DOCKER，包内含 Dockerfile）→ 前置校验放行，compose 缺失走自动生成
        when(versionRepository.findById(10L))
                .thenReturn(Optional.of(version("app.zip", "{\"detectedType\":\"DOCKER\"}")));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("DOCKER")));
        when(serverRepository.findById(2L)).thenReturn(Optional.of(server()));
        lenient().when(llmModelRepository.findAll()).thenReturn(List.of());
        when(configFileRepository.findByProjectIdAndFileKey(1L, "compose")).thenReturn(Optional.empty());

        AiDeployService.AiPlanVO vo = service.generate(10L, 2L, null, user, "DOCKER", null);

        assertThat(vo.mode()).isEqualTo("DOCKER");
        assertThat(vo.status()).isEqualTo("DRAFT");
        verify(configGenerator).ensureCompose(any(ProjectEntity.class), any(), any());
        verify(planRepository).save(any(AiDeploymentPlanEntity.class));
    }

    @Test
    @DisplayName("generate：userMode=DOCKER 但包内无 Dockerfile（识别非 DOCKER）且无 compose → 正常生成方案并触发 compose/Dockerfile 自动补齐")
    void generate_userModeDockerNoDockerfile_autoGeneratesComposeAndDockerfile() {
        // 识别类型 PYTHON（无 Dockerfile），用户选 DOCKER 且未配 compose：
        // 不再前置拦截——方案照常生成，ensureCompose 内部顺带补生成 Dockerfile + compose
        when(versionRepository.findById(10L))
                .thenReturn(Optional.of(version("backend.zip", "{\"detectedType\":\"PYTHON\"}")));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("PYTHON")));
        when(serverRepository.findById(2L)).thenReturn(Optional.of(server()));
        lenient().when(llmModelRepository.findAll()).thenReturn(List.of());
        when(configFileRepository.findByProjectIdAndFileKey(1L, "compose")).thenReturn(Optional.empty());

        AiDeployService.AiPlanVO vo = service.generate(10L, 2L, null, user, "DOCKER", null);

        assertThat(vo.mode()).isEqualTo("DOCKER");
        assertThat(vo.status()).isEqualTo("DRAFT");
        verify(configGenerator).ensureCompose(any(ProjectEntity.class), any(), any());
        verify(planRepository).save(any(AiDeploymentPlanEntity.class));
    }

    @Test
    @DisplayName("generate：userMode=NGINX 覆盖类型推导（JAVA_MAVEN 默认 PROCESS）")
    void generate_userModeOverridesDerived() {
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version("app.jar", null)));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("JAVA_MAVEN")));
        when(serverRepository.findById(2L)).thenReturn(Optional.of(server()));
        lenient().when(llmModelRepository.findAll()).thenReturn(List.of());

        AiDeployService.AiPlanVO vo = service.generate(10L, 2L, null, user, "NGINX", null);

        assertThat(vo.mode()).isEqualTo("NGINX");
        verify(planRepository).save(planCaptor.capture());
        assertThat(planCaptor.getValue().getMode()).isEqualTo("NGINX");
    }

    @Test
    @DisplayName("generate：AI 上报 mode 与 userMode 不一致 → 落库 userMode")
    void generate_userModeOverridesAiReported() {
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version("app.jar", null)));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("JAVA_MAVEN")));
        when(serverRepository.findById(2L)).thenReturn(Optional.of(server()));
        when(llmModelRepository.findAll()).thenReturn(List.of(model()));
        when(crypto.decrypt(any(byte[].class))).thenReturn("sk-test");
        String args = "{\"detectedType\":\"java_maven\",\"mode\":\"PROCESS\","
                + "\"startCmd\":\"java -jar app.jar\",\"port\":9090,\"healthPath\":\"/health\","
                + "\"reasoning\":\"jar 包 → JAVA_MAVEN\"}";
        when(llmClient.chatWithTools(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(assistantWithSubmitPlan(args));

        AiDeployService.AiPlanVO vo = service.generate(10L, 2L, null, user, "NGINX", null);

        assertThat(vo.source()).isEqualTo("AI");
        assertThat(vo.mode()).isEqualTo("NGINX");
    }

    @Test
    @DisplayName("confirm：req.mode 覆盖 plan.mode 并回写 plan")
    void confirm_modeOverride() {
        AiDeploymentPlanEntity plan = draftPlan();
        plan.setMode("NGINX");
        plan.setStartCmd(null);
        plan.setPort(8080);
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("STATIC_WEB")));
        when(deploymentService.create(any(DeploymentCreateRequest.class), eq(user)))
                .thenReturn(depVo(99L));
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version("app.jar", null)));
        when(serverRepository.findById(2L)).thenReturn(Optional.of(server()));

        service.confirm(1L, new AiDeployService.ConfirmRequest(null, 9090, null, "PROCESS", null), user);

        verify(deploymentService).create(reqCaptor.capture(), eq(user));
        assertThat(reqCaptor.getValue().getMode()).isEqualTo("PROCESS");
        assertThat(plan.getMode()).isEqualTo("PROCESS");
    }

    @Test
    @DisplayName("buildUserPrompt：userMode 非空时注入指定方式提示，为空时行为不变")
    void buildUserPrompt_containsUserModeHint() {
        ProjectEntity p = project("JAVA_MAVEN");
        PackageVersionEntity v = version("app.jar", null);
        ServerEntity s = server();
        assertThat(AiDeployService.buildUserPrompt(p, v, s, "NGINX", null))
                .contains("用户已指定部署方式：NGINX");
        assertThat(AiDeployService.buildUserPrompt(p, v, s, null, null))
                .doesNotContain("用户已指定部署方式");
    }

    @Test
    @DisplayName("buildUserPrompt：userPort 非空时注入端口提示（避免 NGINX 默认 80）")
    void buildUserPrompt_containsUserPortHint() {
        ProjectEntity p = project("JAVA_MAVEN");
        PackageVersionEntity v = version("app.jar", null);
        ServerEntity s = server();
        assertThat(AiDeployService.buildUserPrompt(p, v, s, "NGINX", 8021))
                .contains("用户已指定服务端口：8021");
        assertThat(AiDeployService.buildUserPrompt(p, v, s, "NGINX", null))
                .doesNotContain("用户已指定服务端口");
    }

    @Test
    @DisplayName("generate：userPort 非空 → 落库 port 取用户值，覆盖 AI 建议")
    void generate_userPortOverridesAiSuggestion() {
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version("site.zip", null)));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("STATIC_WEB")));
        when(serverRepository.findById(2L)).thenReturn(Optional.of(server()));
        LlmModelConfigEntity m = model();
        when(llmModelRepository.findAll()).thenReturn(List.of(m));
        // LLM 在 prompt 中被告知 userPort=8021，但仍假设它回传 80（验证后端强制 userPort 优先）
        when(crypto.decrypt(any(byte[].class))).thenReturn("sk-test");
        when(llmClient.chatWithTools(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(assistantWithSubmitPlan(
                        "{\"detectedType\":\"STATIC_WEB\",\"mode\":\"NGINX\",\"port\":80,\"healthPath\":\"/\"}"));

        AiDeployService.AiPlanVO vo = service.generate(10L, 2L, null, user, "NGINX", 8021);

        assertThat(vo.source()).isEqualTo("AI");
        assertThat(vo.port()).isEqualTo(8021);
    }

    @Test
    @DisplayName("normalizeReasoning：非空原样保留（trim），空/null 用方案字段合成摘要（永不空白）")
    void normalizeReasoning_fallback() {
        // 非空 → 原样（去首尾空白）
        assertThat(AiDeployService.normalizeReasoning("  识别为 GO  ", "GO", "PROCESS", "./categraf", 17000))
                .isEqualTo("识别为 GO");
        // 空串 / null → 合成摘要，含类型/模式/命令/端口
        assertThat(AiDeployService.normalizeReasoning("", "GO", "PROCESS", "./categraf", 17000))
                .contains("GO").contains("PROCESS").contains("./categraf").contains("17000");
        assertThat(AiDeployService.normalizeReasoning(null, "UNKNOWN", "PROCESS", null, null))
                .contains("UNKNOWN").contains("PROCESS").contains("启动命令 无").contains("端口 未指定");
    }

    @Test
    @DisplayName("generate 回归（plan 48）：LLM 方案缺 reasoning 字段 → VO reasoning 为合成摘要，前端不再显示（无）")
    void generate_missingReasoning_synthesizesSummary() {
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version("categraf.tar.gz", null)));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("GO")));
        when(serverRepository.findById(2L)).thenReturn(Optional.of(server()));
        when(llmModelRepository.findAll()).thenReturn(List.of(model()));
        when(crypto.decrypt(any(byte[].class))).thenReturn("sk-test");
        // 复现线上：思考模型把内部标记泄漏进 arguments，导致截出的 JSON 缺 reasoning 字段（仅剩基本字段）
        String args = "{\"detectedType\":\"UNKNOWN\",\"mode\":\"PROCESS\",\"startCmd\":\"./categraf\","
                + "\"port\":17000,\"healthPath\":\"/\"}";
        when(llmClient.chatWithTools(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(assistantWithSubmitPlan(args));

        AiDeployService.AiPlanVO vo = service.generate(10L, 2L, null, user, "PROCESS", 17000);

        assertThat(vo.source()).isEqualTo("AI");
        assertThat(vo.reasoning()).isNotBlank();           // 关键：不再为 null/空白
        assertThat(vo.reasoning()).contains("方案摘要");     // 走的是合成兜底，而非 LLM 原始说明
    }

    @Test
    @DisplayName("generate：userPort 越界 → BAD_REQUEST")
    void generate_invalidUserPort_throwsBadRequest() {
        assertThatThrownBy(() -> service.generate(10L, 2L, null, user, null, 99999))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> service.generate(10L, 2L, null, user, null, 0))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    // ---------- confirm ----------

    @Test
    @DisplayName("confirm：DRAFT → 回填项目类型 + 调部署管线 + 置 EXECUTED 关联部署")
    void confirm_draftExecutesAndBackfillsType() {
        AiDeploymentPlanEntity plan = draftPlan();
        plan.setDetectedType("JAVA_MAVEN");
        plan.setStartCmd("java -jar app.jar");
        plan.setPort(8080);
        plan.setHealthPath("/health");
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project(null)));
        when(deploymentService.create(any(DeploymentCreateRequest.class), eq(user)))
                .thenReturn(depVo(99L));
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version("app.jar", null)));
        when(serverRepository.findById(2L)).thenReturn(Optional.of(server()));

        AiDeployService.AiPlanVO vo = service.confirm(1L, null, user);

        // 项目类型回填
        assertThat(projectRepository.findById(1L).get().getDetectedType()).isEqualTo("JAVA_MAVEN");
        // 组装创建请求
        verify(deploymentService).create(reqCaptor.capture(), eq(user));
        DeploymentCreateRequest cr = reqCaptor.getValue();
        assertThat(cr.getVersionId()).isEqualTo(10L);
        assertThat(cr.getServerId()).isEqualTo(2L);
        assertThat(cr.getStartCmd()).isEqualTo("java -jar app.jar");
        assertThat(cr.getPort()).isEqualTo(8080);
        assertThat(cr.getHealthPath()).isEqualTo("/health");
        // 状态流转
        assertThat(vo.status()).isEqualTo("EXECUTED");
        assertThat(vo.deploymentId()).isEqualTo(99L);
        assertThat(plan.getConfirmedAt()).isNotNull();
        verify(planRepository).save(plan);
    }

    @Test
    @DisplayName("confirm：req 覆盖字段优先生效")
    void confirm_requestOverridesPlan() {
        AiDeploymentPlanEntity plan = draftPlan();
        plan.setStartCmd("java -jar old.jar");
        plan.setPort(8080);
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("JAVA_MAVEN")));
        when(deploymentService.create(any(DeploymentCreateRequest.class), eq(user)))
                .thenReturn(depVo(99L));
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version("app.jar", null)));
        when(serverRepository.findById(2L)).thenReturn(Optional.of(server()));

        service.confirm(1L, new AiDeployService.ConfirmRequest("java -jar new.jar", 9090, "/ping", null, null), user);

        verify(deploymentService).create(reqCaptor.capture(), eq(user));
        assertThat(reqCaptor.getValue().getStartCmd()).isEqualTo("java -jar new.jar");
        assertThat(reqCaptor.getValue().getPort()).isEqualTo(9090);
        assertThat(reqCaptor.getValue().getHealthPath()).isEqualTo("/ping");
    }

    @Test
    @DisplayName("confirm：req.databaseId 透传部署请求（-1 自动建库 / 正值已登记库）")
    void confirm_databaseIdPassThrough() {
        AiDeploymentPlanEntity plan = draftPlan();
        plan.setStartCmd("java -jar app.jar");
        plan.setPort(8080);
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("JAVA_MAVEN")));
        when(deploymentService.create(any(DeploymentCreateRequest.class), eq(user)))
                .thenReturn(depVo(99L));
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version("app.jar", null)));
        when(serverRepository.findById(2L)).thenReturn(Optional.of(server()));

        service.confirm(1L, new AiDeployService.ConfirmRequest(null, null, null, null, -1L), user);

        verify(deploymentService).create(reqCaptor.capture(), eq(user));
        assertThat(reqCaptor.getValue().getDatabaseId()).isEqualTo(-1L);
    }

    @Test
    @DisplayName("confirm：非 DRAFT（EXECUTED）→ 抛 BAD_REQUEST")
    void confirm_nonDraftThrows() {
        AiDeploymentPlanEntity plan = draftPlan();
        plan.setStatus("EXECUTED");
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));

        assertThatThrownBy(() -> service.confirm(1L, null, user))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        verify(deploymentService, never()).create(any(), any(AuthUser.class));
    }

    @Test
    @DisplayName("confirm：方案不存在 → NOT_FOUND")
    void confirm_notFound() {
        when(planRepository.findById(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.confirm(404L, null, user))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ---------- abandon ----------

    @Test
    @DisplayName("abandon：DRAFT → ABANDONED")
    void abandon_draft() {
        AiDeploymentPlanEntity plan = draftPlan();
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));

        service.abandon(1L, user);

        assertThat(plan.getStatus()).isEqualTo("ABANDONED");
        verify(planRepository).save(plan);
    }

    @Test
    @DisplayName("abandon：非 DRAFT → 抛 BAD_REQUEST")
    void abandon_nonDraftThrows() {
        AiDeploymentPlanEntity plan = draftPlan();
        plan.setStatus("EXECUTED");
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));

        assertThatThrownBy(() -> service.abandon(1L, user))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    // ---------- probe_server 只读命令 ----------

    @Test
    @DisplayName("probe_server：构造只读探测命令（含 OS/运行时/磁盘/已有部署），无写操作")
    void probeServer_buildsReadOnlyCommand() {
        ProjectEntity p = new ProjectEntity();
        p.setId(1L);
        p.setName("demo svc");
        when(sshService.execute(eq(2L), anyString()))
                .thenReturn(CommandResult.ok("ok", 0));

        service.probeServer(server(), p);

        ArgumentCaptor<String> cmdCaptor = ArgumentCaptor.forClass(String.class);
        verify(sshService).execute(eq(2L), cmdCaptor.capture());
        String cmd = cmdCaptor.getValue();
        assertThat(cmd).contains("/etc/os-release").contains("uname -m");
        assertThat(cmd).contains("docker").contains("nginx").contains("java");
        assertThat(cmd).contains("df -h /opt");
        // 项目名含空格 → 路径清洗为下划线
        assertThat(cmd).contains("/opt/deploy/demo_svc");
        // 护栏：只读探测，绝不写
        assertThat(cmd).doesNotContain("rm -rf").doesNotContain("systemctl start")
                .doesNotContain("docker-compose").doesNotContain("nginx -s reload");
    }

    // ---------- 辅助构造 ----------

    private static ProjectEntity project(String detectedType) {
        ProjectEntity p = new ProjectEntity();
        p.setId(1L);
        p.setName("demo");
        p.setDetectedType(detectedType);
        return p;
    }

    private static ServerEntity server() {
        ServerEntity s = new ServerEntity();
        s.setId(2L);
        s.setName("srv");
        s.setIp("10.0.0.2");
        return s;
    }

    private static PackageVersionEntity version(String fileName, String detectedJson) {
        PackageVersionEntity v = new PackageVersionEntity();
        v.setId(10L);
        v.setProjectId(1L);
        v.setFileName(fileName);
        v.setSizeBytes(1024L);
        v.setDetectedJson(detectedJson);
        return v;
    }

    private static DetectedResult detected(String type) {
        DetectedResult d = new DetectedResult();
        d.setDetectedType(type);
        d.setBuildTool("maven");
        return d;
    }

    private static LlmModelConfigEntity model() {
        LlmModelConfigEntity m = new LlmModelConfigEntity();
        m.setId(3L);
        m.setName("gpt");
        m.setBaseUrl("http://llm:8000/v1");
        m.setModel("gpt-4");
        m.setApiKeyCipher(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18});
        m.setEnabled(true);
        return m;
    }

    private static AiDeploymentPlanEntity draftPlan() {
        AiDeploymentPlanEntity p = new AiDeploymentPlanEntity();
        p.setId(1L);
        p.setProjectId(1L);
        p.setVersionId(10L);
        p.setServerId(2L);
        p.setStatus("DRAFT");
        p.setSource("AI");
        p.setMode("PROCESS");
        p.setHealthPath("/");
        return p;
    }

    private static DeploymentVO depVo(Long id) {
        DeploymentVO vo = new DeploymentVO();
        vo.setId(id);
        return vo;
    }

    /** 构造一条 assistant 消息，其 toolCalls 含一次 submit_plan 调用。 */
    private static LlmChatClient.ToolMessage assistantWithSubmitPlan(String argsJson) {
        LlmChatClient.ToolCallFunction fn = new LlmChatClient.ToolCallFunction("submit_plan", argsJson);
        LlmChatClient.ToolCall call = new LlmChatClient.ToolCall("call_1", "function", fn);
        return new LlmChatClient.ToolMessage("assistant", null, List.of(call), null);
    }
}
