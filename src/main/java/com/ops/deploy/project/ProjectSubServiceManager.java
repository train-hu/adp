package com.ops.deploy.project;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.deploy.DeploymentDocumentRepository;
import com.ops.deploy.deploy.DeploymentEntity;
import com.ops.deploy.deploy.DeploymentRepository;
import com.ops.deploy.deploy.DeploymentStepRepository;
import com.ops.deploy.deploy.ServiceInstanceRepository;
import com.ops.deploy.security.AuthUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 项目下子服务（project_sub_service）管理：CRUD + 路径/单元名解析。
 *
 * <p>每个项目自动持有一条"默认子服务"（name=项目名，path_key=NULL）：
 * 由 V19 迁移回填，或 {@link #requireDefault} 在首次访问时补建。
 * 默认子服务沿用旧 workdir / unit / nginx 口径，保证存量实例完全兼容；
 * 新建的非默认子服务 path_key=slug(name)，走新口径路径与单元名。
 *
 * <p>类名带 Sub 前缀：既有的 {@link ProjectService} 是"项目 CRUD 服务"，
 * 这里管理的是"项目下的子服务"，避免命名混淆。
 */
@Service
public class ProjectSubServiceManager {

    private final ProjectSubServiceRepository repository;
    private final ProjectRepository projectRepository;
    private final ProjectAccessService accessService;
    private final PackageVersionRepository versionRepository;
    private final ProjectConfigFileRepository configRepository;
    private final DeploymentRepository deploymentRepository;
    private final DeploymentStepRepository deploymentStepRepository;
    private final DeploymentDocumentRepository deploymentDocumentRepository;
    private final ServiceInstanceRepository serviceInstanceRepository;

    public ProjectSubServiceManager(ProjectSubServiceRepository repository,
                                    ProjectRepository projectRepository,
                                    ProjectAccessService accessService,
                                    PackageVersionRepository versionRepository,
                                    ProjectConfigFileRepository configRepository,
                                    DeploymentRepository deploymentRepository,
                                    DeploymentStepRepository deploymentStepRepository,
                                    DeploymentDocumentRepository deploymentDocumentRepository,
                                    ServiceInstanceRepository serviceInstanceRepository) {
        this.repository = repository;
        this.projectRepository = projectRepository;
        this.accessService = accessService;
        this.versionRepository = versionRepository;
        this.configRepository = configRepository;
        this.deploymentRepository = deploymentRepository;
        this.deploymentStepRepository = deploymentStepRepository;
        this.deploymentDocumentRepository = deploymentDocumentRepository;
        this.serviceInstanceRepository = serviceInstanceRepository;
    }

    /** 项目下全部子服务（默认在前，其余按创建时间升序）。单服务项目确保默认服务存在（懒建兜底）。 */
    public List<ProjectSubServiceEntity> listByProject(Long projectId, AuthUser user) {
        accessService.assertView(projectId, user);
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "project not found: " + projectId));
        if (!project.isMultiService()) {
            requireDefault(projectId);
        }
        return repository.findByProjectIdOrderByCreatedAtAsc(projectId).stream()
                .sorted(Comparator.comparing(
                                (ProjectSubServiceEntity s) -> s.isDefault() ? 0 : 1)
                        .thenComparing(ProjectSubServiceEntity::getId))
                .toList();
    }

    /** 取项目默认子服务（path_key=NULL），不存在则补建（迁移遗漏兜底）。多服务项目不建默认服务。 */
    public ProjectSubServiceEntity requireDefault(Long projectId) {
        return repository.findFirstByProjectIdAndPathKeyIsNull(projectId)
                .orElseGet(() -> {
                    ProjectEntity project = projectRepository.findById(projectId)
                            .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                                    "project not found: " + projectId));
                    if (project.isMultiService()) {
                        throw new BizException(ErrorCode.NOT_FOUND,
                                "multi-service project has no default service, "
                                        + "please select a specific service");
                    }
                    ProjectSubServiceEntity entity = new ProjectSubServiceEntity();
                    entity.setProjectId(projectId);
                    entity.setName(project.getName());
                    entity.setPathKey(null);
                    LocalDateTime now = LocalDateTime.now();
                    entity.setCreatedAt(now);
                    entity.setUpdatedAt(now);
                    try {
                        return repository.save(entity);
                    } catch (DataIntegrityViolationException e) {
                        // 并发补建：重查默认子服务
                        return repository.findFirstByProjectIdAndPathKeyIsNull(projectId)
                                .orElseThrow(() -> e);
                    }
                });
    }

    /**
     * 解析子服务 id：null/0 → 项目默认子服务；否则必须存在且属于该项目。
     */
    public ProjectSubServiceEntity resolve(Long projectId, Long serviceId) {
        if (serviceId == null || serviceId == 0L) {
            return requireDefault(projectId);
        }
        ProjectSubServiceEntity entity = repository.findById(serviceId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "service not found: " + serviceId));
        if (!entity.getProjectId().equals(projectId)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "service " + serviceId + " does not belong to project " + projectId);
        }
        return entity;
    }

    /**
     * 按子服务 id 解析（不做项目归属校验）。
     * 用于已知 serviceId 为有效值的内部链路（如 ProjectConfigFileService），
     * 省去 projectId 校验开销。serviceId 为 null/0 时按 0 处理并抛 NOT_FOUND
     * （调用方应确保传入已解析的有效子服务 id）。
     */
    public ProjectSubServiceEntity resolveEntity(Long serviceId) {
        if (serviceId == null || serviceId == 0L) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "serviceId is required and must be a resolved sub-service id");
        }
        return repository.findById(serviceId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "service not found: " + serviceId));
    }

    /**
     * 新建子服务：同项目内 name 唯一；path_key 由 name slug 化生成。
     * startCmd/port 为服务级部署参数（多服务项目必填启动命令）；存量调用走旧签名（不带启动参数）。
     */
    @Transactional
    public ProjectSubServiceEntity create(Long projectId, String name, AuthUser user) {
        return create(projectId, name, null, null, user);
    }

    @Transactional
    public ProjectSubServiceEntity create(Long projectId, String name,
                                          String startCmd, Integer port, AuthUser user) {
        accessService.assertManageProject(projectId, user);
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "project not found: " + projectId));
        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "service name is required");
        }
        if (port != null && (port < 1 || port > 65535)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "port out of range 1-65535");
        }
        String trimmed = name.trim();
        if (trimmed.equals(project.getName())
                && repository.findFirstByProjectIdAndPathKeyIsNull(projectId).isPresent()) {
            throw new BizException(ErrorCode.CONFLICT,
                    "service name conflicts with the project's default service");
        }
        if (repository.existsByProjectIdAndName(projectId, trimmed)) {
            throw new BizException(ErrorCode.CONFLICT,
                    "a service with this name already exists in project");
        }
        ProjectSubServiceEntity entity = new ProjectSubServiceEntity();
        entity.setProjectId(projectId);
        entity.setName(trimmed);
        entity.setPathKey(slugify(trimmed));
        entity.setStartCmd(startCmd == null || startCmd.isBlank() ? null : startCmd.trim());
        entity.setPort(port);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            return repository.save(entity);
        } catch (DataIntegrityViolationException e) {
            throw new BizException(ErrorCode.CONFLICT,
                    "a service with this name already exists in project");
        }
    }

    /** 删除非默认子服务。默认子服务（path_key=NULL）不可删。 */
    @Transactional
    public void delete(Long projectId, Long serviceId, AuthUser user) {
        accessService.assertManageProject(projectId, user);
        ProjectSubServiceEntity entity = repository.findById(serviceId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "service not found: " + serviceId));
        if (!entity.getProjectId().equals(projectId)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "service " + serviceId + " does not belong to project " + projectId);
        }
        if (entity.isDefault()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "the default service of a project cannot be deleted");
        }
        // 级联清理：4 张表的 service_id 对子服务是 RESTRICT 外键，
        // 直接删会触发 23503 外键冲突。顺序：
        //   deployment → step/doc（外键 deployment_id RESTRICT）
        //   service_instance（外键 service_id RESTRICT，deployment_id 在 deleteDeployment 已置空）
        //   package_version（外键 service_id RESTRICT）
        //   project_config_file（外键 service_id RESTRICT）
        // 最后删子服务本身。
        List<DeploymentEntity> deployments = deploymentRepository.findByServiceId(serviceId);
        for (DeploymentEntity d : deployments) {
            deploymentStepRepository.deleteByDeploymentId(d.getId());
            deploymentDocumentRepository.deleteByDeploymentId(d.getId());
        }
        if (!deployments.isEmpty()) {
            deploymentRepository.deleteAll(deployments);
        }
        serviceInstanceRepository.deleteAll(
                serviceInstanceRepository.findByServiceId(serviceId));
        versionRepository.deleteAll(versionRepository.findByServiceId(serviceId));
        configRepository.deleteAll(configRepository.findByServiceId(serviceId));
        repository.delete(entity);
    }

    /**
     * 子服务名 slug：小写 + 非 [a-z0-9-] 一律替换为 '-'，折叠连续 '-'，去首尾 '-'。
     * 空结果兜底 "svc"。结果长度截断 128（path_key 列上限）。
     */
    public static String slugify(String name) {
        if (name == null) {
            return "svc";
        }
        String s = name.toLowerCase().replaceAll("[^a-z0-9-]+", "-").replaceAll("-{2,}", "-");
        while (s.startsWith("-")) {
            s = s.substring(1);
        }
        while (s.endsWith("-")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            s = "svc";
        }
        return s.length() > 128 ? s.substring(0, 128) : s;
    }

    // ---------- 路径 / 单元名 解析（纯函数，便于单测） ----------

    /**
     * 部署 workdir：默认子服务（pathKey 空）→ {@code /opt/deploy/{project}/{versionId}}；
     * 非默认子服务 → {@code /opt/deploy/{project}/{pathKey}/{versionId}}。
     */
    public static String workdir(String projectName, String pathKey, long versionId) {
        if (pathKey == null || pathKey.isBlank()) {
            return "/opt/deploy/" + projectName + "/" + versionId;
        }
        return "/opt/deploy/" + projectName + "/" + pathKey + "/" + versionId;
    }

    /**
     * systemd 单元名：默认子服务 → {@code deploy-{project}.service}；
     * 非默认子服务 → {@code deploy-{project}-{pathKey}.service}。
     */
    public static String unitName(String projectName, String pathKey) {
        if (pathKey == null || pathKey.isBlank()) {
            return "deploy-" + projectName + ".service";
        }
        return "deploy-" + projectName + "-" + pathKey + ".service";
    }

    /** nginx 站点配置路径（非默认子服务追加 -{pathKey}，避免与默认子服务站点冲突）。 */
    public static String nginxConfPath(String projectName, String pathKey) {
        if (pathKey == null || pathKey.isBlank()) {
            return "/etc/nginx/conf.d/deploy-" + projectName + ".conf";
        }
        return "/etc/nginx/conf.d/deploy-" + projectName + "-" + pathKey + ".conf";
    }

    /** nginx 站点软链路径（非默认子服务落到 {project}/{pathKey}/current，与 workdir 同层）。 */
    public static String nginxSymlinkPath(String projectName, String pathKey) {
        if (pathKey == null || pathKey.isBlank()) {
            return "/opt/deploy/" + projectName + "/current";
        }
        return "/opt/deploy/" + projectName + "/" + pathKey + "/current";
    }
}
