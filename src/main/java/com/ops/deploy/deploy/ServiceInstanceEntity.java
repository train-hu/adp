package com.ops.deploy.deploy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 服务实例，映射 service_instance 表（name 全局唯一，如 {project}-{server}-{versionId}）。
 *
 * <p>online=true 表示当前 (project, server) 上生效的实例；同维度新实例上线时旧实例
 * 置 online=false、status='STOPPED'。status 以 String 存储（RUNNING/STOPPED/FAILED）。
 */
@Entity
@Table(name = "service_instance")
public class ServiceInstanceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 128)
    private String name;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 所属服务（project_service.id）。NULL/0 视为项目默认服务。 */
    @Column(name = "service_id", nullable = false)
    private Long serviceId = 0L;

    @Column(name = "server_id", nullable = false)
    private Long serverId;

    @Column(name = "deployment_id")
    private Long deploymentId;

    @Column(name = "unit_name", length = 128)
    private String unitName;

    @Column(nullable = false, length = 16)
    private String mode = "PROCESS";

    @Column(length = 255)
    private String workdir;

    @Column
    private Integer port;

    @Column(name = "health_path", length = 128)
    private String healthPath;

    @Column(nullable = false, length = 16)
    private String status = "STOPPED";

    @Column(nullable = false)
    private Boolean online = Boolean.TRUE;

    @Column(name = "deployed_at")
    private LocalDateTime deployedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public Long getServerId() {
        return serverId;
    }

    public void setServerId(Long serverId) {
        this.serverId = serverId;
    }

    public Long getDeploymentId() {
        return deploymentId;
    }

    public void setDeploymentId(Long deploymentId) {
        this.deploymentId = deploymentId;
    }

    public String getUnitName() {
        return unitName;
    }

    public void setUnitName(String unitName) {
        this.unitName = unitName;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getWorkdir() {
        return workdir;
    }

    public void setWorkdir(String workdir) {
        this.workdir = workdir;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Boolean getOnline() {
        return online;
    }

    public void setOnline(Boolean online) {
        this.online = online;
    }

    public LocalDateTime getDeployedAt() {
        return deployedAt;
    }

    public void setDeployedAt(LocalDateTime deployedAt) {
        this.deployedAt = deployedAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
