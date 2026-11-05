package com.ops.deploy.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PackageVersionRepository extends JpaRepository<PackageVersionEntity, Long> {

    /** 项目下的版本列表，按创建时间倒序。 */
    List<PackageVersionEntity> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    /** 服务下的版本列表，按创建时间倒序。 */
    List<PackageVersionEntity> findByServiceIdOrderByCreatedAtDesc(Long serviceId);

    /** 同项目下按内容指纹查版本（兼容旧逻辑）。 */
    Optional<PackageVersionEntity> findByProjectIdAndSha256(Long projectId, String sha256);

    /** 同服务下按内容指纹查版本（上传去重，scope 收窄到服务级）。 */
    Optional<PackageVersionEntity> findByServiceIdAndSha256(Long serviceId, String sha256);

    /** 服务下全部版本（删除子服务时级联清理用）。 */
    List<PackageVersionEntity> findByServiceId(Long serviceId);

    /** 该用户是否上传过任一包版本（删除用户前引用检查用）。 */
    boolean existsByUploadedBy(Long uploadedBy);
}
