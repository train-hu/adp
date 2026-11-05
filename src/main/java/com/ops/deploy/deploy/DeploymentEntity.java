package com.ops.deploy.deploy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 部署任务，映射 deployment 表。
 *
 * <p>status 以 String 存储（{@link DeploymentStatus#name()}，不用 JPA 枚举映射，
 * 与 {@code ServerEntity.authType} 同模式）。mode 固定 'PROCESS'（M1 进程模式）。
 * oldVersionId 为回滚目标（部署前该 (project, server) 上 online 实例指向的版本）。
 */
@Entity
@Table(name = "deployment")
public class DeploymentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 所属服务（project_service.id）。NULL/0 视为项目默认服务。 */
    @Column(name = "service_id", nullable = false)
    private Long serviceId = 0L;

    @Column(name = "version_id", nullable = false)
    private Long versionId;

    @Column(name = "server_id", nullable = false)
    private Long serverId;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(nullable = false, length = 16)
    private String mode = "PROCESS";

    @Column(name = "start_cmd", length = 512)
    private String startCmd;

    @Column
    private Integer port;

    @Column(name = "health_path", length = 128)
    private String healthPath;

    @Column(length = 255)
    private String workdir;

    @Column(name = "old_version_id")
    private Long oldVersionId;

    /** 关联数据库（已登记或自动创建的项目独立库）；可空 = 该部署不使用数据库。 */
    @Column(name = "database_id")
    private Long databaseId;

    @Column
    private Long operator;

    @Column(name = "error_summary", length = 1024)
    private String errorSummary;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

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
