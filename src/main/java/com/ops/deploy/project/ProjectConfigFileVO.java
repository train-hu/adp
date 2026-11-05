package com.ops.deploy.project;

import java.time.LocalDateTime;

/**
 * 项目配置文件视图对象（含全文 content，供编辑器回显与保存）。
 */
public class ProjectConfigFileVO {

    private Long id;
    private Long projectId;
    private Long serviceId;
    private String fileKey;
    private String content;
    private String remotePath;
    private String warning;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ProjectConfigFileVO from(ProjectConfigFileEntity e) {
        ProjectConfigFileVO vo = new ProjectConfigFileVO();
        vo.id = e.getId();
        vo.projectId = e.getProjectId();
        vo.serviceId = e.getServiceId();
        vo.fileKey = e.getFileKey();
        vo.content = e.getContent();
        vo.remotePath = e.getRemotePath();
        vo.warning = e.getWarning();
        vo.createdAt = e.getCreatedAt();
        vo.updatedAt = e.getUpdatedAt();
        return vo;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getServiceId() {
        return serviceId;
    }

    public void setServiceId(Long serviceId) {
        this.serviceId = serviceId;
    }

    public String getFileKey() {
        return fileKey;
    }

    public void setFileKey(String fileKey) {
        this.fileKey = fileKey;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getRemotePath() {
        return remotePath;
    }

    public void setRemotePath(String remotePath) {
        this.remotePath = remotePath;
    }

    public String getWarning() {
        return warning;
    }

    public void setWarning(String warning) {
        this.warning = warning;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
