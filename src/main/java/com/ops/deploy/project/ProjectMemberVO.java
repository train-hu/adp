package com.ops.deploy.project;

import java.time.LocalDateTime;

/**
 * 项目成员视图（用户名解析后）。
 */
public class ProjectMemberVO {

    private Long userId;
    private String username;
    private LocalDateTime joinedAt;

    public static ProjectMemberVO from(ProjectMemberEntity entity, String username) {
        ProjectMemberVO vo = new ProjectMemberVO();
        vo.userId = entity.getUserId();
        vo.username = username;
        vo.joinedAt = entity.getJoinedAt();
        return vo;
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

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(LocalDateTime joinedAt) {
        this.joinedAt = joinedAt;
    }
}
