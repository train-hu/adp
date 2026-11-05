package com.ops.deploy.deploy;

import java.time.LocalDateTime;

/**
 * 部署文档对外视图对象（Task 8）。
 *
 * <p>deploymentId 关联 deployment.id；type 为 "deploy"（部署文档）/ "maintain"（维护文档）；
 * content 为对应文档的 Markdown 全文；updatedAt 为最近一次生成/重新生成的时间。
 */
public class DeploymentDocumentVO {

    private Long deploymentId;
    private String type;
    private String content;
    private LocalDateTime updatedAt;

    public Long getDeploymentId() {
        return deploymentId;
    }

    public void setDeploymentId(Long deploymentId) {
        this.deploymentId = deploymentId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
