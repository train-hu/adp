package com.ops.deploy.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 部署项目，映射 project 表。
 *
 * <p>detectedType 以 String 存储（对应识别类型的 name，如 JAVA_MAVEN/NODE/...，与
 * {@code ServerEntity.authType} 同模式）。defaultPort/defaultStartCmd/defaultHealthPath
 * 为项目级部署默认值，可被部署时覆盖。createdBy 关联 app_user.id。
 */
@Entity
@Table(name = "project")
public class ProjectEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String name;

    @Column(length = 255)
    private String description;

    @Column(name = "detected_type", length = 32)
    private String detectedType;

    @Column(name = "default_start_cmd", length = 512)
    private String defaultStartCmd;

    @Column(name = "default_port")
    private Integer defaultPort;

    @Column(name = "default_health_path", length = 128)
    private String defaultHealthPath;

    /**
     * 多服务项目标记：true 时创建项目不自动建默认子服务（启动命令/端口在创建服务时填写）；
     * false（存量项目默认）保持旧行为：V19 回填/懒建一个 path_key=NULL 的默认服务。
     */
    @Column(name = "multi_service", nullable = false)
    private boolean multiService;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDetectedType() {
        return detectedType;
    }

    public void setDetectedType(String detectedType) {
        this.detectedType = detectedType;
    }

    public String getDefaultStartCmd() {
        return defaultStartCmd;
    }

    public void setDefaultStartCmd(String defaultStartCmd) {
        this.defaultStartCmd = defaultStartCmd;
    }

    public Integer getDefaultPort() {
        return defaultPort;
    }

    public void setDefaultPort(Integer defaultPort) {
        this.defaultPort = defaultPort;
    }

    public String getDefaultHealthPath() {
        return defaultHealthPath;
    }

    public void setDefaultHealthPath(String defaultHealthPath) {
        this.defaultHealthPath = defaultHealthPath;
    }

    public boolean isMultiService() {
        return multiService;
    }

    public void setMultiService(boolean multiService) {
        this.multiService = multiService;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
