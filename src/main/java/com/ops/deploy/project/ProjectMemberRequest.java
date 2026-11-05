package com.ops.deploy.project;

import jakarta.validation.constraints.NotNull;

/**
 * 项目成员添加请求：userId 目标用户。项目内无角色分级，成员即可完全管理项目。
 */
public class ProjectMemberRequest {

    @NotNull
    private Long userId;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }
}
