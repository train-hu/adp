package com.ops.deploy.project;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 分片上传会话创建请求。
 *
 * <p>chunkSize 范围（[1MB, 128MB]）与 totalSize 上限由 ChunkUploadService 校验
 * （依赖 deploy.max-upload-size 配置，DTO 层无法表达）；此处只做基础约束。
 */
public class UploadInitRequest {

    @NotBlank
    @Size(max = 255)
    private String fileName;

    @NotNull
    @Min(1)
    private Long totalSize;

    @NotNull
    @Min(1048576)
    private Long chunkSize;

    /** 前端计算的整包 SHA-256（64 位 hex）；可空（空则 complete 时跳过比对）。 */
    @Pattern(regexp = "^[0-9a-fA-F]{64}$", message = "sha256 must be 64 hex chars")
    private String sha256;

    /** 目标子服务 id；null/0 = 项目默认子服务（init 时解析并落库到 upload_session）。 */
    private Long serviceId;

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
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

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public Long getServiceId() {
        return serviceId;
    }

    public void setServiceId(Long serviceId) {
        this.serviceId = serviceId;
    }
}
