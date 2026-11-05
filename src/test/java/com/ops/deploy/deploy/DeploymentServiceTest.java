package com.ops.deploy.deploy;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.database.DatabaseService;
import com.ops.deploy.project.PackageVersionEntity;
import com.ops.deploy.project.PackageVersionRepository;
import com.ops.deploy.project.ProjectAccessService;
import com.ops.deploy.project.ProjectConfigFileEntity;
import com.ops.deploy.project.ProjectConfigFileRepository;
import com.ops.deploy.project.ProjectEntity;
import com.ops.deploy.project.ProjectRepository;
import com.ops.deploy.security.AuthUser;
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

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import org.springframework.dao.DataIntegrityViolationException;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 部署任务服务 {@link DeploymentService} 单测（mock 仓库 + Trigger）。
 *
 * <p>覆盖：create 三级兜底矩阵（请求 &gt; project &gt; detected；healthPath 缺省 "/"）、
 * 缺 startCmd → BAD_REQUEST、旧版本 oldVersionId 取自在线实例、同 server 非终态 CONFLICT、
 * 版本不存在 NOT_FOUND、cancel 仅 PENDING→CANCELLED。
 */
@ExtendWith(MockitoExtension.class)
class DeploymentServiceTest {

    @Mock
    private DeploymentRepository deploymentRepository;
    @Mock
    private DeploymentStepRepository stepRepository;
    @Mock
    private PackageVersionRepository versionRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ProjectConfigFileRepository configFileRepository;
    @Mock
    private ServiceInstanceRepository instanceRepository;
    @Mock
    private DeploymentTrigger trigger;
    @Mock
    private SshService sshService;
    @Mock
    private ConfigFileGenerator configGenerator;
    @Mock
    private DatabaseService databaseService;
    // 新增：项目访问控制 mock，void 断言方法默认 no-op，鉴权天然放行
    @Mock
    private ProjectAccessService accessService;
    // 新增：子服务解析 mock（create 按 version.serviceId 解析 pathKey → workdir；测试 version 的 serviceId 均为 null → 默认服务）
    @Mock
    private com.ops.deploy.project.ProjectSubServiceManager subServiceManager;

    @Captor
    private ArgumentCaptor<DeploymentEntity> deployCaptor;

    private DeploymentService service;

    private static final AuthUser OPS = new AuthUser(8L, "ops", "OPS");

