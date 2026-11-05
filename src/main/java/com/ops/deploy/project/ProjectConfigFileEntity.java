package com.ops.deploy.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 项目配置文件（nginx / docker-compose / .env 等），映射 project_config_file 表。
 *
 * <p>同一项目下 file_key 唯一（(project_id, file_key) 唯一约束，upsert 语义）。
 * content 为文件全文（TEXT）；remote_path 为提示性目标路径（实际下发路径由
 * 部署执行器按 file_key 决定，此列仅用于前端展示约定）。
 */
@Entity
@Table(name = "project_config_file")
public class ProjectConfigFileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 所属服务（project_service.id）。NULL/0 视为项目默认服务。 */
    @Column(name = "service_id", nullable = false)
    private Long serviceId = 0L;

    @Column(name = "file_key", nullable = false, length = 512)
    private String fileKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "remote_path", length = 512)
    private String remotePath;

    @Column(columnDefinition = "TEXT")
    private String warning;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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
