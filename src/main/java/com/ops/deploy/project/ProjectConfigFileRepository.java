package com.ops.deploy.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectConfigFileRepository extends JpaRepository<ProjectConfigFileEntity, Long> {

    List<ProjectConfigFileEntity> findByProjectIdOrderByFileKeyAsc(Long projectId);

    List<ProjectConfigFileEntity> findByProjectId(Long projectId);

    Optional<ProjectConfigFileEntity> findByProjectIdAndFileKey(Long projectId, String fileKey);

    /** 服务下的配置列表，按 fileKey 排序。 */
    List<ProjectConfigFileEntity> findByServiceIdOrderByFileKeyAsc(Long serviceId);

    /** 服务下全部配置（按序，方案 C 覆盖用，不需要排序也可）。 */
    List<ProjectConfigFileEntity> findByServiceId(Long serviceId);

    /** 服务内按 fileKey 精确查（服务作用域 upsert / 删除用）。 */
    Optional<ProjectConfigFileEntity> findByServiceIdAndFileKey(Long serviceId, String fileKey);

    void deleteByProjectId(Long projectId);
}
