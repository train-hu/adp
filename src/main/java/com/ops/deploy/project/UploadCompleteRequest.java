package com.ops.deploy.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 分片上传完成请求。
 *
 * <p>sha256 可空：为空时以 init 阶段声明值为准（init 也未声明则跳过比对）；
 * 服务端始终以合并后实算的 SHA-256 落库。
 */
public class UploadCompleteRequest {

    @NotBlank
    @Size(max = 36)
    private String sessionId;

    /** 整包 SHA-256（64 位 hex）；为空回退 init 声明值。 */
    @Size(max = 64)
    private String sha256;

    /** 版本说明（可选，≤255 字符，与整包上传一致）。 */
    @Size(max = 255)
    private String versionNote;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public String getVersionNote() {
        return versionNote;
    }

    public void setVersionNote(String versionNote) {
        this.versionNote = versionNote;
    }
}
