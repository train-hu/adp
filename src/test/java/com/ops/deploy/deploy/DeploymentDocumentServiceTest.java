package com.ops.deploy.deploy;

import com.ops.deploy.ai.AiDeploymentPlanEntity;
import com.ops.deploy.ai.AiDeploymentPlanRepository;
import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.project.PackageVersionEntity;
import com.ops.deploy.project.PackageVersionRepository;
import com.ops.deploy.project.ProjectEntity;
import com.ops.deploy.project.ProjectRepository;
import com.ops.deploy.server.ServerEntity;
import com.ops.deploy.server.ServerRepository;
import com.ops.deploy.user.UserEntity;
import com.ops.deploy.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DeploymentDocumentService} 单测（mock 各 repository，不连库）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>redact 脱敏纯函数（密码/密钥/长随机串 → ***，正常文本不误伤，null/空串安全）；</li>
 *   <li>generate：组装两份 markdown 并 upsert 落库，deployment 不存在 → NOT_FOUND；</li>
 *   <li>get：type=deploy/maintain 返回对应内容、非法 type → BAD_REQUEST、
 *       文档未生成 → NOT_FOUND（提示重新生成）；</li>
 *   <li>模板填充：不同 projectType（JAVA_MAVEN vs STATIC_WEB）的 FAQ/启动命令差异。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DeploymentDocumentServiceTest {

    @Mock
    private DeploymentRepository deploymentRepository;
    @Mock
    private DeploymentStepRepository stepRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private PackageVersionRepository versionRepository;
    @Mock
    private ServerRepository serverRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private DeploymentDocumentRepository documentRepository;
    @Mock
    private AiDeploymentPlanRepository planRepository;

    @Captor
    private ArgumentCaptor<DeploymentDocumentEntity> docCaptor;

    private DeploymentDocumentService service;

    @BeforeEach
    void setUp() {
        service = new DeploymentDocumentService(deploymentRepository, stepRepository,
                projectRepository, versionRepository, serverRepository, userRepository,
                documentRepository, planRepository);
    }

    // ---------- redact 脱敏 ----------

    @Test
    @DisplayName("脱敏：-p / --password= / password= / secret= / token= / Bearer 均替换为 ***")
    void redact_passwordLikeFragments() {
        assertThat(DeploymentDocumentService.redact("psql -U postgres -p S3cret123"))
                .isEqualTo("psql -U postgres -p ***");
        assertThat(DeploymentDocumentService.redact("mysql --password=abc123 host"))
                .isEqualTo("mysql --password=*** host");
        assertThat(DeploymentDocumentService.redact("jdbc:postgresql://h:5432/db?password=hunter2&x=1"))
                .isEqualTo("jdbc:postgresql://h:5432/db?password=***&x=1");
        assertThat(DeploymentDocumentService.redact("redis-cli -a topsecret"))
                .doesNotContain("topsecret");
        assertThat(DeploymentDocumentService.redact("curl -H 'Authorization: Bearer eyJhbGci0000000000000000'"))
                .contains("Authorization: Bearer ***")
                .doesNotContain("eyJhbGci0000000000000000");
        assertThat(DeploymentDocumentService.redact("export MY_SECRET=sk_live_123"))
                .isEqualTo("export MY_SECRET=***");
        assertThat(DeploymentDocumentService.redact("token=abcd1234"))
                .isEqualTo("token=***");
    }

    @Test
    @DisplayName("脱敏：正常路径/端口/URL/命令不误伤")
    void redact_normalTextNotTouched() {
        assertThat(DeploymentDocumentService.redact("mkdir -p /opt/deploy/demo/10"))
                .isEqualTo("mkdir -p /opt/deploy/demo/10");
        assertThat(DeploymentDocumentService.redact(
                "curl -sf -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:8080/health"))
                .isEqualTo("curl -sf -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:8080/health");
        assertThat(DeploymentDocumentService.redact("systemctl restart deploy-demo.service"))
                .isEqualTo("systemctl restart deploy-demo.service");
        assertThat(DeploymentDocumentService.redact("ssh deploy@10.0.0.8 -p 2222"))
                .isEqualTo("ssh deploy@10.0.0.8 -p ***");
    }

    @Test
    @DisplayName("脱敏：Base64/长随机串替换，纯小写 hex（sha256 指纹）不误伤；null/空串安全")
    void redact_longRandomStrings() {
        String b64 = "Ab3dEf6hIj9kLm2nOp5qRs8tUv1wXy4z"; // 32 位混合大小写
        assertThat(DeploymentDocumentService.redact("key " + b64)).isEqualTo("key ***");
        assertThat(DeploymentDocumentService.redact(
                "sha256: 3a7bd3e2360a3d29eea436fcfb7e44c735d117c42d1c1835420b6b9942dd4f1b"))
                .contains("3a7bd3e2360a3d29eea436fcfb7e44c735d117c42d1c1835420b6b9942dd4f1b");
        assertThat(DeploymentDocumentService.redact("")).isEqualTo("");
        assertThat(DeploymentDocumentService.redact(null)).isNull();
    }

    // ---------- generate ----------

    @Test
    @DisplayName("generate：组装两份 markdown 并落库（含项目名/unit 名/回滚命令/时间线脱敏命令）")
    void generate_buildsBothDocsAndSaves() {
        DeploymentEntity d = deployment(1L, 10L, 2L, 8L, 9L);
        when(deploymentRepository.findById(1L)).thenReturn(Optional.of(d));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("demo", "JAVA_MAVEN")));
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version()));
        when(serverRepository.findById(2L)).thenReturn(Optional.of(server("web-01", "10.0.0.8")));
        when(userRepository.findById(8L)).thenReturn(Optional.of(user(8L, "ops")));
        when(stepRepository.findByDeploymentIdOrderBySeq(1L)).thenReturn(steps());
        when(documentRepository.findByDeploymentId(1L)).thenReturn(Optional.empty());

        DeploymentDocumentVO vo = service.generate(1L);

        verify(documentRepository).save(docCaptor.capture());
        DeploymentDocumentEntity saved = docCaptor.getValue();
        assertThat(saved.getDeploymentId()).isEqualTo(1L);
        assertThat(saved.getDeployMarkdown())
                .contains("demo")
                .contains("deploy-demo.service")
                .contains("/etc/systemd/system/deploy-demo.service")
                .contains("systemctl daemon-reload && systemctl enable deploy-demo.service && systemctl restart deploy-demo.service")
                .contains("本部署非 AI 方案生成（人工配置），无 AI 决策记录。")
                .contains("（部署时未采集环境快照）");
        assertThat(saved.getMaintainMarkdown())
                .contains("systemctl start deploy-demo.service")
                .contains("journalctl -u deploy-demo.service -f --no-pager")
                .contains("systemctl status deploy-demo.service")
                .contains("FAQ");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        assertThat(vo.getDeploymentId()).isEqualTo(1L);
        assertThat(vo.getType()).isEqualTo("deploy");
        assertThat(vo.getContent()).isEqualTo(saved.getDeployMarkdown());
        assertThat(vo.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("generate：已存在文档 → 更新（不新建）")
    void generate_existingDoc_updates() {
        DeploymentEntity d = deployment(1L, 10L, 2L, 8L, null);
        when(deploymentRepository.findById(1L)).thenReturn(Optional.of(d));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("demo", "JAVA_MAVEN")));
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version()));
        when(serverRepository.findById(2L)).thenReturn(Optional.of(server("web-01", "10.0.0.8")));
        when(userRepository.findById(8L)).thenReturn(Optional.of(user(8L, "ops")));
        when(stepRepository.findByDeploymentIdOrderBySeq(1L)).thenReturn(steps());
        DeploymentDocumentEntity existing = new DeploymentDocumentEntity();
        existing.setId(100L);
        existing.setDeploymentId(1L);
        existing.setDeployMarkdown("old");
        when(documentRepository.findByDeploymentId(1L)).thenReturn(Optional.of(existing));

        service.generate(1L);

        verify(documentRepository).save(docCaptor.capture());
        DeploymentDocumentEntity saved = docCaptor.getValue();
        assertThat(saved.getId()).isEqualTo(100L);
        assertThat(saved.getDeployMarkdown()).isNotEqualTo("old");
    }

    @Test
    @DisplayName("generate：deployment 不存在 → NOT_FOUND")
    void generate_deploymentNotFound_throws404() {
        when(deploymentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generate(99L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(documentRepository, never()).save(any(DeploymentDocumentEntity.class));
    }

    @Test
    @DisplayName("generate：STATIC_WEB 类型 → FAQ/启动命令按类型差异化")
    void generate_projectTypeVariesFaqAndStartup() {
        DeploymentEntity d = deployment(1L, 10L, 2L, 8L, null);
        d.setStartCmd(null); // 静态站通常无启动命令
        when(deploymentRepository.findById(1L)).thenReturn(Optional.of(d));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("static-site", "STATIC_WEB")));
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version()));
        when(serverRepository.findById(2L)).thenReturn(Optional.of(server("web-01", "10.0.0.8")));
        when(userRepository.findById(8L)).thenReturn(Optional.of(user(8L, "ops")));
        when(stepRepository.findByDeploymentIdOrderBySeq(1L)).thenReturn(steps());
        when(documentRepository.findByDeploymentId(1L)).thenReturn(Optional.empty());

        service.generate(1L);

        verify(documentRepository).save(docCaptor.capture());
        DeploymentDocumentEntity saved = docCaptor.getValue();
        assertThat(saved.getMaintainMarkdown()).contains("静态");
        assertThat(saved.getDeployMarkdown()).contains("STATIC_WEB");
    }

    @Test
    @DisplayName("generate：startCmd 含密钥 + 有旧版本 → 回滚块 unit 内容已脱敏（与手动启动块一致）")
    void generate_rollbackBlockRedactsStartCmd() {
        DeploymentEntity d = deployment(1L, 10L, 2L, 8L, 5L); // oldVersionId=5 → 走回滚块
        d.setStartCmd("java -jar app.jar --password=hunter2");
        when(deploymentRepository.findById(1L)).thenReturn(Optional.of(d));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("demo", "JAVA_MAVEN")));
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version()));
        when(serverRepository.findById(2L)).thenReturn(Optional.of(server("web-01", "10.0.0.8")));
        when(userRepository.findById(8L)).thenReturn(Optional.of(user(8L, "ops")));
        when(stepRepository.findByDeploymentIdOrderBySeq(1L)).thenReturn(steps());
        when(documentRepository.findByDeploymentId(1L)).thenReturn(Optional.empty());

        service.generate(1L);

        verify(documentRepository).save(docCaptor.capture());
        DeploymentDocumentEntity saved = docCaptor.getValue();
        // 回滚块把 startCmd 写入 unit 的 ExecStart，必须脱敏，明文密钥不能出现
        assertThat(saved.getDeployMarkdown())
                .doesNotContain("hunter2")
                .contains("--password=***");
        // 维护文档的手动启动块同样脱敏
        assertThat(saved.getMaintainMarkdown())
                .doesNotContain("hunter2")
                .contains("--password=***");
    }

    @Test
    @DisplayName("缺陷9：文档回滚块 unit 的 startCmd 取旧版本 SUCCESS 部署值（不用失败部署的新端口）")
    void generate_rollbackBlockUsesOldVersionStartCmd() {
        DeploymentEntity d = deployment(1L, 10L, 2L, 8L, 5L); // 失败部署带新端口
        d.setStartCmd("PORT=8091 /usr/bin/python3 extract/app.py");
        when(deploymentRepository.findById(1L)).thenReturn(Optional.of(d));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("demo", "PYTHON")));
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version()));
        when(serverRepository.findById(2L)).thenReturn(Optional.of(server("web-01", "10.0.0.8")));
        when(userRepository.findById(8L)).thenReturn(Optional.of(user(8L, "ops")));
        when(stepRepository.findByDeploymentIdOrderBySeq(1L)).thenReturn(steps());
        when(documentRepository.findByDeploymentId(1L)).thenReturn(Optional.empty());
        // 旧版本 v5 最近一次 SUCCESS 部署（旧端口 8001）
        DeploymentEntity oldSuccess = deployment(2L, 5L, 2L, 8L, null);
        oldSuccess.setStartCmd("PORT=8001 /usr/bin/python3 extract/app.py");
        when(deploymentRepository.findFirstByProjectIdAndVersionIdAndServerIdAndStatusOrderByIdDesc(
                1L, 5L, 2L, "SUCCESS"))
                .thenReturn(Optional.of(oldSuccess));

        service.generate(1L);

        verify(documentRepository).save(docCaptor.capture());
        DeploymentDocumentEntity saved = docCaptor.getValue();
        String deployMd = saved.getDeployMarkdown();
        // 回滚块的 unit 内容必须用旧版本 startCmd（PORT=8001），失败部署的 PORT=8091 不得出现
        assertThat(deployMd)
                .contains("Environment=PORT=8001")
                .doesNotContain("PORT=8091");
    }

    @Test
    @DisplayName("缺陷9 降级：旧版本无 SUCCESS 部署 → 文档回滚块回退当前部署 startCmd")
    void generate_rollbackBlockFallbackToCurrentStartCmd() {
        DeploymentEntity d = deployment(1L, 10L, 2L, 8L, 5L);
        d.setStartCmd("java -jar app.jar");
        when(deploymentRepository.findById(1L)).thenReturn(Optional.of(d));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("demo", "JAVA_MAVEN")));
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version()));
        when(serverRepository.findById(2L)).thenReturn(Optional.of(server("web-01", "10.0.0.8")));
        when(userRepository.findById(8L)).thenReturn(Optional.of(user(8L, "ops")));
        when(stepRepository.findByDeploymentIdOrderBySeq(1L)).thenReturn(steps());
        when(documentRepository.findByDeploymentId(1L)).thenReturn(Optional.empty());

        service.generate(1L);

        verify(documentRepository).save(docCaptor.capture());
        DeploymentDocumentEntity saved = docCaptor.getValue();
        // 查无旧版本 SUCCESS 部署（mock 默认 Optional.empty）→ 回退当前 startCmd
        assertThat(saved.getDeployMarkdown())
                .contains("WorkingDirectory=/opt/deploy/demo/5")
                .contains("ExecStart=java -jar app.jar");
    }

    // ---------- 按部署方式（DOCKER/NGINX）差异化 ----------

    @Test
    @DisplayName("generate：DOCKER 模式 → compose 命令/产物/回滚，无 systemd unit")
    void generate_dockerMode_usesCompose() {
        DeploymentEntity d = deployment(1L, 10L, 2L, 8L, 5L);
        d.setMode("DOCKER");
        when(deploymentRepository.findById(1L)).thenReturn(Optional.of(d));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("demo", "JAVA_MAVEN")));
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version()));
        when(serverRepository.findById(2L)).thenReturn(Optional.of(server("web-01", "10.0.0.8")));
        when(userRepository.findById(8L)).thenReturn(Optional.of(user(8L, "ops")));
        when(stepRepository.findByDeploymentIdOrderBySeq(1L)).thenReturn(steps());
        when(documentRepository.findByDeploymentId(1L)).thenReturn(Optional.empty());

        service.generate(1L);

        verify(documentRepository).save(docCaptor.capture());
        DeploymentDocumentEntity saved = docCaptor.getValue();
        String deploy = saved.getDeployMarkdown();
        String maintain = saved.getMaintainMarkdown();
        // 维护文档用 compose 命令，不再出现 systemctl / unit
        assertThat(maintain)
                .contains("docker-compose -f /opt/deploy/demo/10/docker-compose.yml up -d")
                .contains("docker-compose -f /opt/deploy/demo/10/docker-compose.yml down")
                .contains("docker-compose -f /opt/deploy/demo/10/docker-compose.yml logs -f --no-color")
                .doesNotContain("systemctl start")
                .doesNotContain("journalctl -u");
        // 部署方式标签 + 产物 + 回滚（旧版本：当前 down + 旧版本 down&&up）
        assertThat(deploy)
                .contains("| 部署方式 | docker-compose 容器 |")
                .contains("/opt/deploy/demo/10/docker-compose.yml")
                .contains("docker-compose -f /opt/deploy/demo/5/docker-compose.yml down && docker-compose -f /opt/deploy/demo/5/docker-compose.yml up -d")
                .doesNotContain("systemctl");
    }

    @Test
    @DisplayName("generate：NGINX 模式 → 站点 conf/软链 + reload，stop 不支持")
    void generate_nginxMode_usesSiteConf() {
        DeploymentEntity d = deployment(1L, 10L, 2L, 8L, 5L);
        d.setMode("NGINX");
        when(deploymentRepository.findById(1L)).thenReturn(Optional.of(d));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("demo", "STATIC_WEB")));
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version()));
        when(serverRepository.findById(2L)).thenReturn(Optional.of(server("web-01", "10.0.0.8")));
        when(userRepository.findById(8L)).thenReturn(Optional.of(user(8L, "ops")));
        when(stepRepository.findByDeploymentIdOrderBySeq(1L)).thenReturn(steps());
        when(documentRepository.findByDeploymentId(1L)).thenReturn(Optional.empty());

        service.generate(1L);

        verify(documentRepository).save(docCaptor.capture());
        DeploymentDocumentEntity saved = docCaptor.getValue();
        String deploy = saved.getDeployMarkdown();
        String maintain = saved.getMaintainMarkdown();
        // 维护文档：站点 conf/软链 + reload；stop 标注共享守护进程不支持
        assertThat(maintain)
                .contains("/etc/nginx/conf.d/deploy-demo.conf")
                .contains("/opt/deploy/demo/current")
                .contains("nginx -t && nginx -s reload")
                .contains("不支持（nginx 为共享守护进程，停止将影响全部站点）")
                .doesNotContain("systemctl start")
                .doesNotContain("journalctl -u");
        // 回滚：软链切回旧版本 extract + reload
        assertThat(deploy)
                .contains("| 部署方式 | nginx 静态站点 |")
                .contains("ln -sfn /opt/deploy/demo/5/extract /opt/deploy/demo/current && nginx -s reload");
    }

    @Test
    @DisplayName("generate：有 AI 方案（deploymentId 回写）→ AI 决策记录渲染来源/推理/关键决策")
    void generate_withAiPlan_rendersDecision() {
        DeploymentEntity d = deployment(1L, 10L, 2L, 8L, null);
        when(deploymentRepository.findById(1L)).thenReturn(Optional.of(d));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("demo", "JAVA_MAVEN")));
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version()));
        when(serverRepository.findById(2L)).thenReturn(Optional.of(server("web-01", "10.0.0.8")));
        when(userRepository.findById(8L)).thenReturn(Optional.of(user(8L, "ops")));
        when(stepRepository.findByDeploymentIdOrderBySeq(1L)).thenReturn(steps());
        when(documentRepository.findByDeploymentId(1L)).thenReturn(Optional.empty());
        AiDeploymentPlanEntity plan = new AiDeploymentPlanEntity();
        plan.setId(7L);
        plan.setSource("AI");
        plan.setReasoning("检测到 pom.xml 与 jar 启动脚本，判定为 Java Maven 项目");
        plan.setDetectedType("JAVA_MAVEN");
        plan.setMode("PROCESS");
        plan.setStartCmd("java -jar app.jar --password=hunter2");
        plan.setPort(8080);
        plan.setHealthPath("/health");
        when(planRepository.findByDeploymentId(1L)).thenReturn(List.of(plan));

        service.generate(1L);

        verify(documentRepository).save(docCaptor.capture());
        DeploymentDocumentEntity saved = docCaptor.getValue();
        String deploy = saved.getDeployMarkdown();
        assertThat(deploy)
                .contains("AI（LLM 生成）")
                .contains("检测到 pom.xml 与 jar 启动脚本，判定为 Java Maven 项目")
                .contains("JAVA_MAVEN")
                .contains("--password=***")
                .doesNotContain("hunter2")
                .doesNotContain("（M2 AI 阶段补充）");
    }

    // ---------- get ----------

    @Test
    @DisplayName("get：type=deploy/maintain 返回对应内容")
    void get_returnsContentByType() {
        DeploymentDocumentEntity doc = new DeploymentDocumentEntity();
        doc.setDeploymentId(1L);
        doc.setDeployMarkdown("DEPLOY-CONTENT");
        doc.setMaintainMarkdown("MAINTAIN-CONTENT");
        doc.setUpdatedAt(LocalDateTime.now());
        when(documentRepository.findByDeploymentId(1L)).thenReturn(Optional.of(doc));

        assertThat(service.get(1L, "deploy")).isEqualTo("DEPLOY-CONTENT");
        assertThat(service.get(1L, "maintain")).isEqualTo("MAINTAIN-CONTENT");
    }

    @Test
    @DisplayName("get：非法 type → BAD_REQUEST")
    void get_invalidType_throws400() {
        assertThatThrownBy(() -> service.get(1L, "bogus"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    @DisplayName("get：文档未生成 → NOT_FOUND（提示重新生成）")
    void get_docNotGenerated_throws404() {
        when(documentRepository.findByDeploymentId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(1L, "deploy"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException be = (BizException) e;
                    assertThat(be.getCode()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(be.getMessage()).contains("重新生成");
                });
    }

    // ---------- 辅助 ----------

    private static DeploymentEntity deployment(Long id, long versionId, long serverId,
                                               long operatorId, Long oldVersionId) {
        DeploymentEntity d = new DeploymentEntity();
        d.setId(id);
        d.setProjectId(1L);
        d.setVersionId(versionId);
        d.setServerId(serverId);
        d.setStatus(DeploymentStatus.SUCCESS.name());
        d.setMode("PROCESS");
        d.setStartCmd("java -jar app.jar");
        d.setPort(8080);
        d.setHealthPath("/health");
        d.setWorkdir("/opt/deploy/demo/10");
        d.setOldVersionId(oldVersionId);
        d.setOperator(operatorId);
        d.setCreatedAt(LocalDateTime.of(2026, 10, 14, 9, 59, 0));
        d.setStartedAt(LocalDateTime.of(2026, 10, 14, 10, 0, 0));
        d.setFinishedAt(LocalDateTime.of(2026, 10, 14, 10, 2, 0));
        return d;
    }

    private static ProjectEntity project(String name, String detectedType) {
        ProjectEntity p = new ProjectEntity();
        p.setId(1L);
        p.setName(name);
        p.setDetectedType(detectedType);
        return p;
    }

    private static PackageVersionEntity version() {
        PackageVersionEntity v = new PackageVersionEntity();
        v.setId(10L);
        v.setProjectId(1L);
        v.setFileName("app.zip");
        v.setSha256("3a7bd3e2360a3d29eea436fcfb7e44c735d117c42d1c1835420b6b9942dd4f1b");
        v.setSizeBytes(12345L);
        return v;
    }

    private static ServerEntity server(String name, String ip) {
        ServerEntity s = new ServerEntity();
        s.setId(2L);
        s.setName(name);
        s.setIp(ip);
        s.setSshPort(22);
        s.setSshUser("deploy");
        return s;
    }

    private static UserEntity user(long id, String username) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setUsername(username);
        return u;
    }

    /** 四步成功的步骤列表（detail 为 JSONB 文本，模拟真实执行器产出）。 */
    private static List<DeploymentStepEntity> steps() {
        LocalDateTime t = LocalDateTime.of(2026, 10, 14, 10, 0, 0);
        return List.of(
                step(1L, 1, "UPLOAD",
                        "{\"sha256\":\"3a7bd3e2360a3d29eea436fcfb7e44c735d117c42d1c1835420b6b9942dd4f1b\",\"sizeBytes\":12345,\"durationMs\":1500}", t.plusSeconds(0), t.plusSeconds(5)),
                step(1L, 2, "INSTALL",
                        "{\"command\":\"unzip -o -q /opt/deploy/demo/10/app.zip -d /opt/deploy/demo/10/extract\",\"durationMs\":800}", t.plusSeconds(5), t.plusSeconds(8)),
                step(1L, 3, "START",
                        "{\"unitName\":\"deploy-demo.service\",\"workdir\":\"/opt/deploy/demo/10\"}", t.plusSeconds(8), t.plusSeconds(12)),
                step(1L, 4, "VERIFY",
                        "{\"url\":\"http://127.0.0.1:8080/health\",\"attempts\":1,\"httpCode\":\"200\"}", t.plusSeconds(12), t.plusSeconds(120))
        );
    }

    private static DeploymentStepEntity step(Long deploymentId, int seq, String name,
                                             String detail, LocalDateTime start, LocalDateTime end) {
        DeploymentStepEntity s = new DeploymentStepEntity();
        s.setDeploymentId(deploymentId);
        s.setSeq(seq);
        s.setName(name);
        s.setStatus(StepStatus.SUCCESS.name());
        s.setDetail(detail);
        s.setStartedAt(start);
        s.setFinishedAt(end);
        return s;
    }
}