    @BeforeEach
    void setUp() {
        service = new DeploymentService(deploymentRepository, stepRepository,
                versionRepository, projectRepository, configFileRepository,
                instanceRepository, trigger, sshService, configGenerator, databaseService, accessService, subServiceManager);
        // create 解析 workdir 用：version.serviceId 默认 0L（默认子服务）→ pathKey=null，旧口径 workdir
        org.mockito.Mockito.lenient().when(subServiceManager.resolveEntity(org.mockito.ArgumentMatchers.any()))
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

    // ---------- create：兜底矩阵 ----------

    @Test
    @DisplayName("create：请求参数覆盖全部 + PENDING 落库 + 触发")
    void create_requestOverridesAll() {
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version(null)));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("demo", null, null, null)));
        when(deploymentRepository.existsByServerIdAndStatusNotIn(eq(2L), anyCollection())).thenReturn(false);
        when(instanceRepository.findFirstByProjectIdAndServerIdAndServiceIdAndOnlineTrue(1L, 2L, 0L)).thenReturn(Optional.empty());
        mockSave();

        DeploymentVO vo = service.create(req("req-cmd", 1234, "/req"), OPS);

        assertThat(vo.getStatus()).isEqualTo(DeploymentStatus.PENDING.name());
        assertThat(vo.getStartCmd()).isEqualTo("req-cmd");
        assertThat(vo.getPort()).isEqualTo(1234);
        assertThat(vo.getHealthPath()).isEqualTo("/req");
        assertThat(vo.getWorkdir()).isEqualTo("/opt/deploy/demo/10");
        assertThat(vo.getOldVersionId()).isNull();
        assertThat(vo.getOperator()).isEqualTo(8L);
        verify(deploymentRepository).save(deployCaptor.capture());
        assertThat(deployCaptor.getValue().getMode()).isEqualTo("PROCESS");
        verify(trigger).trigger(1L);
    }

    @Test
    @DisplayName("create：请求缺失 → 取 project 默认值")
    void create_fallbackToProjectDefaults() {
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version(null)));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("demo", "proj-cmd", 2000, "/proj")));
        when(deploymentRepository.existsByServerIdAndStatusNotIn(eq(2L), anyCollection())).thenReturn(false);
        when(instanceRepository.findFirstByProjectIdAndServerIdAndServiceIdAndOnlineTrue(1L, 2L, 0L)).thenReturn(Optional.empty());
        mockSave();

        DeploymentVO vo = service.create(req(null, null, null), OPS);

        assertThat(vo.getStartCmd()).isEqualTo("proj-cmd");
        assertThat(vo.getPort()).isEqualTo(2000);
        assertThat(vo.getHealthPath()).isEqualTo("/proj");
    }

    @Test
    @DisplayName("create：请求与 project 均缺失 → 取 detected 建议值")
    void create_fallbackToDetected() {
        String detected = "{\"suggestedStartCmd\":\"det-cmd\",\"suggestedPort\":3000,\"suggestedHealthPath\":\"/det\"}";
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version(detected)));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("demo", null, null, null)));
        when(deploymentRepository.existsByServerIdAndStatusNotIn(eq(2L), anyCollection())).thenReturn(false);
        when(instanceRepository.findFirstByProjectIdAndServerIdAndServiceIdAndOnlineTrue(1L, 2L, 0L)).thenReturn(Optional.empty());
        mockSave();

        DeploymentVO vo = service.create(req(null, null, null), OPS);

        assertThat(vo.getStartCmd()).isEqualTo("det-cmd");
        assertThat(vo.getPort()).isEqualTo(3000);
        assertThat(vo.getHealthPath()).isEqualTo("/det");
    }

    @Test
    @DisplayName("create：healthPath 三级皆缺 → 默认 \"/\"")
    void create_healthPathDefaultsToSlash() {
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version(null)));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("demo", "c", 100, null)));
        when(deploymentRepository.existsByServerIdAndStatusNotIn(eq(2L), anyCollection())).thenReturn(false);
        when(instanceRepository.findFirstByProjectIdAndServerIdAndServiceIdAndOnlineTrue(1L, 2L, 0L)).thenReturn(Optional.empty());
        mockSave();

        DeploymentVO vo = service.create(req(null, null, null), OPS);

        assertThat(vo.getHealthPath()).isEqualTo("/");
    }

    @Test
    @DisplayName("create：startCmd 三级皆缺 → BAD_REQUEST(no start command available)")
    void create_missingStartCmd_throws() {
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version(null)));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("demo", null, null, null)));
        when(deploymentRepository.existsByServerIdAndStatusNotIn(eq(2L), anyCollection())).thenReturn(false);

        assertThatThrownBy(() -> service.create(req(null, null, null), OPS))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST))
                .hasMessageContaining("no start command available");
        verify(deploymentRepository, never()).save(any());
    }

    // ---------- create：mode 判定 ----------

    @Test
    @DisplayName("create：project=STATIC_WEB 且无 startCmd → NGINX 模式（免启动命令，startCmd 落 null）")
    void create_staticWeb_noStartCmd_nginxMode() {
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version(null)));
        when(projectRepository.findById(1L)).thenReturn(
                Optional.of(projectTyped("demo", null, null, null, "STATIC_WEB")));
        when(deploymentRepository.existsByServerIdAndStatusNotIn(eq(2L), anyCollection())).thenReturn(false);
        when(instanceRepository.findFirstByProjectIdAndServerIdAndServiceIdAndOnlineTrue(1L, 2L, 0L)).thenReturn(Optional.empty());
        mockSave();

        DeploymentVO vo = service.create(req(null, null, null), OPS);

        assertThat(vo.getMode()).isEqualTo("NGINX");
        assertThat(vo.getStartCmd()).isNull();
        verify(deploymentRepository).save(deployCaptor.capture());
        assertThat(deployCaptor.getValue().getMode()).isEqualTo("NGINX");
    }

    @Test
    @DisplayName("create：project=DOCKER 但缺 compose 配置文件 → 自动生成补齐，部署继续")
    void create_docker_missingCompose_autoGenerates() {
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version(null)));
        when(projectRepository.findById(1L)).thenReturn(
                Optional.of(projectTyped("demo", null, null, null, "DOCKER")));
        when(deploymentRepository.existsByServerIdAndStatusNotIn(eq(2L), anyCollection())).thenReturn(false);
        when(configFileRepository.findByServiceIdAndFileKey(0L, "compose")).thenReturn(Optional.empty());
        when(instanceRepository.findFirstByProjectIdAndServerIdAndServiceIdAndOnlineTrue(1L, 2L, 0L)).thenReturn(Optional.empty());
        mockSave();

        DeploymentVO vo = service.create(req(null, null, null), OPS);

        assertThat(vo.getMode()).isEqualTo("DOCKER");
        assertThat(vo.getStartCmd()).isNull();
        verify(configGenerator).ensureCompose(any(ProjectEntity.class), any(), any(), any());
        verify(deploymentRepository).save(deployCaptor.capture());
        assertThat(deployCaptor.getValue().getMode()).isEqualTo("DOCKER");
    }

    @Test
    @DisplayName("create：project=DOCKER 且有 compose 配置文件 → DOCKER 模式（免启动命令）")
    void create_docker_withCompose_dockerMode() {
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version(null)));
        when(projectRepository.findById(1L)).thenReturn(
                Optional.of(projectTyped("demo", null, null, null, "DOCKER")));
        when(deploymentRepository.existsByServerIdAndStatusNotIn(eq(2L), anyCollection())).thenReturn(false);
        when(configFileRepository.findByServiceIdAndFileKey(0L, "compose")).thenReturn(Optional.of(configFile("compose")));
        when(instanceRepository.findFirstByProjectIdAndServerIdAndServiceIdAndOnlineTrue(1L, 2L, 0L)).thenReturn(Optional.empty());
        mockSave();

        DeploymentVO vo = service.create(req(null, null, null), OPS);

        assertThat(vo.getMode()).isEqualTo("DOCKER");
        assertThat(vo.getStartCmd()).isNull();
        verify(deploymentRepository).save(deployCaptor.capture());
        assertThat(deployCaptor.getValue().getMode()).isEqualTo("DOCKER");
    }

    @Test
    @DisplayName("create：req.mode=PROCESS 覆盖 project 类型推导（STATIC_WEB 本应推 NGINX）")
    void create_modeProcessOverridesStaticWebProject() {
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version(null)));
        when(projectRepository.findById(1L)).thenReturn(
                Optional.of(projectTyped("demo", null, null, null, "STATIC_WEB")));
        when(deploymentRepository.existsByServerIdAndStatusNotIn(eq(2L), anyCollection())).thenReturn(false);
        when(instanceRepository.findFirstByProjectIdAndServerIdAndServiceIdAndOnlineTrue(1L, 2L, 0L)).thenReturn(Optional.empty());
        mockSave();

        DeploymentVO vo = service.create(reqWithMode("PROCESS", "java -jar app.jar", null, null), OPS);

        assertThat(vo.getMode()).isEqualTo("PROCESS");
        assertThat(vo.getStartCmd()).isEqualTo("java -jar app.jar");
        verify(deploymentRepository).save(deployCaptor.capture());
        assertThat(deployCaptor.getValue().getMode()).isEqualTo("PROCESS");
    }

    @Test
    @DisplayName("create：req.mode=NGINX 且无 startCmd → 不报错（平台托管启动）")
    void create_modeNginxNoStartCmd_ok() {
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version(null)));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("demo", null, null, null)));
        when(deploymentRepository.existsByServerIdAndStatusNotIn(eq(2L), anyCollection())).thenReturn(false);
        when(instanceRepository.findFirstByProjectIdAndServerIdAndServiceIdAndOnlineTrue(1L, 2L, 0L)).thenReturn(Optional.empty());
        mockSave();

        DeploymentVO vo = service.create(reqWithMode("NGINX", null, null, null), OPS);

        assertThat(vo.getMode()).isEqualTo("NGINX");
        assertThat(vo.getStartCmd()).isNull();
        verify(deploymentRepository).save(deployCaptor.capture());
        assertThat(deployCaptor.getValue().getMode()).isEqualTo("NGINX");
    }

    @Test
    @DisplayName("create：req.mode=PROCESS 且 startCmd 三级皆缺 → BAD_REQUEST")
    void create_modeProcessMissingStartCmd_throws() {
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version(null)));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("demo", null, null, null)));
        when(deploymentRepository.existsByServerIdAndStatusNotIn(eq(2L), anyCollection())).thenReturn(false);

        assertThatThrownBy(() -> service.create(reqWithMode("PROCESS", null, null, null), OPS))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST))
                .hasMessageContaining("no start command available");
        verify(deploymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("create：req.mode=DOCKER 且无 compose 配置文件 → 自动生成补齐，部署继续")
    void create_modeDockerMissingCompose_autoGenerates() {
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version(null)));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("demo", null, null, null)));
        when(deploymentRepository.existsByServerIdAndStatusNotIn(eq(2L), anyCollection())).thenReturn(false);
        when(configFileRepository.findByServiceIdAndFileKey(0L, "compose")).thenReturn(Optional.empty());
        when(instanceRepository.findFirstByProjectIdAndServerIdAndServiceIdAndOnlineTrue(1L, 2L, 0L)).thenReturn(Optional.empty());
        mockSave();

        DeploymentVO vo = service.create(reqWithMode("DOCKER", null, null, null), OPS);

        assertThat(vo.getMode()).isEqualTo("DOCKER");
        assertThat(vo.getStartCmd()).isNull();
        verify(configGenerator).ensureCompose(any(ProjectEntity.class), any(), any(), any());
        verify(deploymentRepository).save(any(DeploymentEntity.class));
    }

    @Test
    @DisplayName("create：非法 mode（K8S）→ BAD_REQUEST（service 层白名单兜底）")
    void create_illegalMode_throws() {
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version(null)));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("demo", "c", 100, "/")));
        when(deploymentRepository.existsByServerIdAndStatusNotIn(eq(2L), anyCollection())).thenReturn(false);

        assertThatThrownBy(() -> service.create(reqWithMode("K8S", "c", 100, "/"), OPS))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST))
                .hasMessageContaining("invalid mode");
        verify(deploymentRepository, never()).save(any());
    }

    // ---------- create：旧版本 / 冲突 / NOT_FOUND ----------

    @Test
    @DisplayName("create：在线实例对应部署的 versionId → oldVersionId")
    void create_oldVersionFromOnlineInstance() {
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version(null)));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("demo", "c", 100, "/")));
        when(deploymentRepository.existsByServerIdAndStatusNotIn(eq(2L), anyCollection())).thenReturn(false);
        ServiceInstanceEntity inst = new ServiceInstanceEntity();
        inst.setDeploymentId(55L);
        when(instanceRepository.findFirstByProjectIdAndServerIdAndServiceIdAndOnlineTrue(1L, 2L, 0L)).thenReturn(Optional.of(inst));
        DeploymentEntity oldDeploy = new DeploymentEntity();
        oldDeploy.setId(55L);
        oldDeploy.setVersionId(7L);
        when(deploymentRepository.findById(55L)).thenReturn(Optional.of(oldDeploy));
        mockSave();

        DeploymentVO vo = service.create(req(null, null, null), OPS);

        assertThat(vo.getOldVersionId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("create：同 server 存在非终态部署 → CONFLICT")
    void create_conflict_throws() {
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version(null)));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("demo", "c", 100, "/")));
        when(deploymentRepository.existsByServerIdAndStatusNotIn(eq(2L), anyCollection())).thenReturn(true);

        assertThatThrownBy(() -> service.create(req(null, null, null), OPS))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(deploymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("create：versionId 不存在 → NOT_FOUND")
    void create_versionNotFound_throws() {
        when(versionRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(req(null, null, null), OPS))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("create：healthPath 含注入字符 → BAD_REQUEST（兜底后最终校验，不落库）")
    void create_healthPathInjection_throws() {
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version(null)));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("demo", "c", 100, "/")));
        when(deploymentRepository.existsByServerIdAndStatusNotIn(eq(2L), anyCollection())).thenReturn(false);

        assertThatThrownBy(() -> service.create(req(null, null, "/health; rm -rf /"), OPS))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST))
                .hasMessageContaining("invalid healthPath");
        verify(deploymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("create：save 触发部分唯一索引冲突（并发兜底）→ CONFLICT")
    void create_saveIntegrityConflict_throwsConflict() {
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version(null)));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("demo", "c", 100, "/")));
        when(deploymentRepository.existsByServerIdAndStatusNotIn(eq(2L), anyCollection())).thenReturn(false);
        when(instanceRepository.findFirstByProjectIdAndServerIdAndServiceIdAndOnlineTrue(1L, 2L, 0L)).thenReturn(Optional.empty());
        when(deploymentRepository.save(any(DeploymentEntity.class)))
                .thenThrow(new DataIntegrityViolationException("uk conflict"));

        assertThatThrownBy(() -> service.create(req("c", 100, "/"), OPS))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.CONFLICT))
                .hasMessageContaining("another deployment is in progress");
    }

    // ---------- cancel ----------

    @Test
    @DisplayName("cancel：PENDING → CANCELLED + finishedAt")
    void cancel_pending() {
        DeploymentEntity e = new DeploymentEntity();
        e.setId(1L);
        e.setStatus(DeploymentStatus.PENDING.name());
        when(deploymentRepository.findById(1L)).thenReturn(Optional.of(e));
        when(deploymentRepository.save(any(DeploymentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        DeploymentVO vo = service.cancel(1L, OPS);

        assertThat(vo.getStatus()).isEqualTo(DeploymentStatus.CANCELLED.name());
        assertThat(e.getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("cancel：非 PENDING（UPLOADING）→ BAD_REQUEST")
    void cancel_nonPending_throws() {
        DeploymentEntity e = new DeploymentEntity();
        e.setId(1L);
        e.setStatus(DeploymentStatus.UPLOADING.name());
        when(deploymentRepository.findById(1L)).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> service.cancel(1L, OPS))
                .isInstanceOf(BizException.class)
                .satisfies(ev -> assertThat(((BizException) ev).getCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        verify(deploymentRepository, never()).save(any());
    }

    // ---------- redeploy ----------

    @Test
    @DisplayName("redeploy：终态部署 → 同参数新建部署 + 触发")
    void redeploy_terminal_createsNewDeployment() {
        DeploymentEntity origin = new DeploymentEntity();
        origin.setId(9L);
        origin.setProjectId(1L);
        origin.setVersionId(10L);
        origin.setServerId(2L);
        origin.setStatus(DeploymentStatus.FAILED.name());
        origin.setPort(8022);
        origin.setHealthPath("/");
        when(deploymentRepository.findById(9L)).thenReturn(Optional.of(origin));
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version(null)));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("demo", "proj-cmd", null, null)));
        when(deploymentRepository.existsByServerIdAndStatusNotIn(eq(2L), anyCollection())).thenReturn(false);
        when(instanceRepository.findFirstByProjectIdAndServerIdAndServiceIdAndOnlineTrue(1L, 2L, 0L)).thenReturn(Optional.empty());
        when(deploymentRepository.save(any(DeploymentEntity.class))).thenAnswer(inv -> {
            DeploymentEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(11L);
            }
            return e;
        });

        DeploymentVO vo = service.redeploy(9L, OPS);

        assertThat(vo.getId()).isEqualTo(11L);
        assertThat(vo.getVersionId()).isEqualTo(10L);
        assertThat(vo.getServerId()).isEqualTo(2L);
        assertThat(vo.getPort()).isEqualTo(8022);
        assertThat(vo.getHealthPath()).isEqualTo("/");
        verify(trigger).trigger(11L);
    }

    @Test
    @DisplayName("redeploy：沿用原 mode（原 mode=NGINX，project 无类型时仍为 NGINX 而非重推 PROCESS）")
    void redeploy_keepsOriginalMode() {
        DeploymentEntity origin = new DeploymentEntity();
        origin.setId(9L);
        origin.setProjectId(1L);
        origin.setVersionId(10L);
        origin.setServerId(2L);
        origin.setStatus(DeploymentStatus.FAILED.name());
        origin.setMode("NGINX");
        origin.setPort(8022);
        origin.setHealthPath("/");
        when(deploymentRepository.findById(9L)).thenReturn(Optional.of(origin));
        when(versionRepository.findById(10L)).thenReturn(Optional.of(version(null)));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("demo", null, null, null)));
        when(deploymentRepository.existsByServerIdAndStatusNotIn(eq(2L), anyCollection())).thenReturn(false);
        when(instanceRepository.findFirstByProjectIdAndServerIdAndServiceIdAndOnlineTrue(1L, 2L, 0L)).thenReturn(Optional.empty());
        when(deploymentRepository.save(any(DeploymentEntity.class))).thenAnswer(inv -> {
            DeploymentEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(11L);
            }
            return e;
        });

        DeploymentVO vo = service.redeploy(9L, OPS);

        assertThat(vo.getMode()).isEqualTo("NGINX");
        assertThat(vo.getPort()).isEqualTo(8022);
        verify(trigger).trigger(11L);
    }

    @Test
    @DisplayName("redeploy：非终态（进行中）→ BAD_REQUEST")
    void redeploy_nonTerminal_throws() {
        DeploymentEntity e = new DeploymentEntity();
        e.setId(9L);
        e.setStatus(DeploymentStatus.UPLOADING.name());
        when(deploymentRepository.findById(9L)).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> service.redeploy(9L, OPS))
                .isInstanceOf(BizException.class)
                .satisfies(ev -> assertThat(((BizException) ev).getCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        verify(deploymentRepository, never()).save(any());
        verifyNoInteractions(trigger);
    }

    @Test
    @DisplayName("redeploy：部署不存在 → NOT_FOUND")
    void redeploy_notFound_throws() {
        when(deploymentRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.redeploy(9L, OPS))
                .isInstanceOf(BizException.class)
                .satisfies(ev -> assertThat(((BizException) ev).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("rollback：无旧版本（oldVersionId=null）→ BAD_REQUEST")
    void rollback_withoutOldVersion_throwsBadRequest() {
        DeploymentEntity e = new DeploymentEntity();
        e.setId(9L);
        e.setProjectId(1L);
        e.setStatus(DeploymentStatus.SUCCESS.name());
        when(deploymentRepository.findById(9L)).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> service.rollback(9L, OPS))
                .isInstanceOf(BizException.class)
                .satisfies(ev -> assertThat(((BizException) ev).getCode()).isEqualTo(ErrorCode.BAD_REQUEST))
                .hasMessageContaining("no previous version");
        verifyNoInteractions(trigger);
    }

    @Test
    @DisplayName("rollback：非终态（进行中）→ BAD_REQUEST")
    void rollback_nonTerminal_throws() {
        DeploymentEntity e = new DeploymentEntity();
        e.setId(9L);
        e.setProjectId(1L);
        e.setOldVersionId(5L);
        e.setStatus(DeploymentStatus.STARTING.name());
        when(deploymentRepository.findById(9L)).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> service.rollback(9L, OPS))
                .isInstanceOf(BizException.class)
                .satisfies(ev -> assertThat(((BizException) ev).getCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        verifyNoInteractions(trigger);
    }

    @Test
    @DisplayName("rollback：终态部署 → 用 oldVersionId 新建部署（沿用旧版本启动参数）+ 触发")
    void rollback_success_createsDeploymentWithOldVersion() {
        DeploymentEntity origin = new DeploymentEntity();
        origin.setId(9L);
        origin.setProjectId(1L);
        origin.setVersionId(10L);
        origin.setServerId(2L);
        origin.setStatus(DeploymentStatus.SUCCESS.name());
        origin.setOldVersionId(5L);
        origin.setDatabaseId(3L);
        when(deploymentRepository.findById(9L)).thenReturn(Optional.of(origin));
        // 旧版本最近一次成功部署（提供 startCmd/port/healthPath/mode）
        DeploymentEntity oldDeploy = new DeploymentEntity();
        oldDeploy.setMode("PROCESS");
        oldDeploy.setStartCmd("old-cmd");
        oldDeploy.setPort(1808);
        oldDeploy.setHealthPath("/old");
        when(deploymentRepository.findFirstByProjectIdAndVersionIdAndServerIdAndStatusOrderByIdDesc(
                1L, 5L, 2L, "SUCCESS")).thenReturn(Optional.of(oldDeploy));
        // create 链路 stub
        PackageVersionEntity oldVersion = new PackageVersionEntity();
        oldVersion.setId(5L);
        oldVersion.setProjectId(1L);
        when(versionRepository.findById(5L)).thenReturn(Optional.of(oldVersion));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project("demo", "proj-cmd", 9999, "/p")));
        when(deploymentRepository.existsByServerIdAndStatusNotIn(eq(2L), anyCollection())).thenReturn(false);
        when(instanceRepository.findFirstByProjectIdAndServerIdAndServiceIdAndOnlineTrue(1L, 2L, 0L)).thenReturn(Optional.empty());
        when(deploymentRepository.save(any(DeploymentEntity.class))).thenAnswer(inv -> {
            DeploymentEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(20L);
            }
            return e;
        });

        DeploymentVO vo = service.rollback(9L, OPS);

        assertThat(vo.getId()).isEqualTo(20L);
        assertThat(vo.getVersionId()).isEqualTo(5L);
        assertThat(vo.getStartCmd()).isEqualTo("old-cmd");
        assertThat(vo.getPort()).isEqualTo(1808);
        verify(trigger).trigger(20L);
    }

    @Test
    @DisplayName("rollback：部署不存在 → NOT_FOUND")
    void rollback_notFound_throws() {
        when(deploymentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rollback(99L, OPS))
                .isInstanceOf(BizException.class)
                .satisfies(ev -> assertThat(((BizException) ev).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ---------- check-port：纯函数 ----------

    @Test
    @DisplayName("parseListeners：解析真实 ss -lntp 输出（IPv4/IPv6 混合、带 users 字段、无 users 字段）")
    void parseListeners_realSsOutput() {
        String ss = String.join("\n",
                "State  Recv-Q Send-Q Local Address:Port  Peer Address:Port Process",
                "LISTEN 0      128          0.0.0.0:22       0.0.0.0:*    users:((\"sshd\",pid=1234,fd=3))",
                "LISTEN 0      128             [::]:22          [::]:*    users:((\"sshd\",pid=1234,fd=4))",
                "LISTEN 0      511                *:8080             *:*    users:((\"java\",pid=5678,fd=56))",
                "LISTEN 0      128        127.0.0.1:5432      0.0.0.0:*");
        DeploymentService.ListenerReport report = DeploymentService.parseListeners(ss);
        assertThat(report.ports()).containsExactlyInAnyOrder(22, 8080, 5432);
        assertThat(report.processInfo().get(22)).isEqualTo("sshd (pid 1234)");
        assertThat(report.processInfo().get(8080)).isEqualTo("java (pid 5678)");
        assertThat(report.processInfo()).doesNotContainKey(5432); // 无 users 字段（无权限场景）
        assertThat(report.isListening(8080)).isTrue();
        assertThat(report.isListening(9090)).isFalse();
    }

    @Test
    @DisplayName("parseListeners：空输出 / 乱码行 → 空结果不抛异常")
    void parseListeners_emptyAndGarbage() {
        assertThat(DeploymentService.parseListeners(null).ports()).isEmpty();
        assertThat(DeploymentService.parseListeners("   ").ports()).isEmpty();
        assertThat(DeploymentService.parseListeners("not a ss output").ports()).isEmpty();
    }

    @Test
    @DisplayName("findFreePort：起点空闲返回起点；被占递增；100 次耗尽返回 null；超 65535 截断")
    void findFreePort_rules() {
        assertThat(DeploymentService.findFreePort(8080, Set.of(), 100)).isEqualTo(8080);
        assertThat(DeploymentService.findFreePort(8080, Set.of(8080, 8081), 100)).isEqualTo(8082);
        assertThat(DeploymentService.findFreePort(65535, Set.of(65535), 100)).isNull();
        Set<Integer> busyTop = new HashSet<>();
        for (int p = 65400; p <= 65535; p++) {
            busyTop.add(p);
        }
        assertThat(DeploymentService.findFreePort(65400, busyTop, 100)).isNull();
    }

    // ---------- check-port：服务级（mock SshService）----------

    @Test
    @DisplayName("checkPort：指定端口被占 → occupied=true + processInfo(含 user) + recommendedPort")
    void checkPort_occupied() {
        String ss = "LISTEN 0      128          0.0.0.0:8080      0.0.0.0:*    users:((\"java\",pid=5678,fd=56))\n"
                + "LISTEN 0      128          0.0.0.0:8081      0.0.0.0:*    users:((\"node\",pid=6001,fd=7))";
        when(sshService.execute(2L, "ss -lntp", 10000)).thenReturn(CommandResult.ok(ss, 0));
        when(sshService.execute(2L, "ps -o user= -p 5678", 5000)).thenReturn(CommandResult.ok("root\n", 0));

        DeploymentService.PortCheckResult r = service.checkPort(2L, 8080);

        assertThat(r.probed()).isTrue();
        assertThat(r.port()).isEqualTo(8080);
        assertThat(r.occupied()).isTrue();
        assertThat(r.processInfo()).isEqualTo("java (pid 5678, user root)");
        assertThat(r.recommendedPort()).isEqualTo(8082); // 8080/8081 皆占
    }

    @Test
    @DisplayName("checkPort：指定端口空闲 → occupied=false，不查 user")
    void checkPort_free() {
        when(sshService.execute(2L, "ss -lntp", 10000))
                .thenReturn(CommandResult.ok(
                        "LISTEN 0      128          0.0.0.0:22      0.0.0.0:*    users:((\"sshd\",pid=1,fd=3))", 0));

        DeploymentService.PortCheckResult r = service.checkPort(2L, 8080);

        assertThat(r.probed()).isTrue();
        assertThat(r.occupied()).isFalse();
        assertThat(r.processInfo()).isNull();
        assertThat(r.recommendedPort()).isNull();
        verify(sshService, never()).execute(eq(2L), anyString(), eq(5000));
    }

    @Test
    @DisplayName("checkPort：SSH 抛异常 → probed=false 不抛错（前端放行）")
    void checkPort_sshFails_notProbed() {
        when(sshService.execute(2L, "ss -lntp", 10000))
                .thenThrow(new RuntimeException("connection refused"));

        DeploymentService.PortCheckResult r = service.checkPort(2L, 8080);

        assertThat(r.probed()).isFalse();
        assertThat(r.occupied()).isFalse();
        assertThat(r.recommendedPort()).isNull();
    }

    // ---------- 辅助 ----------

    private void mockSave() {
        when(deploymentRepository.save(any(DeploymentEntity.class))).thenAnswer(inv -> {
            DeploymentEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(1L);
            }
            return e;
        });
    }

    private static DeploymentCreateRequest req(String startCmd, Integer port, String healthPath) {
        return reqWithMode(null, startCmd, port, healthPath);
    }

    private static DeploymentCreateRequest reqWithMode(String mode, String startCmd,
                                                       Integer port, String healthPath) {
        DeploymentCreateRequest r = new DeploymentCreateRequest();
        r.setVersionId(10L);
        r.setServerId(2L);
        r.setMode(mode);
        r.setStartCmd(startCmd);
        r.setPort(port);
        r.setHealthPath(healthPath);
        return r;
    }

    private static ProjectEntity project(String name, String startCmd, Integer port, String healthPath) {
        ProjectEntity p = new ProjectEntity();
        p.setId(1L);
        p.setName(name);
        p.setDefaultStartCmd(startCmd);
        p.setDefaultPort(port);
        p.setDefaultHealthPath(healthPath);
        return p;
    }

    private static ProjectEntity projectTyped(String name, String startCmd, Integer port,
                                              String healthPath, String detectedType) {
        ProjectEntity p = project(name, startCmd, port, healthPath);
        p.setDetectedType(detectedType);
        return p;
    }

    private static ProjectConfigFileEntity configFile(String key) {
        ProjectConfigFileEntity c = new ProjectConfigFileEntity();
        c.setProjectId(1L);
        c.setFileKey(key);
        c.setContent("services: {}");
        return c;
    }

    private static PackageVersionEntity version(String detectedJson) {
        PackageVersionEntity v = new PackageVersionEntity();
        v.setId(10L);
        v.setProjectId(1L);
        v.setFileName("app.zip");
        v.setStoragePath("packages/1/10-x/app.zip");
        v.setSha256("a".repeat(64));
        v.setSizeBytes(5L);
        v.setDetectedJson(detectedJson);
        return v;
    }
}
