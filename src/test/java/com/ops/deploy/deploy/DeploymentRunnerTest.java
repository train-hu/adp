package com.ops.deploy.deploy;

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
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 部署执行引擎 {@link DeploymentRunner} 的 5 场景单测（mock 仓库 + 四个步骤执行器 + SshService）。
 *
 * <p>直接 {@code new DeploymentRunner(...)} 并以同步方式调用 {@code run}（无 @Async 代理）。
 * 覆盖：
 * <ol>
 *   <li>四步全成功 → SUCCESS + onSuccess；</li>
 *   <li>步骤失败 + 有旧版本 + 回滚成功 → ROLLED_BACK + ROLLBACK 步（unit 改写指向旧 workdir）+ onRolledBack；</li>
 *   <li>步骤失败 + 有旧版本 + 回滚失败 → NEEDS_ATTENTION，error_summary 含原始 + 回滚错误；</li>
 *   <li>步骤失败 + 无旧版本 → FAILED（绕过状态机直接落库）；</li>
 *   <li>未预期异常 → 非终态置 NEEDS_ATTENTION。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class DeploymentRunnerTest {

    @Mock
    private DeploymentRepository deploymentRepository;
    @Mock
    private DeploymentStepRepository stepRepository;
    @Mock
    private ServiceInstanceService instanceService;
    @Mock
    private UploadStepExecutor uploadExecutor;
    @Mock
    private InstallStepExecutor installExecutor;
    @Mock
    private StartStepExecutor startExecutor;
    @Mock
    private NginxStartExecutor nginxExecutor;
    @Mock
    private DockerStartExecutor dockerExecutor;
    @Mock
    private VerifyStepExecutor verifyExecutor;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private PackageVersionRepository versionRepository;
    @Mock
    private ProjectConfigFileRepository configFileRepository;
    @Mock
    private ProcessConfigOverwriteExecutor configOverwriteExecutor;
    @Mock
    private SshService sshService;
    @Mock
    private DeploymentDocumentService documentService;
    @Mock
    private DatabaseProvisioner databaseProvisioner;
    // 新增：子服务解析 mock（resolvePathKey → pathKey；测试 deployment 的 serviceId 均为 null → 默认服务）
    @Mock
    private com.ops.deploy.project.ProjectSubServiceManager subServiceManager;
    // webhook 告警 + 站内通知（失败终态落库点新增）
    @Mock
    private WebhookNotifier webhookNotifier;
    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private DeploymentMetrics deploymentMetrics;

    @Captor
    private ArgumentCaptor<DeploymentStepEntity> stepCaptor;
    @Captor
    private ArgumentCaptor<String> cmdCaptor;
    @Captor
    private ArgumentCaptor<String> webhookTitleCaptor;
    @Captor
    private ArgumentCaptor<String> webhookMsgCaptor;

    private DeploymentRunner runner;

    @BeforeEach
    void setUp() {
        runner = new DeploymentRunner(deploymentRepository, stepRepository, instanceService,
                uploadExecutor, installExecutor, startExecutor, nginxExecutor, dockerExecutor,
                verifyExecutor, projectRepository, versionRepository, configFileRepository,
                configOverwriteExecutor, sshService, documentService, databaseProvisioner,
                subServiceManager, webhookNotifier, notificationRepository, deploymentMetrics);
        // 模拟 Spring Data 的 save 回写（真实实现返回入参实体；Mockito 默认返回 null）。
        // 两个 save 桩都用 lenient：兜底自保护场景会用 doThrow 覆盖 deploymentRepository.save 桩。
        lenient().when(stepRepository.save(any(DeploymentStepEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(deploymentRepository.save(any(DeploymentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // PROCESS 模式启动前会拉取该子服务全部配置做包内文件覆盖（默认空，避免 NPE）
        lenient().when(configFileRepository.findByServiceId(0L)).thenReturn(List.of());
        lenient().when(configOverwriteExecutor.execute(anyLong(), anyString(), any(List.class)))
                .thenReturn(Map.of("written", List.of(), "count", 0));
        // 子服务解析：测试 deployment/version 走默认子服务（pathKey=null，旧口径路径/单元名）
        lenient().when(subServiceManager.resolveEntity(any()))
                .thenAnswer(inv -> {
                    com.ops.deploy.project.ProjectSubServiceEntity e =
                            new com.ops.deploy.project.ProjectSubServiceEntity();
                    e.setId(1L);
                    e.setProjectId(1L);
                    e.setName("default");
                    e.setPathKey(null);
                    return e;
                });
    }

    // ---------- 场景 1：四步全成功 → SUCCESS ----------

    @Test
    @DisplayName("成功：四步全过 → SUCCESS + 4 步 SUCCESS + onSuccess")
    void success_allStepsPass() {
        DeploymentEntity d = deployment(1L, "demo", 1L, 10L, null);
        when(deploymentRepository.findById(1L)).thenReturn(java.util.Optional.of(d));
        when(projectRepository.findById(1L)).thenReturn(java.util.Optional.of(project("demo")));
        when(versionRepository.findById(10L)).thenReturn(java.util.Optional.of(version("app.zip")));
        when(uploadExecutor.execute(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(Map.of("sha256", "a"));
        when(installExecutor.execute(anyLong(), anyString(), anyString())).thenReturn(Map.of("ok", 1));
        when(startExecutor.execute(anyLong(), anyString(), anyString(), anyString(), anyString())).thenReturn(Map.of("unit", 1));
        when(verifyExecutor.execute(anyLong(), anyInt(), anyString(), anyString())).thenReturn(Map.of("attempts", 1));

        runner.run(1L);

        assertThat(d.getStatus()).isEqualTo(DeploymentStatus.SUCCESS.name());
        assertThat(d.getStartedAt()).isNotNull();
        assertThat(d.getFinishedAt()).isNotNull();

        Map<Integer, DeploymentStepEntity> bySeq = savedSteps();
        assertThat(bySeq.keySet()).containsExactly(1, 2, 3, 4);
        assertThat(bySeq.get(1).getName()).isEqualTo("UPLOAD");
        assertThat(bySeq.get(2).getName()).isEqualTo("INSTALL");
        assertThat(bySeq.get(3).getName()).isEqualTo("START");
        assertThat(bySeq.get(4).getName()).isEqualTo("VERIFY");
        bySeq.values().forEach(s -> assertThat(s.getStatus()).isEqualTo(StepStatus.SUCCESS.name()));

        verify(instanceService).onSuccess(d);
        verify(instanceService, never()).onRolledBack(d);
        // 部署成功后自动生成文档（Task 8）
        verify(documentService).generate(1L);
    }

    // ---------- 场景 1b：文档生成失败不影响部署成功主流程 ----------

    @Test
    @DisplayName("文档生成隔离：SUCCESS 分支 documentService.generate 抛异常 → 部署仍 SUCCESS")
    void success_documentGenerateFails_deploymentStillSuccess() {
        DeploymentEntity d = deployment(1L, "demo", 1L, 10L, null);
        when(deploymentRepository.findById(1L)).thenReturn(java.util.Optional.of(d));
        when(projectRepository.findById(1L)).thenReturn(java.util.Optional.of(project("demo")));
        when(versionRepository.findById(10L)).thenReturn(java.util.Optional.of(version("app.zip")));
        when(uploadExecutor.execute(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(Map.of("sha256", "a"));
        when(installExecutor.execute(anyLong(), anyString(), anyString())).thenReturn(Map.of("ok", 1));
        when(startExecutor.execute(anyLong(), anyString(), anyString(), anyString(), anyString())).thenReturn(Map.of("unit", 1));
        when(verifyExecutor.execute(anyLong(), anyInt(), anyString(), anyString())).thenReturn(Map.of("attempts", 1));
        // 文档生成抛异常（如 DB 抖动）：必须被 catch 隔离，不影响部署成功
        org.mockito.Mockito.doThrow(new RuntimeException("doc gen boom"))
                .when(documentService).generate(1L);

        runner.run(1L);

        assertThat(d.getStatus()).isEqualTo(DeploymentStatus.SUCCESS.name());
        assertThat(d.getErrorSummary()).isNull();
        verify(instanceService).onSuccess(d);
        verify(documentService).generate(1L);
        // 成功不告警：不推 webhook、不落站内通知
        verify(webhookNotifier, never()).notify(anyString(), anyString(), anyString());
        verify(notificationRepository, never()).save(any(NotificationEntity.class));
    }

    // ---------- 场景 2：UPLOAD 失败 + 有旧版本 + 回滚成功 → ROLLED_BACK ----------

    @Test
    @DisplayName("失败可回滚：UPLOAD 失败 + 有旧版本 + 回滚成功 → ROLLED_BACK + ROLLBACK 步指向旧 workdir")
    void uploadFail_rollbackSuccess() {
        DeploymentEntity d = deployment(1L, "demo", 1L, 10L, 9L); // oldVersionId=9
        when(deploymentRepository.findById(1L)).thenReturn(java.util.Optional.of(d));
        when(projectRepository.findById(1L)).thenReturn(java.util.Optional.of(project("demo")));
        when(versionRepository.findById(10L)).thenReturn(java.util.Optional.of(version("app.zip")));
        // 旧版本 v9 是 zip 包 → 回滚 unit 运行目录须为 /opt/deploy/demo/9/extract
        when(versionRepository.findById(9L)).thenReturn(java.util.Optional.of(version("old.zip")));
        when(uploadExecutor.execute(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenThrow(new BizException(ErrorCode.SSH_ERROR, "upload boom"));
        // 回滚：写 unit（ok）+ daemon-reload/enable（ok）
        when(sshService.execute(anyLong(), anyString())).thenReturn(CommandResult.ok("", 0));

        runner.run(1L);

        assertThat(d.getStatus()).isEqualTo(DeploymentStatus.ROLLED_BACK.name());
        assertThat(d.getErrorSummary()).isNull();

        Map<Integer, DeploymentStepEntity> bySeq = savedSteps();
        assertThat(bySeq.keySet()).containsExactly(1, 5);
        assertThat(bySeq.get(1).getStatus()).isEqualTo(StepStatus.FAILED.name());
        assertThat(bySeq.get(5).getName()).isEqualTo("ROLLBACK");
        assertThat(bySeq.get(5).getStatus()).isEqualTo(StepStatus.SUCCESS.name());

        // 回滚 unit 内容应指向旧版本运行目录（zip 包 → extract）：/opt/deploy/demo/9/extract
        verify(sshService, atLeastOnce()).execute(anyLong(), cmdCaptor.capture());
        assertThat(cmdCaptor.getAllValues())
                .anySatisfy(cmd -> assertThat(cmd).contains("WorkingDirectory=/opt/deploy/demo/9/extract"));

        verify(instanceService).onRolledBack(d);
        verify(instanceService, never()).onSuccess(d);

        // webhook：ROLLED_BACK 终态推一条 WARN（标题=部署回滚，消息含项目名+部署id+原始错误）
        verify(webhookNotifier).notify(eq("WARN"), eq("部署回滚"), webhookMsgCaptor.capture());
        assertThat(webhookMsgCaptor.getValue()).contains("demo");
        assertThat(webhookMsgCaptor.getValue()).contains("upload boom");
        // 同步落一条站内通知（WARN，同 title）
        ArgumentCaptor<NotificationEntity> notif = ArgumentCaptor.forClass(NotificationEntity.class);
        verify(notificationRepository).save(notif.capture());
        assertThat(notif.getValue().getLevel()).isEqualTo("WARN");
        assertThat(notif.getValue().getTitle()).isEqualTo("部署回滚");
    }

    // ---------- 场景 2b：回滚 unit 用旧版本 SUCCESS 部署的 startCmd（缺陷9回归） ----------

    @Test
    @DisplayName("缺陷9：回滚 unit 的 startCmd 取旧版本 SUCCESS 部署值（新端口 env 不得带入回滚 unit）")
    void rollback_usesOldVersionStartCmd() {
        DeploymentEntity d = deployment(1L, "demo", 1L, 10L, 9L); // v10 失败，oldVersionId=9
        d.setStartCmd("PORT=8091 /usr/bin/python3 extract/app.py"); // 失败部署带新端口
        when(deploymentRepository.findById(1L)).thenReturn(java.util.Optional.of(d));
        when(projectRepository.findById(1L)).thenReturn(java.util.Optional.of(project("demo")));
        when(versionRepository.findById(10L)).thenReturn(java.util.Optional.of(version("app.zip")));
        // 旧版本 v9 是 zip 包 → 回滚 unit 运行目录须为 /opt/deploy/demo/9/extract
        when(versionRepository.findById(9L)).thenReturn(java.util.Optional.of(version("old.zip")));
        when(uploadExecutor.execute(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenThrow(new BizException(ErrorCode.SSH_ERROR, "upload boom"));
        // 旧版本 v9 最近一次 SUCCESS 部署的 startCmd（旧端口 8001）
        DeploymentEntity oldSuccess = deployment(2L, "demo", 1L, 9L, null);
        oldSuccess.setId(2L);
        oldSuccess.setVersionId(9L);
        oldSuccess.setStatus(DeploymentStatus.SUCCESS.name());
        oldSuccess.setStartCmd("PORT=8001 /usr/bin/python3 extract/app.py");
        when(deploymentRepository.findFirstByProjectIdAndVersionIdAndServerIdAndStatusOrderByIdDesc(
                1L, 9L, 1L, DeploymentStatus.SUCCESS.name()))
                .thenReturn(java.util.Optional.of(oldSuccess));
        // 回滚：写 unit（ok）+ daemon-reload/enable（ok）
        when(sshService.execute(anyLong(), anyString())).thenReturn(CommandResult.ok("", 0));

        runner.run(1L);

        assertThat(d.getStatus()).isEqualTo(DeploymentStatus.ROLLED_BACK.name());

        // 回滚 unit 必须指向旧 workdir 且用旧版本 startCmd（PORT=8001），绝不能带入失败的 PORT=8091
        verify(sshService, atLeastOnce()).execute(anyLong(), cmdCaptor.capture());
        List<String> unitWrites = cmdCaptor.getAllValues().stream()
                .filter(cmd -> cmd.contains("WorkingDirectory=/opt/deploy/demo/9/extract"))
                .toList();
        assertThat(unitWrites).isNotEmpty();
        for (String cmd : unitWrites) {
            assertThat(cmd).contains("Environment=PORT=8001");
            assertThat(cmd).doesNotContain("PORT=8091");
        }
        verify(instanceService).onRolledBack(d);
    }

    @Test
    @DisplayName("缺陷9 降级：查不到旧版本 SUCCESS 部署 → 回退当前部署 startCmd（不 NPE）")
    void rollback_fallbackToCurrentStartCmdWhenNoOldSuccess() {
        DeploymentEntity d = deployment(1L, "demo", 1L, 10L, 9L);
        d.setStartCmd("java -jar app.jar");
        when(deploymentRepository.findById(1L)).thenReturn(java.util.Optional.of(d));
        when(projectRepository.findById(1L)).thenReturn(java.util.Optional.of(project("demo")));
        when(versionRepository.findById(10L)).thenReturn(java.util.Optional.of(version("app.zip")));
        // 旧版本 v9 是 jar 包（mv 到 workdir 根）→ 回滚 unit 运行目录为 workdir 根，不带 extract
        when(versionRepository.findById(9L)).thenReturn(java.util.Optional.of(version("old.jar")));
        when(uploadExecutor.execute(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenThrow(new BizException(ErrorCode.SSH_ERROR, "upload boom"));
        // 旧版本无 SUCCESS 部署记录 → 回退当前 startCmd
        when(deploymentRepository.findFirstByProjectIdAndVersionIdAndServerIdAndStatusOrderByIdDesc(
                1L, 9L, 1L, DeploymentStatus.SUCCESS.name()))
                .thenReturn(java.util.Optional.empty());
        when(sshService.execute(anyLong(), anyString())).thenReturn(CommandResult.ok("", 0));

        runner.run(1L);

        assertThat(d.getStatus()).isEqualTo(DeploymentStatus.ROLLED_BACK.name());
        verify(sshService, atLeastOnce()).execute(anyLong(), cmdCaptor.capture());
        assertThat(cmdCaptor.getAllValues())
                .anySatisfy(cmd -> assertThat(cmd)
                        .contains("WorkingDirectory=/opt/deploy/demo/9")
                        .doesNotContain("/demo/9/extract")
                        .contains("ExecStart=java -jar app.jar"));
    }

    // ---------- 场景 3：UPLOAD 失败 + 有旧版本 + 回滚失败 → NEEDS_ATTENTION ----------

    @Test
    @DisplayName("失败回滚又失败：UPLOAD 失败 + 有旧版本 + 回滚失败 → NEEDS_ATTENTION（error 含原始+回滚错误）")
    void uploadFail_rollbackFail() {
        DeploymentEntity d = deployment(1L, "demo", 1L, 10L, 9L);
        when(deploymentRepository.findById(1L)).thenReturn(java.util.Optional.of(d));
        when(projectRepository.findById(1L)).thenReturn(java.util.Optional.of(project("demo")));
        when(versionRepository.findById(10L)).thenReturn(java.util.Optional.of(version("app.zip")));
        when(uploadExecutor.execute(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenThrow(new BizException(ErrorCode.SSH_ERROR, "upload boom"));
        // 回滚：写 unit 成功，重启失败
        when(sshService.execute(anyLong(), anyString()))
                .thenReturn(CommandResult.ok("", 0))
                .thenReturn(CommandResult.fail(1, "", "restart boom"));

        runner.run(1L);

        assertThat(d.getStatus()).isEqualTo(DeploymentStatus.NEEDS_ATTENTION.name());
        assertThat(d.getErrorSummary())
                .contains("upload boom")
                .contains("rollback failed")
                .contains("restart boom");

        Map<Integer, DeploymentStepEntity> bySeq = savedSteps();
        assertThat(bySeq.get(5).getName()).isEqualTo("ROLLBACK");
        assertThat(bySeq.get(5).getStatus()).isEqualTo(StepStatus.FAILED.name());

        verify(instanceService, never()).onSuccess(d);
        verify(instanceService, never()).onRolledBack(d);
        // webhook：NEEDS_ATTENTION 终态推一条 WARN（标题=部署需处理，含原始+回滚错误）
        verify(webhookNotifier).notify(eq("WARN"), eq("部署需处理"), webhookMsgCaptor.capture());
        assertThat(webhookMsgCaptor.getValue()).contains("upload boom");
        assertThat(webhookMsgCaptor.getValue()).contains("rollback failed");
        assertThat(webhookMsgCaptor.getValue()).contains("restart boom");
    }

    // ---------- 场景 4：INSTALL 失败 + 无旧版本 → FAILED ----------

    @Test
    @DisplayName("失败无回滚：INSTALL 失败 + 无旧版本 → FAILED + error_summary，无 ROLLBACK 步")
    void installFail_noOldVersion() {
        DeploymentEntity d = deployment(1L, "demo", 1L, 10L, null); // oldVersionId=null
        when(deploymentRepository.findById(1L)).thenReturn(java.util.Optional.of(d));
        when(projectRepository.findById(1L)).thenReturn(java.util.Optional.of(project("demo")));
        when(versionRepository.findById(10L)).thenReturn(java.util.Optional.of(version("app.zip")));
        when(uploadExecutor.execute(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(Map.of("ok", 1));
        when(installExecutor.execute(anyLong(), anyString(), anyString()))
                .thenThrow(new BizException(ErrorCode.SSH_ERROR, "install boom"));

        runner.run(1L);

        assertThat(d.getStatus()).isEqualTo(DeploymentStatus.FAILED.name());
        assertThat(d.getErrorSummary()).contains("install boom");

        Map<Integer, DeploymentStepEntity> bySeq = savedSteps();
        assertThat(bySeq.keySet()).containsExactly(1, 2);
        assertThat(bySeq.get(1).getStatus()).isEqualTo(StepStatus.SUCCESS.name());
        assertThat(bySeq.get(2).getStatus()).isEqualTo(StepStatus.FAILED.name());
        assertThat(bySeq).doesNotContainKey(5);

        verify(instanceService, never()).onSuccess(d);
        verify(instanceService, never()).onRolledBack(d);
        // webhook：FAILED 终态推一条 WARN（标题=部署失败，消息含项目名+部署id+错误摘要）
        verify(webhookNotifier).notify(eq("WARN"), eq("部署失败"), webhookMsgCaptor.capture());
        assertThat(webhookMsgCaptor.getValue()).contains("demo");
        assertThat(webhookMsgCaptor.getValue()).contains("install boom");
    }

    // ---------- 场景 5：未预期异常 → 非终态置 NEEDS_ATTENTION ----------

    @Test
    @DisplayName("兜底：未预期异常（project 查库抛错）→ 非终态置 NEEDS_ATTENTION")
    void unexpectedException_marksNeedsAttention() {
        DeploymentEntity d = deployment(1L, "demo", 1L, 10L, 9L);
        d.setStartedAt(java.time.LocalDateTime.now());
        when(deploymentRepository.findById(1L)).thenReturn(java.util.Optional.of(d));
        when(projectRepository.findById(1L)).thenThrow(new RuntimeException("db down"));

        runner.run(1L);

        assertThat(d.getStatus()).isEqualTo(DeploymentStatus.NEEDS_ATTENTION.name());
        assertThat(d.getErrorSummary()).contains("db down");
        verify(instanceService, never()).onSuccess(d);
        verify(instanceService, never()).onRolledBack(d);
    }

    // ---------- 场景 6：兜底自保护——兜底 save 也抛异常时 run 不抛 ----------

    @Test
    @DisplayName("兜底自保护：未预期异常且兜底 save 也抛 → run 本身不抛异常（静默吞掉）")
    void unexpectedException_fallbackSaveAlsoFails_runDoesNotThrow() {
        DeploymentEntity d = deployment(1L, "demo", 1L, 10L, null);
        d.setStartedAt(java.time.LocalDateTime.now());
        when(deploymentRepository.findById(1L)).thenReturn(java.util.Optional.of(d));
        when(projectRepository.findById(1L)).thenThrow(new RuntimeException("db down"));
        // 覆盖 setUp 的 save 桩：兜底 save 也失败（DB 挂了）
        org.mockito.Mockito.doThrow(new RuntimeException("db down 2"))
                .when(deploymentRepository).save(any(DeploymentEntity.class));

        // 直接调用 run 断言不抛异常（若兜底逻辑无自保护，异常会逸出 run）
        runner.run(1L);

        // 兜底在 save 前已把状态置为 NEEDS_ATTENTION（save 失败不影响内存态）
        assertThat(d.getStatus()).isEqualTo(DeploymentStatus.NEEDS_ATTENTION.name());
    }

    // ---------- 场景 7：NGINX 模式（START 走 nginxExecutor，port 可 null 跳过 verify，回滚切软链） ----------

    @Test
    @DisplayName("NGINX 成功：START 走 nginxExecutor（自定义 conf 下发），VERIFY 正常，SUCCESS")
    void nginxMode_success() {
        DeploymentEntity d = deployment(1L, "site", 1L, 10L, null);
        d.setMode("NGINX");
        d.setStartCmd(null);
        d.setPort(80);
        when(deploymentRepository.findById(1L)).thenReturn(java.util.Optional.of(d));
        when(projectRepository.findById(1L)).thenReturn(java.util.Optional.of(project("site")));
        when(versionRepository.findById(10L)).thenReturn(java.util.Optional.of(version("site.zip")));
        when(uploadExecutor.execute(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(Map.of("sha256", "a"));
        when(installExecutor.execute(anyLong(), anyString(), anyString())).thenReturn(Map.of("ok", 1));
        // 项目配了自定义 nginx 配置（file_key=nginx）
        ProjectConfigFileEntity nginxConf = new ProjectConfigFileEntity();
        nginxConf.setFileKey("nginx");
        nginxConf.setContent("server { listen 80; }");
        when(configFileRepository.findByServiceIdAndFileKey(0L, "nginx"))
                .thenReturn(java.util.Optional.of(nginxConf));
        when(nginxExecutor.execute(anyLong(), anyString(), any(), anyInt(), anyString(), anyString()))
                .thenReturn(Map.of("confPath", "/etc/nginx/conf.d/deploy-site.conf",
                        "confContent", "server { listen 80; }"));
        when(verifyExecutor.execute(anyLong(), anyInt(), anyString(), isNull())).thenReturn(Map.of("attempts", 1));

        runner.run(1L);

        assertThat(d.getStatus()).isEqualTo(DeploymentStatus.SUCCESS.name());
        verify(nginxExecutor).execute(1L, "site", null, 80, "/opt/deploy/site/10", "server { listen 80; }");
        verify(startExecutor, never()).execute(anyLong(), anyString(), anyString(), anyString(), anyString());
        verify(instanceService).onSuccess(d);
        // 回写幂等：自定义配置内容未变 → 不触发 save
        verify(configFileRepository, never()).save(any(ProjectConfigFileEntity.class));
    }

    @Test
    @DisplayName("NGINX 首次部署（无自定义配置）：默认站点配置回写项目配置 file_key=nginx，配置列表可见可编辑")
    void nginxMode_firstDeploy_writesDefaultConfIntoConfigFiles() {
        DeploymentEntity d = deployment(1L, "site", 1L, 10L, null);
        d.setMode("NGINX");
        d.setStartCmd(null);
        d.setPort(18010);
        when(deploymentRepository.findById(1L)).thenReturn(java.util.Optional.of(d));
        when(projectRepository.findById(1L)).thenReturn(java.util.Optional.of(project("site")));
        when(versionRepository.findById(10L)).thenReturn(java.util.Optional.of(version("site.zip")));
        when(uploadExecutor.execute(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(Map.of("sha256", "a"));
        when(installExecutor.execute(anyLong(), anyString(), anyString())).thenReturn(Map.of("ok", 1));
        // 无自定义 nginx 配置
        when(configFileRepository.findByServiceIdAndFileKey(0L, "nginx"))
                .thenReturn(java.util.Optional.empty());
        when(nginxExecutor.execute(anyLong(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(Map.of("confPath", "/etc/nginx/conf.d/deploy-site.conf",
                        "confContent", "server {\n    listen 18010;\n}"));
        runner.run(1L);

        assertThat(d.getStatus()).isEqualTo(DeploymentStatus.SUCCESS.name());
        // 回写：新建 file_key=nginx 的项目配置（content 为下发的默认站点配置）
        var captor = org.mockito.ArgumentCaptor.forClass(ProjectConfigFileEntity.class);
        verify(configFileRepository).save(captor.capture());
        assertThat(captor.getValue().getFileKey()).isEqualTo("nginx");
        assertThat(captor.getValue().getServiceId()).isEqualTo(0L);
        assertThat(captor.getValue().getContent()).isEqualTo("server {\n    listen 18010;\n}");
    }

    @Test
    @DisplayName("NGINX 无自定义配置且 port=null → 默认配置 + 跳过 verify（skipped）")
    void nginxMode_noCustomConf_nullPort_verifySkipped() {
        DeploymentEntity d = deployment(1L, "site", 1L, 10L, null);
        d.setMode("NGINX");
        d.setStartCmd(null);
        d.setPort(null);
        when(deploymentRepository.findById(1L)).thenReturn(java.util.Optional.of(d));
        when(projectRepository.findById(1L)).thenReturn(java.util.Optional.of(project("site")));
        when(versionRepository.findById(10L)).thenReturn(java.util.Optional.of(version("site.zip")));
        when(uploadExecutor.execute(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(Map.of("sha256", "a"));
        when(installExecutor.execute(anyLong(), anyString(), anyString())).thenReturn(Map.of("ok", 1));
        when(configFileRepository.findByServiceIdAndFileKey(0L, "nginx"))
                .thenReturn(java.util.Optional.empty());
        when(nginxExecutor.execute(anyLong(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(Map.of("confPath", "x"));
        // port=null 不应调用 verifyExecutor
        runner.run(1L);

        assertThat(d.getStatus()).isEqualTo(DeploymentStatus.SUCCESS.name());
        verify(nginxExecutor).execute(eq(1L), eq("site"), isNull(), isNull(), eq("/opt/deploy/site/10"), isNull());
        verify(verifyExecutor, never()).execute(anyLong(), anyInt(), anyString(), isNull());
        Map<Integer, DeploymentStepEntity> bySeq = savedSteps();
        assertThat(bySeq.get(4).getStatus()).isEqualTo(StepStatus.SUCCESS.name());
    }

    @Test
    @DisplayName("NGINX 回滚：失败+旧版本 → 软链切回旧版+reload，无 unit 改写")
    void nginxMode_rollback_symlink() {
        DeploymentEntity d = deployment(1L, "site", 1L, 10L, 9L);
        d.setMode("NGINX");
        d.setStartCmd(null);
        d.setPort(80);
        when(deploymentRepository.findById(1L)).thenReturn(java.util.Optional.of(d));
        when(projectRepository.findById(1L)).thenReturn(java.util.Optional.of(project("site")));
        when(versionRepository.findById(10L)).thenReturn(java.util.Optional.of(version("site.zip")));
        when(uploadExecutor.execute(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenThrow(new BizException(ErrorCode.SSH_ERROR, "upload boom"));
        // 自定义 nginx 配置（START 会用到，但 START 之前已失败，不应被查询——用 lenient 防 UnnecessaryStubbing）
        lenient().when(configFileRepository.findByServiceIdAndFileKey(0L, "nginx"))
                .thenReturn(java.util.Optional.empty());
        // 回滚命令：软链切回 /opt/deploy/site/9/extract + reload
        when(sshService.execute(anyLong(), anyString())).thenReturn(CommandResult.ok("", 0));

        runner.run(1L);

        assertThat(d.getStatus()).isEqualTo(DeploymentStatus.ROLLED_BACK.name());
        verify(sshService, atLeastOnce()).execute(anyLong(), cmdCaptor.capture());
        assertThat(cmdCaptor.getAllValues())
                .anySatisfy(cmd -> assertThat(cmd)
                        .contains("ln -sfn /opt/deploy/site/9/extract /opt/deploy/site/current")
                        .contains("nginx -s reload"));
        // 不应走 unit 回滚
        assertThat(cmdCaptor.getAllValues())
                .noneSatisfy(cmd -> assertThat(cmd).contains("WorkingDirectory=/opt/deploy/site/9"));
        verify(instanceService).onRolledBack(d);
    }

    // ---------- 场景 8：DOCKER 模式（START 走 dockerExecutor，回滚 compose down+up） ----------

    @Test
    @DisplayName("DOCKER 成功：START 走 dockerExecutor（compose+env 下发），SUCCESS")
    void dockerMode_success() {
        DeploymentEntity d = deployment(1L, "svc", 1L, 10L, null);
        d.setMode("DOCKER");
        d.setStartCmd(null);
        d.setPort(null);
        when(deploymentRepository.findById(1L)).thenReturn(java.util.Optional.of(d));
        when(projectRepository.findById(1L)).thenReturn(java.util.Optional.of(project("svc")));
        when(versionRepository.findById(10L)).thenReturn(java.util.Optional.of(version("svc.tar.gz")));
        when(uploadExecutor.execute(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(Map.of("sha256", "a"));
        when(installExecutor.execute(anyLong(), anyString(), anyString())).thenReturn(Map.of("ok", 1));
        ProjectConfigFileEntity compose = new ProjectConfigFileEntity();
        compose.setFileKey("compose");
        compose.setContent("services: {}");
        when(configFileRepository.findByServiceIdAndFileKey(0L, "compose"))
                .thenReturn(java.util.Optional.of(compose));
        when(configFileRepository.findByServiceIdAndFileKey(0L, "env"))
                .thenReturn(java.util.Optional.empty());
        when(configFileRepository.findByServiceIdAndFileKey(0L, "dockerfile"))
                .thenReturn(java.util.Optional.empty());
        when(dockerExecutor.execute(anyLong(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(Map.of("composePath", "/opt/deploy/svc/10/docker-compose.yml"));

        runner.run(1L);

        assertThat(d.getStatus()).isEqualTo(DeploymentStatus.SUCCESS.name());
        verify(dockerExecutor).execute(1L, "/opt/deploy/svc/10", "services: {}", null, null, null);
        verify(startExecutor, never()).execute(anyLong(), anyString(), anyString(), anyString(), anyString());
        // port=null → 跳过 verify
        verify(verifyExecutor, never()).execute(anyLong(), anyInt(), anyString(), isNull());
        verify(instanceService).onSuccess(d);
    }

    @Test
    @DisplayName("DOCKER 端口避让：resolvedPort 回写 deployment.port，VERIFY 用新端口")
    void dockerMode_resolvedPortWriteBack() {
        DeploymentEntity d = deployment(1L, "svc", 1L, 10L, null);
        d.setMode("DOCKER");
        d.setStartCmd(null);
        d.setPort(8080);
        when(deploymentRepository.findById(1L)).thenReturn(java.util.Optional.of(d));
        when(projectRepository.findById(1L)).thenReturn(java.util.Optional.of(project("svc")));
        when(versionRepository.findById(10L)).thenReturn(java.util.Optional.of(version("svc.tar.gz")));
        when(uploadExecutor.execute(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(Map.of("sha256", "a"));
        when(installExecutor.execute(anyLong(), anyString(), anyString())).thenReturn(Map.of("ok", 1));
        ProjectConfigFileEntity compose = new ProjectConfigFileEntity();
        compose.setFileKey("compose");
        compose.setContent("services: {}");
        when(configFileRepository.findByServiceIdAndFileKey(0L, "compose"))
                .thenReturn(java.util.Optional.of(compose));
        when(configFileRepository.findByServiceIdAndFileKey(0L, "env"))
                .thenReturn(java.util.Optional.empty());
        when(configFileRepository.findByServiceIdAndFileKey(0L, "dockerfile"))
                .thenReturn(java.util.Optional.empty());
        when(dockerExecutor.execute(eq(1L), anyString(), eq("services: {}"), any(), any(), eq(8080)))
                .thenReturn(Map.of("composePath", "/p", "resolvedPort", 8082));

        runner.run(1L);

        assertThat(d.getStatus()).isEqualTo(DeploymentStatus.SUCCESS.name());
        // 避让后端口回写 deployment（save 提交），VERIFY 健康检查用 8082
        assertThat(d.getPort()).isEqualTo(8082);
        verify(deploymentRepository, atLeastOnce()).save(d);
        verify(verifyExecutor).execute(eq(1L), eq(8082), anyString(), isNull());
    }

    @Test
    @DisplayName("DOCKER 端口避让：项目配置 compose 的 host 端口同步回写（8080:8080 → 8082:8080）")
    void dockerMode_avoidanceWriteBackProjectCompose() {
        DeploymentEntity d = deployment(1L, "svc", 1L, 10L, null);
        d.setMode("DOCKER");
        d.setStartCmd(null);
        d.setPort(8080);
        when(deploymentRepository.findById(1L)).thenReturn(java.util.Optional.of(d));
        when(projectRepository.findById(1L)).thenReturn(java.util.Optional.of(project("svc")));
        when(versionRepository.findById(10L)).thenReturn(java.util.Optional.of(version("svc.tar.gz")));
        when(uploadExecutor.execute(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(Map.of("sha256", "a"));
        when(installExecutor.execute(anyLong(), anyString(), anyString())).thenReturn(Map.of("ok", 1));
        String composeContent = "services:\n  svc:\n    build:\n      context: ./extract\n"
                + "    ports:\n      - \"8080:8080\"\n    restart: unless-stopped\n";
        ProjectConfigFileEntity compose = new ProjectConfigFileEntity();
        compose.setId(99L);
        compose.setProjectId(1L);
        compose.setFileKey("compose");
        compose.setContent(composeContent);
        when(configFileRepository.findByServiceIdAndFileKey(0L, "compose"))
                .thenReturn(java.util.Optional.of(compose));
        when(configFileRepository.findByServiceIdAndFileKey(0L, "env"))
                .thenReturn(java.util.Optional.empty());
        when(configFileRepository.findByServiceIdAndFileKey(0L, "dockerfile"))
                .thenReturn(java.util.Optional.empty());
        when(dockerExecutor.execute(eq(1L), anyString(), eq(composeContent), any(), any(), eq(8080)))
                .thenReturn(Map.of("composePath", "/p", "resolvedPort", 8082));

        runner.run(1L);

        assertThat(d.getStatus()).isEqualTo(DeploymentStatus.SUCCESS.name());
        assertThat(d.getPort()).isEqualTo(8082);
        // 项目配置 compose 的 host 端口被回写为 8082（容器侧 8080 不变）
        assertThat(compose.getContent()).contains("\"8082:8080\"");
        assertThat(compose.getContent()).doesNotContain("\"8080:8080\"");
        verify(configFileRepository).save(compose);
        verify(verifyExecutor).execute(eq(1L), eq(8082), anyString(), isNull());
    }

    @Test
    @DisplayName("DOCKER 配置漂移：deployment.port 已是上轮回写值 8082，compose 仍是 8080 → 仍回写项目配置")
    void dockerMode_configDrift_writeBackEvenIfDeploymentPortUnchanged() {
        DeploymentEntity d = deployment(1L, "svc", 1L, 10L, null);
        d.setMode("DOCKER");
        d.setStartCmd(null);
        d.setPort(8082);
        when(deploymentRepository.findById(1L)).thenReturn(java.util.Optional.of(d));
        when(projectRepository.findById(1L)).thenReturn(java.util.Optional.of(project("svc")));
        when(versionRepository.findById(10L)).thenReturn(java.util.Optional.of(version("svc.tar.gz")));
        when(uploadExecutor.execute(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(Map.of("sha256", "a"));
        when(installExecutor.execute(anyLong(), anyString(), anyString())).thenReturn(Map.of("ok", 1));
        String composeContent = "services:\n  svc:\n    build:\n      context: ./extract\n"
                + "    ports:\n      - \"8080:8080\"\n    restart: unless-stopped\n";
        ProjectConfigFileEntity compose = new ProjectConfigFileEntity();
        compose.setId(99L);
        compose.setProjectId(1L);
        compose.setFileKey("compose");
        compose.setContent(composeContent);
        when(configFileRepository.findByServiceIdAndFileKey(0L, "compose"))
                .thenReturn(java.util.Optional.of(compose));
        when(configFileRepository.findByServiceIdAndFileKey(0L, "env"))
                .thenReturn(java.util.Optional.empty());
        when(configFileRepository.findByServiceIdAndFileKey(0L, "dockerfile"))
                .thenReturn(java.util.Optional.empty());
        // 锚点 min(8080, 8082)=8080 被占 → 避让 8082，恰好等于 deployment.port（deployment 端口不变）
        when(dockerExecutor.execute(eq(1L), anyString(), eq(composeContent), any(), any(), eq(8082)))
                .thenReturn(Map.of("composePath", "/p", "resolvedPort", 8082));

        runner.run(1L);

        assertThat(d.getStatus()).isEqualTo(DeploymentStatus.SUCCESS.name());
        // deployment.port 未变（仍 8082，不重复 save deployment 是允许的，只断言值）
        assertThat(d.getPort()).isEqualTo(8082);
        // 关键：项目配置 compose 漂移被纠正
        assertThat(compose.getContent()).contains("\"8082:8080\"");
        assertThat(compose.getContent()).doesNotContain("\"8080:8080\"");
        verify(configFileRepository).save(compose);
    }

    @Test
    @DisplayName("DOCKER 模式也执行包内配置覆盖：pathKey 配置（如 requirements.txt）写入 extract 进入构建上下文（szhmh 缺陷回归）")
    void dockerMode_writesPathKeyConfigsIntoExtract() {
        DeploymentEntity d = deployment(1L, "svc", 1L, 10L, null);
        d.setMode("DOCKER");
        d.setStartCmd(null);
        d.setPort(18000);
        when(deploymentRepository.findById(1L)).thenReturn(java.util.Optional.of(d));
        when(projectRepository.findById(1L)).thenReturn(java.util.Optional.of(project("svc")));
        when(versionRepository.findById(10L)).thenReturn(java.util.Optional.of(version("svc.zip")));
        when(uploadExecutor.execute(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(Map.of("ok", 1));
        when(installExecutor.execute(anyLong(), anyString(), anyString())).thenReturn(Map.of("ok", 1));
        when(configFileRepository.findByServiceIdAndFileKey(0L, "compose"))
                .thenReturn(java.util.Optional.of(cfg("compose", "services: {}")));
        when(configFileRepository.findByServiceIdAndFileKey(0L, "env"))
                .thenReturn(java.util.Optional.empty());
        when(configFileRepository.findByServiceIdAndFileKey(0L, "dockerfile"))
                .thenReturn(java.util.Optional.empty());
        // 该子服务的配置里含一个 pathKey 条目（requirements.txt）
        List<ProjectConfigFileEntity> allConfigs =
                List.of(cfg("compose", "services: {}"), cfg("requirements.txt", "PyJWT>=2.6"));
        when(configFileRepository.findByServiceId(0L)).thenReturn(allConfigs);
        when(configOverwriteExecutor.execute(1L, "/opt/deploy/svc/10", allConfigs))
                .thenReturn(Map.of("written", List.of("requirements.txt"), "count", 1));
        when(dockerExecutor.execute(anyLong(), anyString(), eq("services: {}"), any(), any(), eq(18000)))
                .thenReturn(Map.of("composePath", "/p"));

        runner.run(1L);

        assertThat(d.getStatus()).isEqualTo(DeploymentStatus.SUCCESS.name());
        // DOCKER 分支在 up 前把 pathKey 配置覆盖写入 extract（由执行器落盘），detail 携带覆盖结果
        verify(configOverwriteExecutor).execute(1L, "/opt/deploy/svc/10", allConfigs);
        verify(dockerExecutor).execute(1L, "/opt/deploy/svc/10", "services: {}", null, null, 18000);
    }

    @Test
    @DisplayName("DOCKER 回滚：失败+旧版本 → 旧版本 compose down+up")
    void dockerMode_rollback_composeDownUp() {
        DeploymentEntity d = deployment(1L, "svc", 1L, 10L, 9L);
        d.setMode("DOCKER");
        d.setStartCmd(null);
        d.setPort(null);
        when(deploymentRepository.findById(1L)).thenReturn(java.util.Optional.of(d));
        when(projectRepository.findById(1L)).thenReturn(java.util.Optional.of(project("svc")));
        when(versionRepository.findById(10L)).thenReturn(java.util.Optional.of(version("svc.tar.gz")));
        when(uploadExecutor.execute(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenThrow(new BizException(ErrorCode.SSH_ERROR, "upload boom"));
        lenient().when(configFileRepository.findByServiceIdAndFileKey(0L, "compose"))
                .thenReturn(java.util.Optional.empty());
        lenient().when(configFileRepository.findByServiceIdAndFileKey(0L, "dockerfile"))
                .thenReturn(java.util.Optional.empty());
        // 回滚命令走 executeWithRetry（compose down + up）
        when(sshService.executeWithRetry(anyLong(), anyString(), anyInt(), anyInt()))
                .thenReturn(CommandResult.ok("", 0));

        runner.run(1L);

        assertThat(d.getStatus()).isEqualTo(DeploymentStatus.ROLLED_BACK.name());
        verify(sshService, atLeastOnce()).executeWithRetry(anyLong(), cmdCaptor.capture(), anyInt(), anyInt());
        assertThat(cmdCaptor.getAllValues())
                .anySatisfy(cmd -> assertThat(cmd)
                        .contains("docker-compose -f /opt/deploy/svc/9/docker-compose.yml down")
                        .contains("docker-compose -f /opt/deploy/svc/9/docker-compose.yml up -d"));
        verify(instanceService).onRolledBack(d);
    }

    @Test
    @DisplayName("DOCKER 无 compose 配置 → START 抛 BAD_REQUEST，无旧版本 → FAILED")
    void dockerMode_missingCompose_failed() {
        DeploymentEntity d = deployment(1L, "svc", 1L, 10L, null);
        d.setMode("DOCKER");
        d.setStartCmd(null);
        d.setPort(null);
        when(deploymentRepository.findById(1L)).thenReturn(java.util.Optional.of(d));
        when(projectRepository.findById(1L)).thenReturn(java.util.Optional.of(project("svc")));
        when(versionRepository.findById(10L)).thenReturn(java.util.Optional.of(version("svc.tar.gz")));
        when(uploadExecutor.execute(anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(Map.of("ok", 1));
        when(installExecutor.execute(anyLong(), anyString(), anyString())).thenReturn(Map.of("ok", 1));
        when(configFileRepository.findByServiceIdAndFileKey(0L, "compose"))
                .thenReturn(java.util.Optional.empty());

        runner.run(1L);

        assertThat(d.getStatus()).isEqualTo(DeploymentStatus.FAILED.name());
        assertThat(d.getErrorSummary()).contains("compose config file");
        verify(dockerExecutor, never()).execute(anyLong(), anyString(), anyString(), any(), any(), any());
        Map<Integer, DeploymentStepEntity> bySeq = savedSteps();
        assertThat(bySeq.get(3).getStatus()).isEqualTo(StepStatus.FAILED.name());
    }

    // ---------- syncDbEnvIntoDotenv（纯函数） ----------

    @Test
    @DisplayName("syncDbEnvIntoDotenv：覆写 .env 中已有 DB 键（统一双引号），未命中键追加，注释/空行/其他键原样")
    void syncDbEnvIntoDotenv_overridesAndAppends() {
        String env = """
                # app env
                APP_ENV=prod
                DB_HOST=127.0.0.1
                DB_PORT="3306"
                DB_PASSWORD='secret'
                export DB_NAME=legacy
                OTHER=keep
                """;
        Map<String, String> db = Map.of(
                "DB_HOST", "db.internal",
                "DB_PORT", "5432",
                "DB_PASSWORD", "p@ss\"q",
                "DB_NAME", "szhmh_new");
        String out = DeploymentRunner.syncDbEnvIntoDotenv(env, db);
        assertThat(out).contains("APP_ENV=prod");
        assertThat(out).contains("DB_HOST=\"db.internal\"");
        assertThat(out).contains("DB_PORT=\"5432\"");
        assertThat(out).contains("DB_PASSWORD=\"p@ss\\\"q\"");
        assertThat(out).contains("export DB_NAME=\"szhmh_new\"");
        assertThat(out).contains("OTHER=keep");
        assertThat(out).contains("# app env");
        // 原值不再出现
        assertThat(out).doesNotContain("127.0.0.1");
        assertThat(out).doesNotContain("legacy");
        // 新增 DB_USER（db 里没列）不出现
        assertThat(out).doesNotContain("DB_USER=");
    }

    @Test
    @DisplayName("syncDbEnvIntoDotenv：.env 没有的 DB 键追加到文末；dbEnv 为空/null 原样返回")
    void syncDbEnvIntoDotenv_appendsMissingAndNoop() {
        String env = "APP_ENV=prod\n";
        Map<String, String> db = Map.of("DB_HOST", "db.internal", "DB_PORT", "5432");
        String out = DeploymentRunner.syncDbEnvIntoDotenv(env, db);
        assertThat(out).contains("APP_ENV=prod");
        assertThat(out).contains("DB_HOST=\"db.internal\"");
        assertThat(out).contains("DB_PORT=\"5432\"");
        // 追加的两个键都在原行 APP_ENV 之后（不依赖两键相对顺序，Map.of 迭代序不定）
        assertThat(out.indexOf("DB_HOST=\"db.internal\"")).isGreaterThan(out.indexOf("APP_ENV=prod"));
        assertThat(out.indexOf("DB_PORT=\"5432\"")).isGreaterThan(out.indexOf("APP_ENV=prod"));
        assertThat(out).endsWith("\n");
        // 边界：env 空 / db 空 → 原样
        assertThat(DeploymentRunner.syncDbEnvIntoDotenv(null, db)).isNull();
        assertThat(DeploymentRunner.syncDbEnvIntoDotenv("APP=1", null)).isEqualTo("APP=1");
        assertThat(DeploymentRunner.syncDbEnvIntoDotenv("APP=1", Map.of())).isEqualTo("APP=1");
    }

    @Test
    @DisplayName("syncDbEnvIntoDotenv：值含特殊字符正确转义；空值去引号")
    void syncDbEnvIntoDotenv_escaping() {
        Map<String, String> db = Map.of("DB_PASSWORD", "a\\b\"c", "DB_USER", "");
        String out = DeploymentRunner.syncDbEnvIntoDotenv("X=1\n", db);
        assertThat(out).contains("DB_PASSWORD=\"a\\\\b\\\"c\"");
        assertThat(out).contains("DB_USER=");
        assertThat(out).doesNotContain("DB_USER=\"");
    }

    // ---------- 辅助 ----------

    /** 收集已保存步骤，seq → 步骤（同对象引用，取当前最终状态）。 */
    private Map<Integer, DeploymentStepEntity> savedSteps() {
        verify(stepRepository, atLeastOnce()).save(stepCaptor.capture());
        Map<Integer, DeploymentStepEntity> bySeq = new TreeMap<>();
        for (DeploymentStepEntity s : stepCaptor.getAllValues()) {
            bySeq.put(s.getSeq(), s);
        }
        return bySeq;
    }

    private static DeploymentEntity deployment(Long id, String projectName, long serverId,
                                               long versionId, Long oldVersionId) {
        DeploymentEntity d = new DeploymentEntity();
        d.setId(id);
        d.setProjectId(1L);
        d.setServerId(serverId);
        d.setVersionId(versionId);
        d.setOldVersionId(oldVersionId);
        d.setStatus(DeploymentStatus.PENDING.name());
        d.setMode("PROCESS");
        d.setStartCmd("java -jar app.jar");
        d.setPort(8080);
        d.setHealthPath("/health");
        d.setWorkdir("/opt/deploy/" + projectName + "/" + versionId);
        return d;
    }

    private static ProjectConfigFileEntity cfg(String fileKey, String content) {
        ProjectConfigFileEntity e = new ProjectConfigFileEntity();
        e.setFileKey(fileKey);
        e.setContent(content);
        return e;
    }

    private static ProjectEntity project(String name) {
        ProjectEntity p = new ProjectEntity();
        p.setId(1L);
        p.setName(name);
        return p;
    }

    private static PackageVersionEntity version(String fileName) {
        PackageVersionEntity v = new PackageVersionEntity();
        v.setId(10L);
        v.setProjectId(1L);
        v.setFileName(fileName);
        v.setStoragePath("packages/1/10-x/" + fileName);
        v.setSha256("a".repeat(64));
        v.setSizeBytes(5L);
        return v;
    }
}
