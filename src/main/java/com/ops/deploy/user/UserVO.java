package com.ops.deploy.user;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户视图。不含密码（hash 与明文均不外露）；
 * projectIds 为该用户已分配（作为成员）的项目 id 列表，供用户管理页展示。
 */
public class UserVO {

    private Long id;
    private String username;
    private String role;
    private boolean enabled;
    private LocalDateTime createdAt;
    private List<Long> projectIds;

    public static UserVO from(UserEntity entity) {
        UserVO vo = new UserVO();
        vo.id = entity.getId();
        vo.username = entity.getUsername();
        vo.role = entity.getRole();
        vo.enabled = entity.isEnabled();
        vo.createdAt = entity.getCreatedAt();
        vo.projectIds = List.of();
        return vo;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public List<Long> getProjectIds() {
        return projectIds;
    }

    public void setProjectIds(List<Long> projectIds) {
        this.projectIds = projectIds;
    }
}
