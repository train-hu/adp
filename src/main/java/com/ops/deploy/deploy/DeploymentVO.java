package com.ops.deploy.deploy;

import java.time.LocalDateTime;

/**
 * 部署任务对外视图对象。
 */
public class DeploymentVO {

    private Long id;
    private Long projectId;
    private Long serviceId;
    private Long versionId;
    private Long serverId;
    private String status;
    private String mode;
    private String startCmd;
    private Integer port;
    private String healthPath;
    private String workdir;
    private Long oldVersionId;
    private Long databaseId;
    private Long operator;
    private String errorSummary;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public static DeploymentVO from(DeploymentEntity e) {
        DeploymentVO vo = new DeploymentVO();
        vo.id = e.getId();
        vo.projectId = e.getProjectId();
        vo.serviceId = e.getServiceId();
        vo.versionId = e.getVersionId();
        vo.serverId = e.getServerId();
        vo.status = e.getStatus();
        vo.mode = e.getMode();
        vo.startCmd = e.getStartCmd();
        vo.port = e.getPort();
        vo.healthPath = e.getHealthPath();
        vo.workdir = e.getWorkdir();
        vo.oldVersionId = e.getOldVersionId();
        vo.databaseId = e.getDatabaseId();
        vo.operator = e.getOperator();
        vo.errorSummary = e.getErrorSummary();
        vo.createdAt = e.getCreatedAt();
        vo.startedAt = e.getStartedAt();
        vo.finishedAt = e.getFinishedAt();
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

    public Long getVersionId() {
        return versionId;
    }

    public void setVersionId(Long versionId) {
        this.versionId = versionId;
    }

    public Long getServerId() {
        return serverId;
    }

    public void setServerId(Long serverId) {
        this.serverId = serverId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getStartCmd() {
        return startCmd;
    }

    public void setStartCmd(String startCmd) {
        this.startCmd = startCmd;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getHealthPath() {
        return healthPath;
    }

    public void setHealthPath(String healthPath) {
        this.healthPath = healthPath;
    }

    public String getWorkdir() {
        return workdir;
    }

    public void setWorkdir(String workdir) {
        this.workdir = workdir;
    }

    public Long getOldVersionId() {
        return oldVersionId;
    }

    public void setOldVersionId(Long oldVersionId) {
        this.oldVersionId = oldVersionId;
    }

    public Long getDatabaseId() {
        return databaseId;
    }

    public void setDatabaseId(Long databaseId) {
        this.databaseId = databaseId;
    }

    public Long getOperator() {
        return operator;
    }

    public void setOperator(Long operator) {
        this.operator = operator;
    }

    public String getErrorSummary() {
        return errorSummary;
    }

    public void setErrorSummary(String errorSummary) {
        this.errorSummary = errorSummary;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }
}
