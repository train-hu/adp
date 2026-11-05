package com.ops.deploy.server;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 服务器资源按用户分配，映射 server_grant 表（联合主键 server_id + user_id，
 * 由 {@link ServerGrantId} 承载）。
 *
 * <p>可见性语义：OPS 可见 = 任一 OPS 创建的资源 ∪ 被分配给自己的资源；
 * 双向 ON DELETE CASCADE（删服务器/删用户自动清理，见 V13 迁移）。
 * 见 {@link com.ops.deploy.security.ResourceAccessService}。
 */
@Entity
@Table(name = "server_grant")
@IdClass(ServerGrantId.class)
public class ServerGrantEntity {

    @Id
    @Column(name = "server_id")
    private Long serverId;

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "granted_at", nullable = false)
    private LocalDateTime grantedAt;

    public Long getServerId() {
        return serverId;
    }

    public void setServerId(Long serverId) {
        this.serverId = serverId;
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
