package com.ops.deploy.security;

import jakarta.validation.constraints.NotNull;

/**
 * 资源（服务器/数据库）分配请求：userId 为被分配的目标用户。
 */
public class ResourceGrantRequest {

    @NotNull
    private Long userId;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }
}
