package com.ops.deploy.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * AI 自主部署方案，映射 ai_deployment_plan 表（M2 AI 能力，FR-02/FR-05）。
 *
 * <p>AI 以 ReAct 循环（推理-行动-观察）解析部署包 + 只读探测目标机，产出结构化部署方案
 * （项目类型 / mode / 启动命令 / 端口 / 健康检查），全程轨迹落库可回放、可审计。
 * 方案需人工确认（可编辑）后才由 {@link com.ops.deploy.deploy.DeploymentService#create}
 * 既有安全管线执行。status 生命周期：DRAFT（待确认）→ CONFIRMED（已确认/执行中）
 * → EXECUTED（已生成部署任务）/ ABANDONED（已放弃）。source ∈ {AI, RULE}（LLM 不可用时降级规则引擎）。
 */
@Entity
@Table(name = "ai_deployment_plan")
public class AiDeploymentPlanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "version_id", nullable = false)
    private Long versionId;

    @Column(name = "server_id", nullable = false)
    private Long serverId;

    /** DRAFT / CONFIRMED / EXECUTED / ABANDONED。 */
    @Column(nullable = false, length = 16)
    private String status = "DRAFT";

    /** 方案来源：AI（LLM 生成）/ RULE（规则引擎降级）。 */
    @Column(nullable = false, length = 16)
    private String source = "AI";

    @Column(name = "detected_type", length = 32)
    private String detectedType;

    @Column(name = "build_tool", length = 32)
    private String buildTool;

    @Column(nullable = false, length = 16)
    private String mode = "PROCESS";

    @Column(name = "start_cmd")
    private String startCmd;

    @Column
    private Integer port;

    @Column(name = "health_path", length = 256)
    private String healthPath = "/";

    /** AI 推理说明（为何如此部署），供人工审阅。 */
    @Column
    private String reasoning;

    /** ReAct 轨迹（每步 LLM 输入/工具调用/结果），JSON 数组。 */
    @Column(name = "trajectory_json")
    private String trajectoryJson;

    /** 完整方案快照（含命令预览），JSON。 */
    @Column(name = "plan_json")
    private String planJson;

    /** 确认执行后关联生成的部署任务 id。 */
    @Column(name = "deployment_id")
    private Long deploymentId;

    @Column(name = "error_summary")
    private String errorSummary;

    @Column(length = 64)
    private String operator;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

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

    public Long getVersionId() {
        return versionId;
    }

    public void setVersionId(Long versionId) {
        this.versionId = versionId;
    }

    public Long getServerId() {
        return serverId;
    }

    public void setServerId(Long serverId) {
        this.serverId = serverId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getDetectedType() {
        return detectedType;
    }

    public void setDetectedType(String detectedType) {
        this.detectedType = detectedType;
    }

    public String getBuildTool() {
        return buildTool;
    }

    public void setBuildTool(String buildTool) {
        this.buildTool = buildTool;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getStartCmd() {
        return startCmd;
    }

    public void setStartCmd(String startCmd) {
        this.startCmd = startCmd;
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

    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }

    public String getTrajectoryJson() {
        return trajectoryJson;
    }

    public void setTrajectoryJson(String trajectoryJson) {
        this.trajectoryJson = trajectoryJson;
    }

    public String getPlanJson() {
        return planJson;
    }

    public void setPlanJson(String planJson) {
        this.planJson = planJson;
    }

    public Long getDeploymentId() {
        return deploymentId;
    }

    public void setDeploymentId(Long deploymentId) {
        this.deploymentId = deploymentId;
    }

    public String getErrorSummary() {
        return errorSummary;
    }

    public void setErrorSummary(String errorSummary) {
        this.errorSummary = errorSummary;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
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

    public LocalDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(LocalDateTime confirmedAt) {
        this.confirmedAt = confirmedAt;
    }
}
