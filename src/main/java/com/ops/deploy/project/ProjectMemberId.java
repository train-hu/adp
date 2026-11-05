package com.ops.deploy.project;

import java.io.Serializable;

/**
 * 项目成员联合主键载体（project_id + user_id），供 {@link ProjectMemberEntity}
 * 的 @IdClass 与 {@link ProjectMemberRepository} 的复合主键查询使用。
 */
public class ProjectMemberId implements Serializable {

    private Long projectId;
    private Long userId;

    public ProjectMemberId() {
    }

    public ProjectMemberId(Long projectId, Long userId) {
        this.projectId = projectId;
        this.userId = userId;
    }

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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProjectMemberId)) {
            return false;
        }
        ProjectMemberId that = (ProjectMemberId) o;
        return projectId != null && projectId.equals(that.projectId)
                && userId != null && userId.equals(that.userId);
    }

    @Override
    public int hashCode() {
        return (projectId == null ? 0 : projectId.hashCode())
                * 31 + (userId == null ? 0 : userId.hashCode());
    }
}
