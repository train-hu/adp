package com.ops.deploy.security;

import java.time.LocalDateTime;

/**
 * 资源分配视图（服务器/数据库按用户分配，用户名解析后）。
 */
public class ResourceGrantVO {

    /** SERVER / DATABASE（{@link ResourceAccessService.ResourceType} name）。 */
    private String resourceType;
    private Long resourceId;
    private Long userId;
    private String username;
    private String userRole;
    private LocalDateTime grantedAt;

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public void setResourceId(Long resourceId) {
        this.resourceId = resourceId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getUserRole() {
        return userRole;
    }

    public void setUserRole(String userRole) {
        this.userRole = userRole;
    }

    public LocalDateTime getGrantedAt() {
        return grantedAt;
    }

    public void setGrantedAt(LocalDateTime grantedAt) {
        this.grantedAt = grantedAt;
    }
}
