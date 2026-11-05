package com.ops.deploy.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/**
 * 审计记录仓储。
 */
public interface AuditRepository extends JpaRepository<AuditLog, Long> {

    /** 批量删除创建早于 cutoff 的审计记录（数据保留清理用），返回删除行数。 */
    @Modifying
    @Query("delete from AuditLog a where a.createdAt < :cutoff")
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff);
}
