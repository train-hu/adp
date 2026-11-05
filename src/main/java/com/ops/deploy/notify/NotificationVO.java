package com.ops.deploy.notify;

import java.time.LocalDateTime;

/**
 * 站内通知视图（通知实体 → 前端）。
 */
public class NotificationVO {

    private Long id;
    private String level;
    private String title;
    private String message;
    private Long projectId;
    private Long instanceId;
    private LocalDateTime createdAt;

    public static NotificationVO from(NotificationEntity e) {
        NotificationVO vo = new NotificationVO();
        vo.id = e.getId();
        vo.level = e.getLevel();
        vo.title = e.getTitle();
        vo.message = e.getMessage();
        vo.projectId = e.getProjectId();
        vo.instanceId = e.getInstanceId();
        vo.createdAt = e.getCreatedAt();
        return vo;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(Long instanceId) {
        this.instanceId = instanceId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
