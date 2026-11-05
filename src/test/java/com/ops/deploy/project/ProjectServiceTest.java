package com.ops.deploy.project;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.crypto.CredentialCryptoService;
import com.ops.deploy.deploy.DeploymentDocumentRepository;
import com.ops.deploy.deploy.DeploymentEntity;
import com.ops.deploy.deploy.DeploymentRepository;
import com.ops.deploy.deploy.DeploymentStepRepository;
import com.ops.deploy.deploy.ServiceInstanceEntity;
import com.ops.deploy.deploy.ServiceInstanceRepository;
import com.ops.deploy.security.AuthUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProjectService 单测：mock 两个 Repository + mock PackageStorage + mock TypeDetector。
 * 覆盖：create 冲突、upload 全链路（ArgumentCaptor 校验落库字段）、sha 重复、
 * UNKNOWN 仍落 detected_json、首次上传回填 detected_type、delete 级联、版本管理。
 */
@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    private static final long MAX_UPLOAD = 2L * 1024 * 1024 * 1024;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private PackageVersionRepository versionRepository;

    @Mock
    private PackageStorage storage;

    @Mock
    private TypeDetector detector;

    @Mock
    private ProjectConfigFileService configFileService;

    @Mock
    private ServiceInstanceRepository serviceInstanceRepository;

    @Mock
    private DeploymentRepository deploymentRepository;

    @Mock
    private DeploymentStepRepository deploymentStepRepository;

    @Mock
    private DeploymentDocumentRepository deploymentDocumentRepository;

    // 新增：项目访问控制 mock，void 断言方法默认 no-op，鉴权天然放行
    @Mock
    private ProjectAccessService accessService;
    // 新增：项目成员仓库 mock（delete 级联清理成员用）
    @Mock
    private ProjectMemberRepository memberRepository;
    // 新增：git 源码拉取 mock（git 上传场景用）
    @Mock
    private GitSourceService gitSourceService;
    // 新增：凭证加解密 mock（git 私有仓库凭证加密落库用）
    @Mock
    private CredentialCryptoService credentialCryptoService;
    // 新增：子服务解析 mock（upload/listVersions 解析 serviceId → 默认子服务）
    @Mock
    private ProjectSubServiceManager subServiceManager;
    // 新增：子服务仓库 mock（delete 级联清理子服务用）
    @Mock
    private ProjectSubServiceRepository subServiceRepository;

    private ProjectService service;

    private final AuthUser admin = new AuthUser(9L, "admin", "ADMIN");

    @BeforeEach
    void setUp() {
        service = new ProjectService(projectRepository, versionRepository, storage, detector,
                configFileService, serviceInstanceRepository,
                deploymentRepository, deploymentStepRepository, deploymentDocumentRepository,
                accessService, memberRepository, gitSourceService, credentialCryptoService,
                subServiceManager, subServiceRepository, MAX_UPLOAD);
        // 默认无运行中实例、无部署记录（避免 Strict 模式 UnnecessaryStubbing；delete 场景可覆盖）
        org.mockito.Mockito.lenient()
                .when(serviceInstanceRepository.findByProjectIdAndStatus(anyLong(), eq("RUNNING")))
                .thenReturn(List.of());
        org.mockito.Mockito.lenient()
                .when(deploymentRepository.findByProjectId(anyLong()))
                .thenReturn(List.of());
        org.mockito.Mockito.lenient()
                .when(serviceInstanceRepository.findByProjectId(anyLong()))
                .thenReturn(List.of());
        // 默认子服务（pathKey=null，沿用旧口径路径/单元名）：上传/版本列表按 0 → 默认解析
        org.mockito.Mockito.lenient()
                .when(subServiceManager.resolve(anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(defaultSubService(1L));
        // delete 级联：项目下无其他子服务
        org.mockito.Mockito.lenient()
                .when(subServiceRepository.findByProjectIdOrderByCreatedAtAsc(anyLong()))
                .thenReturn(List.of());
    }

    private ProjectSubServiceEntity defaultSubService(long id) {
        ProjectSubServiceEntity e = new ProjectSubServiceEntity();
        e.setId(id);
        e.setProjectId(1L);
        e.setName("p1");
        e.setPathKey(null);
        return e;
    }

    private ProjectEntity project(Long id, String name, String detectedType) {
        ProjectEntity p = new ProjectEntity();
        p.setId(id);
        p.setName(name);
        p.setDetectedType(detectedType);
        return p;
    }

    private DetectedResult detected(String type, String buildTool) {
        DetectedResult d = new DetectedResult();
        d.setDetectedType(type);
        d.setBuildTool(buildTool);
        d.setConfidence(80);
        return d;
    }

    private DetectedResult detectedWithConfigs(String type, String buildTool,
                                               java.util.Map<String, String> configs) {
        DetectedResult d = detected(type, buildTool);
        d.setExtractedConfigs(configs);
        return d;
    }

    private PackageStorage.TempFile tempFile(String sha, long size, String name) {
        PackageStorage.TempFile t = new PackageStorage.TempFile();
        t.setTempPath(Path.of("/tmp/x.part"));
        t.setSha256(sha);
        t.setSizeBytes(size);
        t.setFileName(name);
        return t;
    }

    // ---------- create ----------

    @Test
    void create_success_shouldSetCreatedByAndReturnVo() {
        when(projectRepository.existsByName("p1")).thenReturn(false);
        doAnswer(inv -> {
            ProjectEntity e = inv.getArgument(0);
            e.setId(1L);
            return e;
        }).when(projectRepository).save(any(ProjectEntity.class));

        ProjectCreateRequest req = new ProjectCreateRequest();
        req.setName("p1");
        req.setDescription("desc");
        req.setDefaultPort(8080);
        ProjectVO vo = service.create(req, admin);

        assertThat(vo.getId()).isEqualTo(1L);
        assertThat(vo.getName()).isEqualTo("p1");
        ArgumentCaptor<ProjectEntity> captor = ArgumentCaptor.forClass(ProjectEntity.class);
        verify(projectRepository).save(captor.capture());
        assertThat(captor.getValue().getCreatedBy()).isEqualTo(9L);
        assertThat(captor.getValue().getCreatedAt()).isNotNull();
        // 创建时填写的默认端口必须落库（否则列表不显示、部署无兜底端口）
        assertThat(captor.getValue().getDefaultPort()).isEqualTo(8080);
        assertThat(vo.getDefaultPort()).isEqualTo(8080);
    }

    @Test
    void create_duplicateName_shouldThrowConflict() {
        when(projectRepository.existsByName("dup")).thenReturn(true);

        ProjectCreateRequest req = new ProjectCreateRequest();
        req.setName("dup");

        assertThatThrownBy(() -> service.create(req, admin))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void create_uniqueViolation_shouldFallbackToConflict() {
        when(projectRepository.existsByName("race")).thenReturn(false);
        when(projectRepository.save(any(ProjectEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        ProjectCreateRequest req = new ProjectCreateRequest();
        req.setName("race");

        assertThatThrownBy(() -> service.create(req, admin))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    // ---------- get / list / update / delete ----------

    @Test
    void get_notFound_shouldThrowNotFound() {
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(99L, admin))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void update_detectedType_manualOverride_shouldPersist() {
        ProjectEntity existing = project(1L, "p1", null);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(existing));

        ProjectUpdateRequest req = new ProjectUpdateRequest();
        req.setDetectedType("NODE");
        req.setDefaultPort(8080);
        service.update(1L, req, admin);

        ArgumentCaptor<ProjectEntity> captor = ArgumentCaptor.forClass(ProjectEntity.class);
        verify(projectRepository).save(captor.capture());
        assertThat(captor.getValue().getDetectedType()).isEqualTo("NODE");
        assertThat(captor.getValue().getDefaultPort()).isEqualTo(8080);
    }

    @Test
    void update_notFound_shouldThrowNotFound() {
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());
        ProjectUpdateRequest req = new ProjectUpdateRequest();
        assertThatThrownBy(() -> service.update(99L, req, admin))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void delete_shouldCascadeDeleteVersionsAndFiles() {
        ProjectEntity p = project(1L, "p1", "JAVA_MAVEN");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p));
        PackageVersionEntity v1 = new PackageVersionEntity();
        v1.setId(10L);
        v1.setStoragePath("packages/1/10-abc/app.zip");
        PackageVersionEntity v2 = new PackageVersionEntity();
        v2.setId(11L);
        v2.setStoragePath("packages/1/11-def/app2.zip");
        when(versionRepository.findByProjectIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(v1, v2));

        service.delete(1L, admin);

        verify(storage).delete("packages/1/10-abc/app.zip");
        verify(storage).delete("packages/1/11-def/app2.zip");
        verify(versionRepository).deleteAll(any());
        verify(configFileService).deleteByProject(1L);
        verify(projectRepository).delete(p);
    }

    @Test
    void delete_notFound_shouldThrowNotFound() {
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(99L, admin))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void delete_withRunningInstances_shouldThrowConflict() {
        ProjectEntity p = project(1L, "p1", "JAVA_MAVEN");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p));
        ServiceInstanceEntity running = new ServiceInstanceEntity();
        running.setId(5L);
        running.setName("p1-srv1-10");
        ServiceInstanceEntity running2 = new ServiceInstanceEntity();
        running2.setId(6L);
        running2.setName("p1-srv2-10");
        when(serviceInstanceRepository.findByProjectIdAndStatus(1L, "RUNNING"))
                .thenReturn(List.of(running, running2));

        assertThatThrownBy(() -> service.delete(1L, admin))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException ex = (BizException) e;
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(ex.getMessage()).contains("p1-srv1-10").contains("p1-srv2-10");
                });
        // 拦截后不执行任何删除
        verify(projectRepository, never()).delete(any(ProjectEntity.class));
        verify(versionRepository, never()).deleteAll(any());
    }

    @Test
    void delete_withDeploymentsAndStoppedInstances_shouldCascadeCleanupAndDelete() {
        // 回归：service_instance / deployment 对 project 为 RESTRICT 外键，
        // 存在（已停止）实例与部署记录时，删项目前必须先级联清理，否则 23503 → 500（E2E 实测暴露）。
        ProjectEntity p = project(1L, "p1", "JAVA_MAVEN");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p));
        when(versionRepository.findByProjectIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        DeploymentEntity d1 = new DeploymentEntity();
        d1.setId(100L);
        when(deploymentRepository.findByProjectId(1L)).thenReturn(List.of(d1));

        ServiceInstanceEntity stopped = new ServiceInstanceEntity();
        stopped.setId(5L);
        stopped.setName("p1-srv1-10");
        stopped.setStatus("STOPPED");
        when(serviceInstanceRepository.findByProjectId(1L)).thenReturn(List.of(stopped));

        service.delete(1L, admin);

        // 部署的步骤与文档先清理，再删部署
        verify(deploymentStepRepository).deleteByDeploymentId(100L);
        verify(deploymentDocumentRepository).deleteByDeploymentId(100L);
        verify(deploymentRepository).deleteAll(any());
        // 服务实例级联清理
        verify(serviceInstanceRepository).deleteAll(any());
        // 项目本体与版本删除
        verify(projectRepository).delete(p);
        verify(configFileService).deleteByProject(1L);
    }

    // ---------- upload ----------

    private MockMultipartFile file(String name, String content) {
        byte[] b = content.getBytes(StandardCharsets.UTF_8);
        return new MockMultipartFile("file", name, "application/zip", b);
    }

    @Test
    void upload_fullFlow_shouldPersistAllFields() throws Exception {
        ProjectEntity p = project(1L, "p1", null);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p));
        when(storage.sanitizeFileName("app.zip")).thenReturn("app.zip");
        PackageStorage.TempFile temp = tempFile("a".repeat(64), 5, "app.zip");
        when(storage.storeTemp(eq("app.zip"), any(InputStream.class))).thenReturn(temp);
        when(versionRepository.findByServiceIdAndSha256(1L, "a".repeat(64))).thenReturn(Optional.empty());
        when(detector.detectArchive(any(Path.class), eq("zip"))).thenReturn(detected("JAVA_MAVEN", "maven"));
        doAnswer(inv -> {
            PackageVersionEntity e = inv.getArgument(0);
            e.setId(10L);
            return e;
        }).when(versionRepository).save(any(PackageVersionEntity.class));
        when(storage.moveIntoPlace(eq(temp), eq(1L), eq(10L), eq("app.zip")))
                .thenReturn("packages/1/10-" + "a".repeat(12) + "/app.zip");

        PackageVersionVO vo = service.uploadPackage(1L, file("app.zip", "hello"), "v1", admin);

        assertThat(vo.getId()).isEqualTo(10L);
        assertThat(vo.getProjectId()).isEqualTo(1L);
        assertThat(vo.getFileName()).isEqualTo("app.zip");
        assertThat(vo.getSha256()).isEqualTo("a".repeat(64));
        assertThat(vo.getDetectedType()).isEqualTo("JAVA_MAVEN");

        // 落库字段校验（第二次 save 携带最终 storagePath）
        ArgumentCaptor<PackageVersionEntity> captor = ArgumentCaptor.forClass(PackageVersionEntity.class);
        verify(versionRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        PackageVersionEntity last = captor.getValue();
        assertThat(last.getFileName()).isEqualTo("app.zip");
        assertThat(last.getSizeBytes()).isEqualTo(5L);
        assertThat(last.getVersionNote()).isEqualTo("v1");
        assertThat(last.getUploadedBy()).isEqualTo(9L);
        assertThat(last.getStoragePath()).isEqualTo("packages/1/10-" + "a".repeat(12) + "/app.zip");
        assertThat(last.getDetectedJson()).contains("JAVA_MAVEN");
    }

    @Test
    void upload_withExtractedConfigs_shouldUpsertEachAndContinue() throws Exception {
        ProjectEntity p = project(1L, "p1", null);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p));
        when(storage.sanitizeFileName("app.zip")).thenReturn("app.zip");
        PackageStorage.TempFile temp = tempFile("b".repeat(64), 5, "app.zip");
        when(storage.storeTemp(eq("app.zip"), any(InputStream.class))).thenReturn(temp);
        when(versionRepository.findByServiceIdAndSha256(1L, "b".repeat(64))).thenReturn(Optional.empty());
        java.util.Map<String, String> configs = new java.util.LinkedHashMap<>();
        configs.put("compose", "services:\n  app: {}");
        configs.put("env", "KEY=value\n");
        when(detector.detectArchive(any(Path.class), eq("zip")))
                .thenReturn(detectedWithConfigs("DOCKER", "docker", configs));
        doAnswer(inv -> {
            PackageVersionEntity e = inv.getArgument(0);
            e.setId(10L);
            return e;
        }).when(versionRepository).save(any(PackageVersionEntity.class));
        when(storage.moveIntoPlace(eq(temp), eq(1L), eq(10L), eq("app.zip")))
                .thenReturn("packages/1/10-" + "b".repeat(12) + "/app.zip");

        service.uploadPackage(1L, file("app.zip", "hello"), "v1", admin);

        ArgumentCaptor<ProjectConfigFileRequest> cap = ArgumentCaptor.forClass(ProjectConfigFileRequest.class);
        verify(configFileService, org.mockito.Mockito.times(2)).upsert(eq(1L), cap.capture());
        assertThat(cap.getAllValues())
                .extracting(ProjectConfigFileRequest::getFileKey)
                .containsExactly("compose", "env");
        assertThat(cap.getAllValues().get(0).getContent()).contains("services:");
    }

    @Test
    void upload_withExtractedConfigSingleFailure_shouldNotBlockUpload() throws Exception {
        ProjectEntity p = project(1L, "p1", null);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p));
        when(storage.sanitizeFileName("app.zip")).thenReturn("app.zip");
        PackageStorage.TempFile temp = tempFile("c".repeat(64), 5, "app.zip");
        when(storage.storeTemp(eq("app.zip"), any(InputStream.class))).thenReturn(temp);
        when(versionRepository.findByServiceIdAndSha256(1L, "c".repeat(64))).thenReturn(Optional.empty());
        java.util.Map<String, String> configs = new java.util.LinkedHashMap<>();
        configs.put("env", "KEY=value\n");
        configs.put("nginx", "server {}");
        when(detector.detectArchive(any(Path.class), eq("zip")))
                .thenReturn(detectedWithConfigs("STATIC_WEB", "none", configs));
        doAnswer(inv -> {
            PackageVersionEntity e = inv.getArgument(0);
            e.setId(10L);
            return e;
        }).when(versionRepository).save(any(PackageVersionEntity.class));
        when(storage.moveIntoPlace(eq(temp), eq(1L), eq(10L), eq("app.zip")))
                .thenReturn("packages/1/10-" + "c".repeat(12) + "/app.zip");
        // 首个 upsert 抛错不应阻断：后续项仍被尝试，上传本身成功
        org.mockito.Mockito.when(configFileService.upsert(eq(1L), any(ProjectConfigFileRequest.class)))
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(new ProjectConfigFileVO());

        PackageVersionVO vo = service.uploadPackage(1L, file("app.zip", "hello"), "v1", admin);

        assertThat(vo.getId()).isEqualTo(10L);
        verify(configFileService, org.mockito.Mockito.times(2)).upsert(eq(1L), any(ProjectConfigFileRequest.class));
    }

    @Test
    void upload_firstSave_shouldHaveNonBlankStoragePath() throws Exception {
        // 回归：storage_path 列为 NOT NULL，而正式路径依赖 versionId（依赖首次落库拿 id）。
        // 首次 save 前必须预填非空占位路径，否则真实 DB 上插入即失败（E2E 实测暴露）。
        ProjectEntity p = project(1L, "p1", null);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p));
        when(storage.sanitizeFileName("app.zip")).thenReturn("app.zip");
        PackageStorage.TempFile temp = tempFile("a".repeat(64), 5, "app.zip");
        when(storage.storeTemp(eq("app.zip"), any(InputStream.class))).thenReturn(temp);
        when(versionRepository.findByServiceIdAndSha256(1L, "a".repeat(64))).thenReturn(Optional.empty());
        when(detector.detectArchive(any(Path.class), eq("zip"))).thenReturn(detected("JAVA_MAVEN", "maven"));
        when(storage.buildRelativePath(eq(1L), eq(0L), eq("a".repeat(64)), eq("app.zip")))
                .thenReturn("packages/1/0-" + "a".repeat(12) + "/app.zip");
        // 记录每次 save 时 version.storagePath 的"值"（字符串不可变）。
        // 不能用 ArgumentCaptor：它捕获的是对象引用，而同一 version 实例在第二次
        // setStoragePath 后会被复写，导致第一次 save 的占位路径被后续值覆盖。
        List<String> storagePathAtSave = new ArrayList<>();
        doAnswer(inv -> {
            PackageVersionEntity e = inv.getArgument(0);
            e.setId(10L);
            storagePathAtSave.add(e.getStoragePath());
            return e;
        }).when(versionRepository).save(any(PackageVersionEntity.class));
        when(storage.moveIntoPlace(eq(temp), eq(1L), eq(10L), eq("app.zip")))
                .thenReturn("packages/1/10-" + "a".repeat(12) + "/app.zip");

        service.uploadPackage(1L, file("app.zip", "hello"), "v1", admin);

        // 第一次 save 前 storagePath 已预填非空占位路径（versionId=0），move 后回填真实路径
        assertThat(storagePathAtSave).hasSize(2);
        assertThat(storagePathAtSave.get(0))
                .isEqualTo("packages/1/0-" + "a".repeat(12) + "/app.zip");
        assertThat(storagePathAtSave.get(1))
                .isEqualTo("packages/1/10-" + "a".repeat(12) + "/app.zip");
    }

    @Test
    void upload_duplicateSha_shouldThrowConflictAndCleanupTemp() throws Exception {
        ProjectEntity p = project(1L, "p1", null);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p));
        when(storage.sanitizeFileName("app.zip")).thenReturn("app.zip");
        PackageStorage.TempFile temp = tempFile("b".repeat(64), 5, "app.zip");
        when(storage.storeTemp(eq("app.zip"), any(InputStream.class))).thenReturn(temp);
        PackageVersionEntity dup = new PackageVersionEntity();
        dup.setId(77L);
        when(versionRepository.findByServiceIdAndSha256(1L, "b".repeat(64))).thenReturn(Optional.of(dup));

        assertThatThrownBy(() -> service.uploadPackage(1L, file("app.zip", "hello"), "v", admin))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(e.getMessage()).contains("77");
                });

        verify(storage).deleteTemp(temp);
        verify(versionRepository, never()).save(any(PackageVersionEntity.class));
    }

    @Test
    void upload_shaUniqueViolationRace_shouldThrowConflictAndCleanupTemp() throws Exception {
        ProjectEntity p = project(1L, "p1", null);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p));
        when(storage.sanitizeFileName("app.zip")).thenReturn("app.zip");
        PackageStorage.TempFile temp = tempFile("e".repeat(64), 5, "app.zip");
        when(storage.storeTemp(eq("app.zip"), any(InputStream.class))).thenReturn(temp);
        // 业务查询去重未命中（竞态），save 时命中 (project_id, sha256) 唯一约束
        when(versionRepository.findByServiceIdAndSha256(1L, "e".repeat(64))).thenReturn(Optional.empty());
        when(detector.detectArchive(any(Path.class), eq("zip"))).thenReturn(detected("JAVA_MAVEN", "none"));
        when(versionRepository.save(any(PackageVersionEntity.class)))
                .thenThrow(new DataIntegrityViolationException("SQLState 23505: duplicate key uk_package_version_project_sha"));

        assertThatThrownBy(() -> service.uploadPackage(1L, file("app.zip", "hello"), "v", admin))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(e.getMessage()).contains("same content");
                });

        // move 之前的 save 竞态：临时文件必须清理，且未进入 move 阶段
        verify(storage).deleteTemp(temp);
        verify(storage, never()).moveIntoPlace(any(), anyLong(), anyLong(), any());
    }

    @Test
    void upload_unknownType_shouldStillSaveDetectedJson() throws Exception {
        ProjectEntity p = project(1L, "p1", null);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p));
        when(storage.sanitizeFileName("mystery.bin")).thenReturn("mystery.bin");
        PackageStorage.TempFile temp = tempFile("c".repeat(64), 5, "mystery.bin");
        when(storage.storeTemp(eq("mystery.bin"), any(InputStream.class))).thenReturn(temp);
        when(versionRepository.findByServiceIdAndSha256(1L, "c".repeat(64))).thenReturn(Optional.empty());
        DetectedResult unknown = detected("UNKNOWN", "none");
        unknown.setConfidence(5);
        when(detector.detectArchive(any(Path.class), eq("bin"))).thenReturn(unknown);
        doAnswer(inv -> {
            PackageVersionEntity e = inv.getArgument(0);
            e.setId(20L);
            return e;
        }).when(versionRepository).save(any(PackageVersionEntity.class));
        when(storage.moveIntoPlace(eq(temp), eq(1L), eq(20L), eq("mystery.bin"))).thenReturn("packages/1/20-x/mystery.bin");

        service.uploadPackage(1L, file("mystery.bin", "hello"), null, admin);

        ArgumentCaptor<PackageVersionEntity> captor = ArgumentCaptor.forClass(PackageVersionEntity.class);
        verify(versionRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getDetectedJson()).contains("UNKNOWN");
        // UNKNOWN 不应回填 project.detected_type
        ArgumentCaptor<ProjectEntity> pCaptor = ArgumentCaptor.forClass(ProjectEntity.class);
        verify(projectRepository, never()).save(pCaptor.capture());
    }

    @Test
    void upload_emptyFile_shouldThrowBadRequest() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project(1L, "p1", null)));
        MockMultipartFile empty = new MockMultipartFile("file", "e.zip", "application/zip", new byte[0]);

        assertThatThrownBy(() -> service.uploadPackage(1L, empty, null, admin))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void upload_tooLarge_shouldThrowBadRequest() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project(1L, "p1", null)));
        // 用小上限验证逻辑：构造一个 service 上限为 10 字节
        ProjectService small = new ProjectService(projectRepository, versionRepository, storage, detector,
                configFileService, serviceInstanceRepository,
                deploymentRepository, deploymentStepRepository, deploymentDocumentRepository,
                accessService, memberRepository, gitSourceService, credentialCryptoService,
                subServiceManager, subServiceRepository, 10L);
        MockMultipartFile big = file("big.zip", "0123456789ABCDEF");

        assertThatThrownBy(() -> small.uploadPackage(1L, big, null, admin))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void upload_projectNotFound_shouldThrowNotFound() throws Exception {
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());
        MockMultipartFile f = file("app.zip", "hello");

        assertThatThrownBy(() -> service.uploadPackage(99L, f, null, admin))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void upload_firstUpload_shouldBackfillProjectDetectedType() throws Exception {
        ProjectEntity p = project(1L, "p1", null); // 首次，detected_type 为空
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p));
        when(storage.sanitizeFileName("app.zip")).thenReturn("app.zip");
        PackageStorage.TempFile temp = tempFile("d".repeat(64), 5, "app.zip");
        when(storage.storeTemp(eq("app.zip"), any(InputStream.class))).thenReturn(temp);
        when(versionRepository.findByServiceIdAndSha256(1L, "d".repeat(64))).thenReturn(Optional.empty());
        when(detector.detectArchive(any(Path.class), eq("zip"))).thenReturn(detected("NODE", "npm"));
        doAnswer(inv -> {
            PackageVersionEntity e = inv.getArgument(0);
            e.setId(30L);
            return e;
        }).when(versionRepository).save(any(PackageVersionEntity.class));
        when(storage.moveIntoPlace(eq(temp), eq(1L), eq(30L), eq("app.zip"))).thenReturn("packages/1/30-x/app.zip");

        service.uploadPackage(1L, file("app.zip", "hello"), null, admin);

        // 回填 project.detected_type = 识别值
        verify(projectRepository).save(p);
        assertThat(p.getDetectedType()).isEqualTo("NODE");
    }

    @Test
    void upload_moveSuccessThenSecondSaveFails_shouldDeleteMovedFile() throws Exception {
        ProjectEntity p = project(1L, "p1", "NODE"); // 非首次，跳过 project 回填，聚焦 move 后第二次 save 失败
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p));
        when(storage.sanitizeFileName("app.zip")).thenReturn("app.zip");
        PackageStorage.TempFile temp = tempFile("f".repeat(64), 5, "app.zip");
        when(storage.storeTemp(eq("app.zip"), any(InputStream.class))).thenReturn(temp);
        when(versionRepository.findByServiceIdAndSha256(1L, "f".repeat(64))).thenReturn(Optional.empty());
        when(detector.detectArchive(any(Path.class), eq("zip"))).thenReturn(detected("NODE", "npm"));
        doAnswer(inv -> {
            PackageVersionEntity e = inv.getArgument(0);
            e.setId(40L);
            return e;
        }).doThrow(new IllegalStateException("second save failed (e.g. db down)"))
                .when(versionRepository)
                .save(any(PackageVersionEntity.class));
        when(storage.moveIntoPlace(eq(temp), eq(1L), eq(40L), eq("app.zip")))
                .thenReturn("packages/1/40-x/app.zip");

        assertThatThrownBy(() -> service.uploadPackage(1L, file("app.zip", "hello"), null, admin))
                .isInstanceOf(IllegalStateException.class);

        // move 成功后失败：临时文件已不存在，需清理已 move 的正式文件，不再 deleteTemp
        verify(storage).delete("packages/1/40-x/app.zip");
        verify(storage, never()).deleteTemp(any());
    }

    // ---------- upload（git 来源） ----------

    private GitSourceService.Result gitResult(String commit, String sha, String type) {
        return new GitSourceService.Result(commit, detected(type, "maven"),
                tempFile(sha, 5, "demo.git.zip"));
    }

    private PackageGitSource gitSource(String url, String ref, String username, String password) {
        PackageGitSource g = new PackageGitSource();
        g.setGitUrl(url);
        g.setGitRef(ref);
        g.setGitUsername(username);
        g.setGitPassword(password);
        return g;
    }

    @Test
    void upload_gitSource_shouldFreezeCommitAndEncryptAuth() throws Exception {
        ProjectEntity p = project(1L, "p1", null); // 首次上传，验证 detected_type 回填
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p));
        String sha = "1".repeat(64);
        String commit = "c".repeat(40);
        GitSourceService.Result result = gitResult(commit, sha, "JAVA_MAVEN");
        when(gitSourceService.fetch("https://git.example.com/demo.git", "feature", "bob", "secret"))
                .thenReturn(result);
        when(storage.sanitizeFileName("demo.git.zip")).thenReturn("demo.git.zip");
        when(versionRepository.findByServiceIdAndSha256(1L, sha)).thenReturn(Optional.empty());
        // 私有仓库凭证加密后落库（username:password）
        when(credentialCryptoService.encrypt("bob:secret")).thenReturn(new byte[]{1, 2, 3});
        doAnswer(inv -> {
            PackageVersionEntity e = inv.getArgument(0);
            e.setId(50L);
            return e;
        }).when(versionRepository).save(any(PackageVersionEntity.class));
        PackageStorage.TempFile temp = result.temp();
        when(storage.moveIntoPlace(eq(temp), eq(1L), eq(50L), eq("demo.git.zip")))
                .thenReturn("packages/1/50-" + sha.substring(0, 12) + "/demo.git.zip");

        PackageVersionVO vo = service.uploadPackage(1L, null, null, "v1",
                gitSource("https://git.example.com/demo.git", "feature", "bob", "secret"), admin);

        assertThat(vo.getId()).isEqualTo(50L);
        assertThat(vo.getFileName()).isEqualTo("demo.git.zip");
        assertThat(vo.getGitUrl()).isEqualTo("https://git.example.com/demo.git");
        assertThat(vo.getGitRef()).isEqualTo("feature");
        assertThat(vo.getGitCommit()).isEqualTo(commit);
        assertThat(vo.getDetectedType()).isEqualTo("JAVA_MAVEN");

        ArgumentCaptor<PackageVersionEntity> captor = ArgumentCaptor.forClass(PackageVersionEntity.class);
        verify(versionRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        PackageVersionEntity last = captor.getValue();
        assertThat(last.getGitUrl()).isEqualTo("https://git.example.com/demo.git");
        assertThat(last.getGitRef()).isEqualTo("feature");
        assertThat(last.getGitCommit()).isEqualTo(commit);
        assertThat(last.getGitAuthCipher()).isEqualTo(new byte[]{1, 2, 3});
        // 首次上传回填 project.detected_type（git 目录识别结果同文件上传）
        assertThat(p.getDetectedType()).isEqualTo("JAVA_MAVEN");
    }

    @Test
    void upload_gitPublicRepo_shouldNotEncryptCredentials() throws Exception {
        ProjectEntity p = project(1L, "p1", "JAVA_MAVEN"); // 非首次，跳过 project 回填
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p));
        String sha = "2".repeat(64);
        GitSourceService.Result result = gitResult("d".repeat(40), sha, "JAVA_MAVEN");
        when(gitSourceService.fetch("https://git.example.com/demo.git", null, null, null))
                .thenReturn(result);
        when(storage.sanitizeFileName("demo.git.zip")).thenReturn("demo.git.zip");
        when(versionRepository.findByServiceIdAndSha256(1L, sha)).thenReturn(Optional.empty());
        doAnswer(inv -> {
            PackageVersionEntity e = inv.getArgument(0);
            e.setId(51L);
            return e;
        }).when(versionRepository).save(any(PackageVersionEntity.class));
        when(storage.moveIntoPlace(eq(result.temp()), eq(1L), eq(51L), eq("demo.git.zip")))
                .thenReturn("packages/1/51-x/demo.git.zip");

        service.uploadPackage(1L, null, null, null,
                gitSource("https://git.example.com/demo.git", null, null, null), admin);

        // 无凭证不加密；落库 git 字段完整、密文为 null
        ArgumentCaptor<PackageVersionEntity> captor = ArgumentCaptor.forClass(PackageVersionEntity.class);
        verify(versionRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getGitAuthCipher()).isNull();
        assertThat(captor.getValue().getGitRef()).isNull();
        verify(credentialCryptoService, never()).encrypt(any());
    }

    @Test
    void upload_gitAndFileBothProvided_shouldThrowBadRequest() throws Exception {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project(1L, "p1", null)));

        assertThatThrownBy(() -> service.uploadPackage(1L, null, file("app.zip", "hello"), null,
                gitSource("https://git.example.com/demo.git", null, null, null), admin))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST));

        verify(gitSourceService, never()).fetch(any(), any(), any(), any());
        verify(storage, never()).storeTemp(any(), any(InputStream.class));
    }

    @Test
    void upload_gitDuplicateSha_shouldThrowConflictAndCleanupTemp() throws Exception {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project(1L, "p1", null)));
        String sha = "3".repeat(64);
        GitSourceService.Result result = gitResult("e".repeat(40), sha, "NODE");
        when(gitSourceService.fetch("https://git.example.com/demo.git", null, null, null))
                .thenReturn(result);
        PackageVersionEntity dup = new PackageVersionEntity();
        dup.setId(78L);
        when(versionRepository.findByServiceIdAndSha256(1L, sha)).thenReturn(Optional.of(dup));

        assertThatThrownBy(() -> service.uploadPackage(1L, null, null, "v",
                gitSource("https://git.example.com/demo.git", null, null, null), admin))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(e.getMessage()).contains("78");
                });

        // 去重命中：临时文件必须清理，且未落库
        verify(storage).deleteTemp(result.temp());
        verify(versionRepository, never()).save(any(PackageVersionEntity.class));
    }

    // ---------- version 管理 ----------

    @Test
    void listVersions_shouldReturnDesc() {
        PackageVersionEntity v1 = new PackageVersionEntity();
        v1.setId(1L);
        PackageVersionEntity v2 = new PackageVersionEntity();
        v2.setId(2L);
        // 注：accessService 为 mock（assertView 默认 no-op），无需再 stub projectRepository.findById
        when(versionRepository.findByProjectIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(v2, v1));

        List<PackageVersionVO> vos = service.listVersions(1L, admin);

        assertThat(vos).extracting(PackageVersionVO::getId).containsExactly(2L, 1L);
    }

    @Test
    void getVersion_shouldIncludeRawDetectedJson() {
        PackageVersionEntity v = new PackageVersionEntity();
        v.setId(1L);
        v.setDetectedJson("{\"detectedType\":\"NODE\"}");
        when(versionRepository.findById(1L)).thenReturn(Optional.of(v));

        PackageVersionVO vo = service.getVersion(1L, admin);

        assertThat(vo.getDetectedJson()).isEqualTo("{\"detectedType\":\"NODE\"}");
    }

    @Test
    void getVersion_notFound_shouldThrowNotFound() {
        when(versionRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getVersion(99L, admin))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void deleteVersion_shouldDeleteFileAndRecord() {
        PackageVersionEntity v = new PackageVersionEntity();
        v.setId(5L);
        v.setStoragePath("packages/1/5-x/app.zip");
        when(versionRepository.findById(5L)).thenReturn(Optional.of(v));

        service.deleteVersion(5L, admin);

        verify(storage).delete("packages/1/5-x/app.zip");
        verify(versionRepository).delete(v);
    }

    @Test
    void deleteVersion_notFound_shouldThrowNotFound() {
        when(versionRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteVersion(99L, admin))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
