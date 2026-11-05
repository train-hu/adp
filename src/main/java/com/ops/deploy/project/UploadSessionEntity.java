package com.ops.deploy.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 分片上传会话，映射 upload_session 表。
 *
 * <p>id 为 UUID 字符串（前端持有）；分片本体存
 * {@code {data-dir}/.uploads/{sessionId}/{index}.part}，不入库。
 * status: UPLOADING / COMPLETED / FAILED。
 */
@Entity
@Table(name = "upload_session")
public class UploadSessionEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 所属子服务（project_sub_service.id）；0 = 项目默认服务。init 时落库，complete 读取。 */
    @Column(name = "service_id", nullable = false)
    private Long serviceId = 0L;

    @Column(nullable = false, length = 255)
    private String filename;

    @Column(name = "total_size", nullable = false)
    private Long totalSize;

    @Column(name = "chunk_size", nullable = false)
    private Long chunkSize;

    @Column(name = "total_chunks", nullable = false)
    private Integer totalChunks;

    @Column(length = 64)
    private String sha256;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
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

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public Long getTotalSize() {
        return totalSize;
    }

    public void setTotalSize(Long totalSize) {
        this.totalSize = totalSize;
    }

    public Long getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(Long chunkSize) {
        this.chunkSize = chunkSize;
    }

    public Integer getTotalChunks() {
        return totalChunks;
    }

    public void setTotalChunks(Integer totalChunks) {
        this.totalChunks = totalChunks;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
