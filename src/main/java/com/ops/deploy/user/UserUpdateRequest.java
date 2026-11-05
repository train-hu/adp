package com.ops.deploy.user;

import jakarta.validation.constraints.Size;

/**
 * 更新用户请求。所有字段可空，null/空 = 不修改：
 * - role：ADMIN/OPS（最后一个 ADMIN 降级 → CONFLICT）；
 * - password：新密码（BCrypt 存储），空 = 保留原密码；
 * - enabled：启用/禁用（禁用最后一个启用中的 ADMIN → CONFLICT）。
 * toString 中密码打码，避免审计 detail 泄露明文。
 */
public class UserUpdateRequest {

    @Size(max = 16)
    private String role;

    @Size(min = 6, max = 64)
    private String password;

    private Boolean enabled;

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String toString() {
        return "UserUpdateRequest{role=" + role
                + ", password=***" + ", enabled=" + enabled + "}";
    }
}
