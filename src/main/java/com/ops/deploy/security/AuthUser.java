package com.ops.deploy.security;

/**
 * 已认证用户身份（由 JwtAuthFilter 注入 request attribute，也可通过 @CurrentUser 解析）。
 */
public class AuthUser {

    private final Long userId;
    private final String username;
    private final String role;

    public AuthUser(Long userId, String username, String role) {
        this.userId = userId;
        this.username = username;
        this.role = role;
    }

    public Long getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getRole() {
        return role;
    }
}
