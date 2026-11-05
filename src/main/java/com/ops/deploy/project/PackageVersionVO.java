package com.ops.deploy.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

/**
 * 部署包版本对外视图对象。
 *
 * <p>detectedType 为识别类型摘要（从 detected_json 解析的 detectedType 字段），
 * detectedJson 为识别结果原始 JSON 字符串（前端可再解析完整 evidence/port 等）。
 */
public class PackageVersionVO {

    /** 解析 detected_json 取 detectedType（JSON 异常/缺字段时返回 null）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private Long id;
    private Long projectId;
    private Long serviceId;
    private String fileName;
    private String storagePath;
    private String sha256;
    private Long sizeBytes;
    private String versionNote;
    private String detectedType;
    private String detectedJson;
    private String gitUrl;
    private String gitRef;
    private String gitCommit;
    private Long uploadedBy;
    private LocalDateTime createdAt;

    public static PackageVersionVO from(PackageVersionEntity entity) {
        PackageVersionVO vo = new PackageVersionVO();
        vo.id = entity.getId();
        vo.projectId = entity.getProjectId();
        vo.serviceId = entity.getServiceId();
        vo.fileName = entity.getFileName();
        vo.storagePath = entity.getStoragePath();
        vo.sha256 = entity.getSha256();
        vo.sizeBytes = entity.getSizeBytes();
        vo.versionNote = entity.getVersionNote();
        vo.detectedJson = entity.getDetectedJson();
        vo.detectedType = parseDetectedType(entity.getDetectedJson());
        vo.gitUrl = entity.getGitUrl();
        vo.gitRef = entity.getGitRef();
        vo.gitCommit = entity.getGitCommit();
        vo.uploadedBy = entity.getUploadedBy();
        vo.createdAt = entity.getCreatedAt();
        return vo;
    }

    /** 从识别结果 JSON 字符串解析 detectedType；解析失败/缺字段返回 null。 */
    private static String parseDetectedType(String detectedJson) {
        if (detectedJson == null || detectedJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(detectedJson);
            JsonNode type = node.get("detectedType");
            return (type != null && !type.isNull()) ? type.asText() : null;
        } catch (Exception e) {
            return null;
        }
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

    public String getDetectedType() {
        return detectedType;
    }

    public void setDetectedType(String detectedType) {
        this.detectedType = detectedType;
    }

    public String getDetectedJson() {
        return detectedJson;
    }

    public void setDetectedJson(String detectedJson) {
        this.detectedJson = detectedJson;
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
