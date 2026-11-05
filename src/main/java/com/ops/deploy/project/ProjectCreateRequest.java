package com.ops.deploy.project;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建项目请求。name 必填且 64 字符内（UNIQUE）。
 */
public class ProjectCreateRequest {

    @NotBlank
    @Size(max = 64)
    private String name;

    @Size(max = 255)
    private String description;

    /** 默认端口（可选，部署时 req.port 缺省的兜底值）。 */
    @Min(1)
    @Max(65535)
    private Integer defaultPort;

    /** 多服务项目：不自动创建默认服务，启动命令/端口在创建服务时填写。 */
    private boolean multiService;

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

    public Integer getDefaultPort() {
        return defaultPort;
    }

    public void setDefaultPort(Integer defaultPort) {
        this.defaultPort = defaultPort;
    }

    public boolean isMultiService() {
        return multiService;
    }

    public void setMultiService(boolean multiService) {
        this.multiService = multiService;
    }

    @Override
    public String toString() {
        return "ProjectCreateRequest{name=" + name + ", description=" + description
                + ", defaultPort=" + defaultPort + ", multiService=" + multiService + "}";
    }
}
