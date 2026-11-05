package com.ops.deploy.project;

import java.time.LocalDateTime;

/**
 * 项目对外视图对象。
 */
public class ProjectVO {

    private Long id;
    private String name;
    private String description;
    private String detectedType;
    private String defaultStartCmd;
    private Integer defaultPort;
    private String defaultHealthPath;
    /** 多服务项目（无默认服务，服务/启动命令/端口由用户手动创建）。 */
    private boolean multiService;
    private LocalDateTime createdAt;
    /** 当前请求用户是否该项目成员（非 ADMIN 恒为 true，ADMIN 为 null 表示不过滤）。 */
    private Boolean isMember;

    public static ProjectVO from(ProjectEntity entity) {
        ProjectVO vo = new ProjectVO();
        vo.id = entity.getId();
        vo.name = entity.getName();
        vo.description = entity.getDescription();
        vo.detectedType = entity.getDetectedType();
        vo.defaultStartCmd = entity.getDefaultStartCmd();
        vo.defaultPort = entity.getDefaultPort();
        vo.defaultHealthPath = entity.getDefaultHealthPath();
        vo.multiService = entity.isMultiService();
        vo.createdAt = entity.getCreatedAt();
        return vo;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDetectedType() {
        return detectedType;
    }

    public void setDetectedType(String detectedType) {
        this.detectedType = detectedType;
    }

    public String getDefaultStartCmd() {
        return defaultStartCmd;
    }

    public void setDefaultStartCmd(String defaultStartCmd) {
        this.defaultStartCmd = defaultStartCmd;
    }

    public Integer getDefaultPort() {
        return defaultPort;
    }

    public void setDefaultPort(Integer defaultPort) {
        this.defaultPort = defaultPort;
    }

    public String getDefaultHealthPath() {
        return defaultHealthPath;
    }

    public void setDefaultHealthPath(String defaultHealthPath) {
        this.defaultHealthPath = defaultHealthPath;
    }

    public boolean isMultiService() {
        return multiService;
    }

    public void setMultiService(boolean multiService) {
        this.multiService = multiService;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Boolean getIsMember() {
        return isMember;
    }

    public void setIsMember(Boolean isMember) {
        this.isMember = isMember;
    }
}
