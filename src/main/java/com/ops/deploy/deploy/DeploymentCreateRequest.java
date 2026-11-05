package com.ops.deploy.deploy;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建部署任务请求：versionId / serverId 必填；
 * startCmd / port / healthPath 可选（三级兜底：请求 &gt; project 默认 &gt; detected 建议）。
 */
public class DeploymentCreateRequest {

    @NotNull(message = "versionId is required")
    private Long versionId;

    @NotNull(message = "serverId is required")
    private Long serverId;

    /** 可选覆盖：启动命令（不得含换行，防 heredoc 逃逸）。 */
    @Size(max = 512)
    @Pattern(regexp = "^[^\\n\\r]*$")
    private String startCmd;

    /** 可选覆盖：部署方式（PROCESS/NGINX/DOCKER）；留空按 project 类型 → 识别结果推导。 */
    @Pattern(regexp = "PROCESS|NGINX|DOCKER")
    private String mode;

    /** 可选覆盖：服务端口。 */
    private Integer port;

    /** 可选覆盖：健康检查路径（默认 "/"；必须是合法 URL 路径，防 curl 命令注入）。 */
    @Size(max = 128)
    @Pattern(regexp = "^/[A-Za-z0-9._/*-]*$")
    private String healthPath;

    /**
     * 可选：使用的数据库 id（{@code database_info}）。
     * <p>null/0 = 不使用数据库；{@code -1} = 自动创建项目独立 MySQL 库（平台 SSH 到目标机
     * 起 mysql 容器并自动登记）；其余正值 = 引用已登记的数据库。
     */
    private Long databaseId;

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

    public String getStartCmd() {
        return startCmd;
    }

    public void setStartCmd(String startCmd) {
        this.startCmd = startCmd;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
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

    public Long getDatabaseId() {
        return databaseId;
    }

    public void setDatabaseId(Long databaseId) {
        this.databaseId = databaseId;
    }
}
