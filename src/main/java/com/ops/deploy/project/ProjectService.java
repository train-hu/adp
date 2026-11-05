package com.ops.deploy.project;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 项目管理服务：CRUD + 部署包上传（类型识别）+ 版本管理。
 *
 * <p>安全/业务要点：
 * <ul>
 *   <li>项目名唯一：先 existsByName 校验，DataIntegrityViolation 兜底 CONFLICT（防并发竞态）；</li>
 *   <li>上传两阶段落盘（{@link PackageStorage#storeTemp} 流式算 sha → 拿到自增 versionId 后
 *       {@link PackageStorage#moveIntoPlace}）；同项目下 sha256 重复 → CONFLICT；</li>
 *   <li>上传大小上限 {@code deploy.max-upload-size}（默认 2GB），超限 BAD_REQUEST；</li>
 *   <li>git 来源（file 与 gitUrl 二选一）：平台侧 clone → 冻结 commit（git_commit）→ 打 zip
 *       走同一落库链路；私有仓库凭证加密存 git_auth_cipher（响应不回传）；</li>
 *   <li>{@link TypeDetector} 识别（zip/tar.gz 内部解压到临时目录识别后自清理；jar/war 直接分析），
 *       结果序列化 JSON 存 detected_json；识别失败/UNKNOWN 不影响落库，返回 UNKNOWN + error；</li>
 *   <li>project.detected_type 仅在项目首次上传（或 update 手动确认）时回填识别值；</li>
 * <li>delete 项目级联删除所有版本记录 + 存储文件 + 部署记录（含步骤/文档）+ 服务实例
  * （service_instance/deployment 对 project 为 RESTRICT 外键，须先清理才能删项目）。</li>
 * </ul>
 */
@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    /** 识别结果序列化（存 detected_json，JSONB 列以 JSON 文本存储）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProjectRepository projectRepository;
    private final PackageVersionRepository versionRepository;
    private final PackageStorage storage;
    private final TypeDetector detector;
    private final ProjectConfigFileService configFileService;
    private final ServiceInstanceRepository serviceInstanceRepository;
    private final DeploymentRepository deploymentRepository;
    private final DeploymentStepRepository deploymentStepRepository;
    private final DeploymentDocumentRepository deploymentDocumentRepository;
    private final ProjectAccessService accessService;
    private final ProjectMemberRepository memberRepository;
    private final GitSourceService gitSourceService;
    private final CredentialCryptoService credentialCryptoService;
    private final ProjectSubServiceManager subServiceManager;
    private final ProjectSubServiceRepository subServiceRepository;
    private final long maxUploadSize;

    public ProjectService(ProjectRepository projectRepository,
                          PackageVersionRepository versionRepository,
                          PackageStorage storage,
                          TypeDetector detector,
                          ProjectConfigFileService configFileService,
                          ServiceInstanceRepository serviceInstanceRepository,
                          DeploymentRepository deploymentRepository,
                          DeploymentStepRepository deploymentStepRepository,
                          DeploymentDocumentRepository deploymentDocumentRepository,
                          ProjectAccessService accessService,
                          ProjectMemberRepository memberRepository,
                          GitSourceService gitSourceService,
                          CredentialCryptoService credentialCryptoService,
                          ProjectSubServiceManager subServiceManager,
                          ProjectSubServiceRepository subServiceRepository,
                          @Value("${deploy.max-upload-size:2147483648}") long maxUploadSize) {
        this.projectRepository = projectRepository;
        this.versionRepository = versionRepository;
        this.storage = storage;
        this.detector = detector;
        this.configFileService = configFileService;
        this.serviceInstanceRepository = serviceInstanceRepository;
        this.deploymentRepository = deploymentRepository;
        this.deploymentStepRepository = deploymentStepRepository;
        this.deploymentDocumentRepository = deploymentDocumentRepository;
        this.accessService = accessService;
        this.memberRepository = memberRepository;
        this.gitSourceService = gitSourceService;
        this.credentialCryptoService = credentialCryptoService;
        this.subServiceManager = subServiceManager;
        this.subServiceRepository = subServiceRepository;
        this.maxUploadSize = maxUploadSize;
    }

    // ---------- 项目 CRUD ----------

    @Transactional
    public ProjectVO create(ProjectCreateRequest req, AuthUser user) {
        if (projectRepository.existsByName(req.getName())) {
            throw new BizException(ErrorCode.CONFLICT, "project name already exists");
        }
        ProjectEntity entity = new ProjectEntity();
        entity.setName(req.getName());
        entity.setDescription(req.getDescription());
        if (req.getDefaultPort() != null) {
            entity.setDefaultPort(req.getDefaultPort());
        }
        entity.setMultiService(req.isMultiService());
        entity.setCreatedBy(user.getUserId());
        entity.setCreatedAt(LocalDateTime.now());
        try {
            projectRepository.save(entity);
        } catch (DataIntegrityViolationException e) {
            // (name) 唯一约束竞态 → CONFLICT；外键违规（created_by 引用的用户缺失）不可伪装
            // 成"名称已存在"误导用户，抛 SERVER_ERROR 由全局处理器按 FK 违规归类 500
            if (e.getMessage() != null && e.getMessage().contains("23503")) {
                throw new BizException(ErrorCode.SERVER_ERROR, "internal server error");
            }
            throw new BizException(ErrorCode.CONFLICT, "project name already exists");
        }
        // 创建人自动成为项目成员（同事务，成员即可完全管理项目）
        accessService.registerMember(entity.getId(), user.getUserId());
        return ProjectVO.from(entity);
    }

    /** 项目列表（按可见性隔离）：ADMIN 全部可见；非 ADMIN 仅可见其成员项目。 */
    public List<ProjectVO> list(AuthUser user) {
        List<Long> visibleIds = accessService.visibleProjectIds(user);
        if (visibleIds != null && visibleIds.isEmpty()) {
            return List.of();
        }
        List<ProjectEntity> entities = visibleIds == null
                ? projectRepository.findAll(Sort.by(Sort.Direction.ASC, "id"))
                : projectRepository.findAllById(visibleIds)
                        .stream()
                        .sorted(java.util.Comparator.comparing(ProjectEntity::getId))
                        .collect(Collectors.toList());
        return entities.stream()
                .map(entity -> {
                    ProjectVO vo = ProjectVO.from(entity);
                    if (!accessService.isAdmin(user)) {
                        // 列表已按成员过滤，非 ADMIN 可见的项目必为其成员项目
                        vo.setIsMember(true);
                    }
                    return vo;
                })
                .collect(Collectors.toList());
    }

    public ProjectVO get(Long id, AuthUser user) {
        accessService.assertView(id, user);
        ProjectVO vo = projectRepository.findById(id)
                .map(ProjectVO::from)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "project not found"));
        if (!accessService.isAdmin(user)) {
            vo.setIsMember(true);
        }
        return vo;
    }

    @Transactional
    public ProjectVO update(Long id, ProjectUpdateRequest req, AuthUser user) {
        accessService.assertManageProject(id, user);
        ProjectEntity entity = projectRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "project not found"));
        if (req.getDescription() != null) {
            entity.setDescription(req.getDescription());
        }
        // detectedType 允许手动覆盖（识别值或人工指定）
        if (req.getDetectedType() != null) {
            entity.setDetectedType(req.getDetectedType());
        }
        if (req.getDefaultStartCmd() != null) {
            entity.setDefaultStartCmd(req.getDefaultStartCmd());
        }
        if (req.getDefaultPort() != null) {
            entity.setDefaultPort(req.getDefaultPort());
        }
        if (req.getDefaultHealthPath() != null) {
            entity.setDefaultHealthPath(req.getDefaultHealthPath());
        }
        projectRepository.save(entity);
        return ProjectVO.from(entity);
    }

    /**
     * 删除项目（级联删除所有版本记录 + 存储文件 + 部署记录（含步骤/文档）+ 服务实例）。
     *
     * <p>前置校验：项目下任意服务器存在 status=RUNNING 的服务实例时禁止删除，
     * 抛 {@code CONFLICT}（409）并列出运行中实例，由前端弹窗提醒用户先停止服务。
     * 已停止（STOPPED/FAILED）的实例不拦截：项目允许删除，其部署与实例记录一并级联清理
     *（service_instance.project_id / deployment.project_id 对 project 为 RESTRICT 外键，
     *  若不清理则删项目时触发外键冲突 500）。
     *
     * <p>已知容忍（M1 单实例低概率）：本方法在事务内先删 DB 记录再删存储文件，
     * 若文件删除成功但事务提交失败（如连接中断），会残留"文件已删、记录仍在"状态。
     * best-effort 策略：文件删除失败仅 log.warn 不阻断；提交失败产生的残留可通过
     * 再次删除该项目清理，不影响正确性。
     */
    @Transactional
    public void delete(Long id, AuthUser user) {
        accessService.assertManageProject(id, user);
        ProjectEntity entity = projectRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "project not found"));
        List<ServiceInstanceEntity> running =
                serviceInstanceRepository.findByProjectIdAndStatus(id, "RUNNING");
        if (!running.isEmpty()) {
            String names = running.stream()
                    .map(ServiceInstanceEntity::getName)
                    .collect(Collectors.joining("、"));
            throw new BizException(ErrorCode.CONFLICT,
                    "项目下有 " + running.size() + " 个服务正在运行（" + names + "），"
                            + "请先停止服务后再删除项目");
        }
        // 级联清理部署（含步骤/文档）与服务实例：这两类表对 project 为 RESTRICT 外键，
        // 不先删会导致 projectRepository.delete 触发 23503 外键冲突（E2E 实测 500）。
        List<DeploymentEntity> deployments = deploymentRepository.findByProjectId(id);
        for (DeploymentEntity d : deployments) {
            deploymentStepRepository.deleteByDeploymentId(d.getId());
            deploymentDocumentRepository.deleteByDeploymentId(d.getId());
        }
        if (!deployments.isEmpty()) {
            deploymentRepository.deleteAll(deployments);
        }
        serviceInstanceRepository.deleteAll(serviceInstanceRepository.findByProjectId(id));

        List<PackageVersionEntity> versions =
                versionRepository.findByProjectIdOrderByCreatedAtDesc(id);
        versionRepository.deleteAll(versions);
        configFileService.deleteByProject(id);
        // 子服务最后删：package_version / project_config_file 的 service_id 外键
        // 指向子服务，须先删完版本和配置才能删子服务（否则 23503 外键冲突）。
        subServiceRepository.deleteAll(
                subServiceRepository.findByProjectIdOrderByCreatedAtAsc(id));
        // 最后删项目本身：deployment/service_instance/package_version 对 project 均为 RESTRICT 外键，
        // 若先删项目，事务提交时触发 23503 外键冲突（E2E 实测：含版本的项目删除 500）。
        memberRepository.deleteByProjectId(id);
        projectRepository.delete(entity);
        // 存储文件 best-effort 删除（DB 与文件尽力一致，失败仅告警不阻断）
        for (PackageVersionEntity v : versions) {
            try {
                storage.delete(v.getStoragePath());
            } catch (RuntimeException e) {
                log.warn("failed to delete package file for version {}: {}", v.getId(), e.getMessage());
            }
        }
    }

    // ---------- 部署包上传 ----------

    /**
     * 上传部署包：校验 → 流式临时存储(sha256) → 去重 → 类型识别 → 落库 → move 到正式路径。
     *
     * @return 落库后的版本视图（含 detectedType / detectedJson）
     */
    @Transactional
    public PackageVersionVO uploadPackage(Long projectId, MultipartFile file, String versionNote,
                                          AuthUser user) throws IOException {
        return uploadPackage(projectId, 0L, file, versionNote, null, user);
    }

    /**
     * 上传部署包（文件 / git 地址二选一）。git 来源走 {@link GitSourceService#fetch}：
     * 平台侧 clone → 冻结 commit → 打 zip → 与文件上传共用同一落库链路。
     *
     * @param serviceId 目标子服务 id；null/0 = 项目默认子服务
     * @param gitSource git 来源参数（gitUrl 非空即 git 模式）；与 file 二选一，
     *                  均提供或均未提供 → BAD_REQUEST
     */
    @Transactional
    public PackageVersionVO uploadPackage(Long projectId, Long serviceId, MultipartFile file,
                                          String versionNote, PackageGitSource gitSource,
                                          AuthUser user) throws IOException {
        accessService.assertOperate(projectId, user);
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "project not found"));
        Long resolvedServiceId = subServiceManager.resolve(projectId, serviceId).getId();

        boolean hasFile = file != null && !file.isEmpty();
        boolean hasGit = gitSource != null && gitSource.getGitUrl() != null
                && !gitSource.getGitUrl().isBlank();
        if (hasFile && hasGit) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "部署包来源二选一：file 与 gitUrl 不能同时提供");
        }
        if (!hasFile && !hasGit) {
            throw new BizException(ErrorCode.BAD_REQUEST, "uploaded file is empty");
        }

        String fileName;
        PackageStorage.TempFile temp;
        DetectedResult detection;
        String gitUrl = null, gitRef = null, gitCommit = null;
        byte[] gitAuthCipher = null;
        if (hasGit) {
            // git 来源：clone + 冻结 commit + 打 zip（内部已 storeTemp 算 sha + 识别）
            GitSourceService.Result result = gitSourceService.fetch(
                    gitSource.getGitUrl(), gitSource.getGitRef(),
                    gitSource.getGitUsername(), gitSource.getGitPassword());
            temp = result.temp();
            detection = result.detection();
            if (temp.getSizeBytes() > maxUploadSize) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "git 打包结果过大 (max " + maxUploadSize + " bytes)");
            }
            fileName = storage.sanitizeFileName(temp.getFileName());
            gitUrl = gitSource.getGitUrl().trim();
            gitRef = gitSource.getGitRef();
            gitCommit = result.commitId();
            if (gitSource.getGitUsername() != null && !gitSource.getGitUsername().isBlank()) {
                // 私有仓库凭证 AES-256-GCM 加密存储（username:password），响应不回传
                gitAuthCipher = credentialCryptoService.encrypt(
                        gitSource.getGitUsername().trim() + ":"
                                + (gitSource.getGitPassword() == null
                                        ? "" : gitSource.getGitPassword()));
            }
        } else {
            long size = file.getSize();
            if (size > maxUploadSize) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "file too large (max " + maxUploadSize + " bytes)");
            }
            fileName = storage.sanitizeFileName(file.getOriginalFilename());
            try (InputStream in = file.getInputStream()) {
                temp = storage.storeTemp(fileName, in);
            }
            detection = detector.detectArchive(temp.getTempPath(), extensionOf(fileName));
        }

        return persistFromTemp(project, temp, fileName, versionNote, detection,
                gitUrl, gitRef, gitCommit, gitAuthCipher, resolvedServiceId, user);
    }

    /**
     * 分片上传合并完成后的落库入口：与文件上传共用同一链路
     * （sha 去重 → 类型识别 → 两阶段落盘 → 回填 detected_type → 应用包内配置）。
     * 由 {@link ChunkUploadService#complete} 在分片合并 + 校验通过后调用。
     *
     * @param temp 已合并的完整包临时文件（路径 + 服务端实算 sha256 + 大小）
     */
    @Transactional
    public PackageVersionVO uploadMergedPackage(Long projectId, PackageStorage.TempFile temp,
                                                String fileName, String versionNote, AuthUser user)
            throws IOException {
        return uploadMergedPackage(projectId, 0L, temp, fileName, versionNote, user);
    }

    /**
     * 分片上传合并完成后的落库入口：与文件上传共用同一链路
     * （sha 去重 → 类型识别 → 两阶段落盘 → 回填 detected_type → 应用包内配置）。
     *
     * @param serviceId 目标子服务 id；null/0 = 项目默认子服务
     * @param temp 已合并的完整包临时文件（路径 + 服务端实算 sha256 + 大小）
     */
    @Transactional
    public PackageVersionVO uploadMergedPackage(Long projectId, Long serviceId,
                                                PackageStorage.TempFile temp,
                                                String fileName, String versionNote, AuthUser user)
            throws IOException {
        accessService.assertOperate(projectId, user);
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "project not found"));
        if (temp.getSizeBytes() > maxUploadSize) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "file too large (max " + maxUploadSize + " bytes)");
        }
        Long resolvedServiceId = subServiceManager.resolve(projectId, serviceId).getId();
        DetectedResult detection = detector.detectArchive(temp.getTempPath(), extensionOf(fileName));
        return persistFromTemp(project, temp, fileName, versionNote, detection,
                null, null, null, null, resolvedServiceId, user);
    }

    /**
     * 从已就绪的临时文件落库（文件上传 / git 拉取 / 分片合并三条链路的公共尾部）：
     * sha 去重 → 首次落库（占位 storagePath）→ move 到含真实 id 的正式路径 → 二次落库
     * → 首次上传回填 project.detected_type → 应用包内配置文件。
     * 失败时按"是否已 move"清理正式文件或临时文件，并原样抛出。
     */
    private PackageVersionVO persistFromTemp(ProjectEntity project, PackageStorage.TempFile temp,
                                             String fileName, String versionNote,
                                             DetectedResult detection,
                                             String gitUrl, String gitRef, String gitCommit,
                                             byte[] gitAuthCipher, Long serviceId, AuthUser user)
            throws IOException {
        Long projectId = project.getId();
        String storagePath = null;
        try {
            // 同服务下内容指纹去重（serviceId 已由调用方解析，必为有效子服务 id）
            versionRepository.findByServiceIdAndSha256(serviceId, temp.getSha256()).ifPresent(dup -> {
                throw new BizException(ErrorCode.CONFLICT,
                        "package with same content already exists (version id=" + dup.getId() + ")");
            });

            String detectedJson = JSON.writeValueAsString(detection);

            PackageVersionEntity version = new PackageVersionEntity();
            version.setProjectId(projectId);
            version.setServiceId(serviceId);
            version.setFileName(fileName);
            version.setSha256(temp.getSha256());
            version.setSizeBytes(temp.getSizeBytes());
            version.setVersionNote(versionNote);
            version.setDetectedJson(detectedJson);
            version.setUploadedBy(user.getUserId());
            version.setCreatedAt(LocalDateTime.now());
            version.setGitUrl(gitUrl);
            version.setGitRef(gitRef);
            version.setGitCommit(gitCommit);
            version.setGitAuthCipher(gitAuthCipher);
            // storagePath 为 NOT NULL，但正式路径依赖 versionId，而 id 又依赖首次落库。
            // 解法：先落库（storagePath 预填占位路径），save 后拿到 id 再把临时文件
            // move 到含真实 id 的正式路径，并回填确认。占位路径在 move 前不应被读取
            // （版本未 move 完成前不存在可下载内容，列表/下载均会指向文件不存在的状态）。
            version.setStoragePath(storage.buildRelativePath(projectId, 0L, temp.getSha256(), fileName));
            try {
                versionRepository.save(version);
            } catch (DataIntegrityViolationException e) {
                // 并发下 (service_id, sha256) 唯一约束兜底（23505）：
                // check-then-act 去重（findByServiceIdAndSha256）与插入之间存在竞态，
                // 此时 temp 尚未 move，先清理临时文件再抛 CONFLICT；
                // 其它约束违规（如外键）不在此伪装，交全局处理器归类
                if (e.getMessage() != null && e.getMessage().contains("23505")) {
                    storage.deleteTemp(temp);
                    temp = null;
                    throw new BizException(ErrorCode.CONFLICT,
                            "package with same content already exists");
                }
                throw e;
            }

            String movedPath = storage.moveIntoPlace(temp, projectId, version.getId(), fileName);
            // move 成功即标记：此后任何失败都需清理"已 move 的正式文件"而非临时文件
            //（临时文件此刻已不存在）。必须在第二次 save 之前赋值，否则第二次 save
            // 失败时 storagePath 仍为 null，cleanup 会误走 deleteTemp 路径。
            storagePath = movedPath;
            version.setStoragePath(movedPath);
            versionRepository.save(version);

            // 首次上传（或项目尚未确认类型）时回填 project.detected_type；UNKNOWN 不回填
            if (project.getDetectedType() == null && !"UNKNOWN".equals(detection.getDetectedType())) {
                project.setDetectedType(detection.getDetectedType());
                projectRepository.save(project);
            }

            // 包内自带配置文件（compose/env/nginx）检查可用性后直接应用（upsert，覆盖既有），
            // 展示在项目配置文件列表；校验不通过不阻断上传，入库并带告警供前端提示。
            applyExtractedConfigs(serviceId, detection);

            return PackageVersionVO.from(version);
        } catch (RuntimeException | IOException e) {
            if (storagePath != null) {
                // move 成功后、返回前失败：临时文件已不存在，需清理已 move 的正式文件
                // （best-effort：删除失败仅告警，不覆盖原始异常）
                try {
                    storage.delete(storagePath);
                } catch (RuntimeException ex) {
                    log.warn("failed to delete orphan package file after upload failure: {}",
                            storagePath, ex);
                }
            } else {
                // 业务失败/IO 失败：清理临时文件（deleteTemp 对 null 为 no-op）
                storage.deleteTemp(temp);
            }
            throw e;
        }
    }

    // ---------- 版本管理 ----------

    public List<PackageVersionVO> listVersions(Long projectId, AuthUser user) {
        return listVersions(projectId, 0L, user);
    }

    /**
     * 版本列表：serviceId 为 null/0 时返回项目下全部版本（兼容旧前端）；
     * 否则仅返回该子服务的版本。
     */
    public List<PackageVersionVO> listVersions(Long projectId, Long serviceId, AuthUser user) {
        accessService.assertView(projectId, user);
        Long resolvedServiceId = (serviceId == null || serviceId == 0L)
                ? null : subServiceManager.resolve(projectId, serviceId).getId();
        List<PackageVersionEntity> entities = resolvedServiceId == null
                ? versionRepository.findByProjectIdOrderByCreatedAtDesc(projectId)
                : versionRepository.findByServiceIdOrderByCreatedAtDesc(resolvedServiceId);
        return entities.stream()
                .map(PackageVersionVO::from)
                .collect(Collectors.toList());
    }

    public PackageVersionVO getVersion(Long versionId, AuthUser user) {
        PackageVersionEntity version = requireVersion(versionId);
        accessService.assertView(version.getProjectId(), user);
        return PackageVersionVO.from(version);
    }

    /** 返回版本实体（含 storagePath/fileName），供 Controller 二进制下载。 */
    public PackageVersionEntity getVersionEntity(Long versionId, AuthUser user) {
        PackageVersionEntity version = requireVersion(versionId);
        accessService.assertView(version.getProjectId(), user);
        return version;
    }

    @Transactional
    public void deleteVersion(Long versionId, AuthUser user) {
        PackageVersionEntity version = requireVersion(versionId);
        accessService.assertManageProject(version.getProjectId(), user);
        try {
            storage.delete(version.getStoragePath());
        } catch (RuntimeException e) {
            log.warn("failed to delete package file for version {}: {}", versionId, e.getMessage());
        }
        versionRepository.delete(version);
    }

    private PackageVersionEntity requireVersion(Long versionId) {
        return versionRepository.findById(versionId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "package version not found"));
    }

    // ---------- 内部工具 ----------

    /**
     * 包内提取的配置文件（fileKey → 全文）逐条 upsert 入库，直接展示在子服务配置文件列表。
     * 单项失败仅告警不阻断上传（包已落库，配置可后续手动补）。
     *
     * @param serviceId 目标子服务 id（配置按服务作用域存储）
     */
    private void applyExtractedConfigs(Long serviceId, DetectedResult detection) {
        Map<String, String> configs = detection.getExtractedConfigs();
        if (configs.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> e : configs.entrySet()) {
            ProjectConfigFileRequest req = new ProjectConfigFileRequest();
            req.setFileKey(e.getKey());
            req.setContent(e.getValue());
            try {
                configFileService.upsert(serviceId, req);
                log.info("extracted config fileKey={} applied from uploaded package for service {}",
                        e.getKey(), serviceId);
            } catch (RuntimeException ex) {
                log.warn("failed to apply extracted config {} for service {}: {}",
                        e.getKey(), serviceId, ex.getMessage());
            }
        }
    }

    /** 取识别用扩展名：{@code .tar.gz} 需保留两段（detectArchive 按 tar.gz 分支处理）。 */
    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".tar.gz")) {
            return "tar.gz";
        }
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1) : "";
    }
}
