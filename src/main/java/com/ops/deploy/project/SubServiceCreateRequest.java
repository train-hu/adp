package com.ops.deploy.project;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 子服务创建请求。
 *
 * <p>startCmd/port 为服务级部署参数（PROCESS 模式启动命令、部署/compose 宿主机端口）：
 * 多服务项目创建服务时必填启动命令；存量单服务项目可缺省（走 project 默认值/识别值）。
 */
public class SubServiceCreateRequest {

    @NotBlank
    @Size(max = 64)
    private String name;

    /** 服务级启动命令（多服务项目必填；单服务项目可缺省）。 */
    @Size(max = 512)
    private String startCmd;

    /** 服务级端口（部署/compose 宿主机映射优先）。 */
    @Min(1)
    @Max(65535)
    private Integer port;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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
}
