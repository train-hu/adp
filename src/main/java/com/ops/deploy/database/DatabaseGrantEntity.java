package com.ops.deploy.database;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 数据库资源按用户分配，映射 database_grant 表（联合主键 database_id + user_id，
 * 由 {@link DatabaseGrantId} 承载）。
 *
 * <p>可见性语义与服务器一致：OPS 可见 = 任一 OPS 创建的数据库 ∪ 被分配给自己的数据库；
 * 双向 ON DELETE CASCADE（删数据库/删用户自动清理，见 V13 迁移）。
 * 见 {@link com.ops.deploy.security.ResourceAccessService}。
 */
@Entity
@Table(name = "database_grant")
@IdClass(DatabaseGrantId.class)
public class DatabaseGrantEntity {

    @Id
    @Column(name = "database_id")
    private Long databaseId;

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "granted_at", nullable = false)
    private LocalDateTime grantedAt;

    public Long getDatabaseId() {
        return databaseId;
    }

    public void setDatabaseId(Long databaseId) {
        this.databaseId = databaseId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public LocalDateTime getGrantedAt() {
        return grantedAt;
    }

    public void setGrantedAt(LocalDateTime grantedAt) {
        this.grantedAt = grantedAt;
    }
}
