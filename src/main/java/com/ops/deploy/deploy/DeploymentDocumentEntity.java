package com.ops.deploy.deploy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 部署文档，映射 deployment_document 表（deployment_id 唯一）。
 *
 * <p>每次部署成功后自动生成部署/维护两份 Markdown 全文（M1 不引入 MinIO，
 * 直接以 TEXT 存库）。regenerate 时更新两份全文并刷新 updated_at。
 */
@Entity
@Table(name = "deployment_document")
public class DeploymentDocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "deployment_id", nullable = false, unique = true)
    private Long deploymentId;

    @Column(name = "deploy_markdown", columnDefinition = "text")
    private String deployMarkdown;

    @Column(name = "maintain_markdown", columnDefinition = "text")
    private String maintainMarkdown;

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

    public Long getDeploymentId() {
        return deploymentId;
    }

    public void setDeploymentId(Long deploymentId) {
        this.deploymentId = deploymentId;
    }

    public String getDeployMarkdown() {
        return deployMarkdown;
    }

    public void setDeployMarkdown(String deployMarkdown) {
        this.deployMarkdown = deployMarkdown;
    }

    public String getMaintainMarkdown() {
        return maintainMarkdown;
    }

    public void setMaintainMarkdown(String maintainMarkdown) {
        this.maintainMarkdown = maintainMarkdown;
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
