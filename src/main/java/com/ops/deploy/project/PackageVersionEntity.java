package com.ops.deploy.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 部署包版本，映射 package_version 表。
 *
 * <p>detectedJson 以 String 存储 JSONB 列（识别结果的 JSON 文本，读回原样返回给前端）。
 * sha256 为包内容指纹（64 位小写 hex），同项目下 sha256 唯一（业务层去重）。
 * storagePath 为相对 deploy.data-dir 的路径（packages/{projectId}/{versionId}-{sha12}/{fileName}）。
 */
@Entity
@Table(name = "package_version")
public class PackageVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 所属服务（project_service.id）。NULL/0 视为项目默认服务。 */
    @Column(name = "service_id", nullable = false)
    private Long serviceId = 0L;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "storage_path", nullable = false, length = 512)
    private String storagePath;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "version_note", length = 255)
    private String versionNote;

    /** git 来源版本的仓库地址（上传文件版本为空）。 */
    @Column(name = "git_url", length = 512)
    private String gitUrl;

    /** git 来源版本的分支/标签（空 = 默认分支 HEAD）。 */
    @Column(name = "git_ref", length = 255)
    private String gitRef;

    /** git 来源版本冻结的 commit SHA（40 位小写 hex，部署复用该次 clone 的 zip）。 */
    @Column(name = "git_commit", length = 40)
    private String gitCommit;

    /** 私有仓库凭证密文（username:password，AES-256-GCM，VO 不回传）。 */
    @Column(name = "git_auth_cipher")
    private byte[] gitAuthCipher;

    /**
     * 识别结果的 JSON 文本（合法 JSON，读回原样返回给前端）。
     * DDL 中 detected_json 为可空 JSONB 列；String 存合法 JSON 文本，
     * {@code @JdbcTypeCode(SqlTypes.JSON)} 使 Hibernate validate 模式与 jsonb 列类型对齐
     * （避免纯 String 映射在 ddl-auto=validate 下与 jsonb 列产生类型不匹配的运行时风险）。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detected_json", columnDefinition = "jsonb")
    private String detectedJson;

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

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

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getVersionNote() {
        return versionNote;
    }

    public void setVersionNote(String versionNote) {
        this.versionNote = versionNote;
    }

    public String getGitUrl() {
        return gitUrl;
    }

    public void setGitUrl(String gitUrl) {
        this.gitUrl = gitUrl;
    }

    public String getGitRef() {
        return gitRef;
    }

    public void setGitRef(String gitRef) {
        this.gitRef = gitRef;
    }

    public String getGitCommit() {
        return gitCommit;
    }

    public void setGitCommit(String gitCommit) {
        this.gitCommit = gitCommit;
    }

    public byte[] getGitAuthCipher() {
        return gitAuthCipher;
    }

    public void setGitAuthCipher(byte[] gitAuthCipher) {
        this.gitAuthCipher = gitAuthCipher;
    }

    public String getDetectedJson() {
        return detectedJson;
    }

    public void setDetectedJson(String detectedJson) {
        this.detectedJson = detectedJson;
    }

    public Long getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(Long uploadedBy) {
        this.uploadedBy = uploadedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
