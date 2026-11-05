package com.ops.deploy.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 项目下的子服务（project_sub_service 表）。一个项目可拆分为多个独立部署的服务
 * （如 categraf 项目下既部署 categraf 主服务，也部署另一个 agent）。
 *
 * <p>每个项目自动持有一条"默认子服务"（name=项目名，path_key=NULL）：
 * 存量数据（包版本 / 配置文件 / 部署 / 服务实例）全部挂在默认子服务名下，
 * 且默认子服务的 workdir / unit 沿用旧口径（{@code /opt/deploy/{项目}/{版本}}、
 * {@code deploy-{项目}.service}），保证线上已部署实例完全兼容。
 *
 * <p>新建的非默认子服务 path_key=slug(name)（非空）→ 走新口径：
 * workdir={@code /opt/deploy/{项目}/{pathKey}/{版本}}、
 * unit={@code deploy-{项目}-{pathKey}.service}，
 * 与同项目其它子服务的磁盘目录 / systemd 单元天然隔离，互不覆盖。
 *
 * <p>(project_id, name) 唯一；同项目下子服务名不可重复。
 */
@Entity
@Table(name = "project_sub_service")
public class ProjectSubServiceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 子服务名（同项目内唯一）。默认子服务 name=项目名。 */
    @Column(nullable = false, length = 64)
    private String name;

    /**
     * 非默认子服务的目录/单元后缀（slug，如 "agent"）。NULL = 默认子服务，
     * 沿用旧 workdir/unit 口径；非空 → 新路径/新 unit。
     */
    @Column(name = "path_key", length = 128)
    private String pathKey;

    /**
     * 服务级启动命令（PROCESS 模式部署时优先于 project.defaultStartCmd；
     * 存量默认服务为 null → 走 project 默认/识别值旧口径）。
     */
    @Column(name = "start_cmd", length = 512)
    private String startCmd;

    /** 服务级端口（部署/compose 宿主机映射优先于请求端口；存量默认服务为 null）。 */
    @Column(name = "port")
    private Integer port;

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

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPathKey() {
        return pathKey;
    }

    public void setPathKey(String pathKey) {
        this.pathKey = pathKey;
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

    /** 是否默认子服务（path_key 为 NULL/空 即默认）。 */
    public boolean isDefault() {
        return pathKey == null || pathKey.isBlank();
    }
}
