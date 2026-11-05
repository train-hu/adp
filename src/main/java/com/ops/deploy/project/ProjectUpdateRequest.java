package com.ops.deploy.project;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 更新项目请求。全部字段可空 = 不修改。
 * detectedType 可手动覆盖（识别值或人工指定），取值白名单与 TypeDetector 产出对齐
 * （7 个合法值 + 空串保持既有"可清空"语义，null 表示不修改）。
 */
public class ProjectUpdateRequest {

    @Size(max = 255)
    private String description;

    @Size(max = 32)
    @Pattern(regexp = "JAVA_MAVEN|JAVA_GRADLE|NODE|PYTHON|STATIC_WEB|DOCKER|UNKNOWN|",
            message = "detectedType must be one of JAVA_MAVEN/JAVA_GRADLE/NODE/PYTHON/STATIC_WEB/DOCKER/UNKNOWN")
    private String detectedType;

    @Size(max = 512)
    @Pattern(regexp = "^[^\\n\\r]*$")
    private String defaultStartCmd;

    @Min(1)
    @Max(65535)
    private Integer defaultPort;

    @Size(max = 128)
    @Pattern(regexp = "^/[A-Za-z0-9._/*-]*$")
    private String defaultHealthPath;

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

    @Override
    public String toString() {
        return "ProjectUpdateRequest{description=" + description
                + ", detectedType=" + detectedType
                + ", defaultStartCmd=" + defaultStartCmd
                + ", defaultPort=" + defaultPort
                + ", defaultHealthPath=" + defaultHealthPath + "}";
    }
}
