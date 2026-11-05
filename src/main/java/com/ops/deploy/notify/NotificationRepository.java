package com.ops.deploy.notify;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {

    /** 未读通知，按创建时间倒序，限 N 条（前端铃铛列表）。 */
    List<NotificationEntity> findTop50ByReadAtIsNullOrderByCreatedAtDesc();

    /** 未读数量（前端角标）。 */
    long countByReadAtIsNull();

    /** 全部未读标记为已读。 */
    @Modifying
    @Query("update NotificationEntity n set n.readAt = :now where n.readAt is null")
    int markAllRead(@Param("now") java.time.LocalDateTime now);

    /** 单条标记已读（点击通知时）。 */
    @Modifying
    @Query("update NotificationEntity n set n.readAt = :now where n.id = :id and n.readAt is null")
    int markRead(@Param("id") Long id, @Param("now") java.time.LocalDateTime now);

    /** 批量删除创建早于 cutoff 且已读的通知（数据保留清理用），返回删除行数。 */
    @Modifying
    @Query("delete from NotificationEntity n where n.createdAt < :cutoff and n.readAt is not null")
    int deleteBefore(@Param("cutoff") java.time.LocalDateTime cutoff);
}
