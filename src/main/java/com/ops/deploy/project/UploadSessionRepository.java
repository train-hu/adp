package com.ops.deploy.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UploadSessionRepository extends JpaRepository<UploadSessionEntity, String> {

    /** 项目下的会话（按创建时间倒序），供清理/排查。 */
    List<UploadSessionEntity> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    Optional<UploadSessionEntity> findByIdAndProjectId(String id, Long projectId);

    /** 非 COMPLETED 且创建早于 cutoff 的会话（定时清理过期上传用）。 */
    List<UploadSessionEntity> findByStatusNotAndCreatedAtBefore(String status, LocalDateTime cutoff);
}
