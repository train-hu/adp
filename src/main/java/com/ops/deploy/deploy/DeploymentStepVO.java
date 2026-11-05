package com.ops.deploy.deploy;

import java.time.LocalDateTime;

/**
 * 部署步骤对外视图对象。detail 为 JSONB 列的 JSON 文本（原样返回）。
 */
public class DeploymentStepVO {

    private Long id;
    private Long deploymentId;
    private Integer seq;
    private String name;
    private String status;
    private String detail;
    private String logText;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public static DeploymentStepVO from(DeploymentStepEntity e) {
        DeploymentStepVO vo = new DeploymentStepVO();
        vo.id = e.getId();
        vo.deploymentId = e.getDeploymentId();
        vo.seq = e.getSeq();
        vo.name = e.getName();
        vo.status = e.getStatus();
        vo.detail = e.getDetail();
        vo.logText = e.getLogText();
        vo.startedAt = e.getStartedAt();
        vo.finishedAt = e.getFinishedAt();
        return vo;
    }

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

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getLogText() {
        return logText;
    }

    public void setLogText(String logText) {
        this.logText = logText;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }
}
