package com.ops.deploy.project;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 子服务配置文件服务：列表 / 保存(upsert) / 删除。
 *
 * <p>fileKey 与 (service_id) 的唯一约束保证 upsert 语义（同服务同 key 重复保存覆盖）。
 * 配置按子服务作用域存储：同一项目下不同子服务可各自持有同 fileKey 的配置互不覆盖。
 * 项目删除时由 {@link ProjectService#delete} 级联清理（本服务提供 deleteByProject 供其调用）。
 */
@Service
public class ProjectConfigFileService {

    private final ProjectConfigFileRepository configRepository;
    private final ProjectSubServiceManager subServiceManager;

    public ProjectConfigFileService(ProjectConfigFileRepository configRepository,
                                    ProjectSubServiceManager subServiceManager) {
        this.configRepository = configRepository;
        this.subServiceManager = subServiceManager;
    }

    /**
     * 子服务下的配置列表。
     *
     * @param projectId 所属项目（用于校验 / 解析子服务归属）
     * @param serviceId 子服务 id；null/0 = 项目默认子服务
     */
    public List<ProjectConfigFileVO> list(Long projectId, Long serviceId) {
        Long resolvedServiceId = subServiceManager.resolve(projectId, serviceId).getId();
        return configRepository.findByServiceIdOrderByFileKeyAsc(resolvedServiceId)
                .stream()
                .map(ProjectConfigFileVO::from)
                .collect(Collectors.toList());
    }

    /** upsert：同 (service, fileKey) 存在则覆盖 content/remotePath，否则新建。 */
    @Transactional
    public ProjectConfigFileVO upsert(Long serviceId, ProjectConfigFileRequest req) {
        ProjectSubServiceEntity subService = subServiceManager.resolveEntity(serviceId);
        ProjectConfigFileEntity entity = configRepository
                .findByServiceIdAndFileKey(serviceId, req.getFileKey())
                .orElseGet(() -> {
                    ProjectConfigFileEntity e = new ProjectConfigFileEntity();
                    e.setProjectId(subService.getProjectId());
                    e.setServiceId(serviceId);
                    e.setFileKey(req.getFileKey());
                    e.setCreatedAt(LocalDateTime.now());
                    return e;
                });
        entity.setContent(req.getContent());
        if (req.getRemotePath() != null) {
            entity.setRemotePath(req.getRemotePath());
        }
        // 可用性校验：不通过不阻断保存（入库并带 warning 告警，前端展示提示）
        entity.setWarning(ConfigFileValidator.validate(req.getFileKey(), req.getContent()));
        entity.setUpdatedAt(LocalDateTime.now());
        ProjectConfigFileEntity saved = configRepository.save(entity);
        return ProjectConfigFileVO.from(saved);
    }

    @Transactional
    public void delete(Long serviceId, String fileKey) {
        subServiceManager.resolveEntity(serviceId);
        ProjectConfigFileEntity entity = configRepository
                .findByServiceIdAndFileKey(serviceId, fileKey)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "config file not found: " + fileKey));
        configRepository.delete(entity);
    }

    /** 供 ProjectService.delete 级联清理（best-effort，DB 外键 ON DELETE CASCADE 兜底）。 */
    @Transactional
    public void deleteByProject(Long projectId) {
        configRepository.deleteByProjectId(projectId);
    }
}
