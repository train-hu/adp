package com.ops.deploy.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 项目成员，映射 project_member 表（联合主键 project_id + user_id，
 * 由 {@link ProjectMemberId} 承载）。
 *
 * <p>项目内无角色分级：成员即可完全管理该项目（见 {@link ProjectAccessService}）。
 * 隔离语义：ADMIN 全局角色绕过成员校验，非成员对项目不可见。
 */
@Entity
@Table(name = "project_member")
@IdClass(ProjectMemberId.class)
public class ProjectMemberEntity {

    @Id
    @Column(name = "project_id")
    private Long projectId;

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(LocalDateTime joinedAt) {
        this.joinedAt = joinedAt;
    }
}
