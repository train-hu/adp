package com.ops.deploy.server;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建服务器请求。password 为必填明文凭证（服务端加密后存储，响应不回传）。
 * toString 中密码打码，避免审计 detail（方法参数摘要）泄露明文。
 */
public class ServerCreateRequest {

    @NotBlank
    @Size(max = 64)
    private String name;

    @NotBlank
    @Size(max = 64)
    private String ip;

    @Min(1)
    @Max(65535)
    private Integer sshPort;

    @NotBlank
    @Size(max = 64)
    private String sshUser;

    @Size(max = 16)
    private String authType;

    @NotBlank
    private String password;

    @Size(max = 16)
    private String envTag;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public Integer getSshPort() {
        return sshPort;
    }

    public void setSshPort(Integer sshPort) {
        this.sshPort = sshPort;
    }

    public String getSshUser() {
        return sshUser;
    }

    public void setSshUser(String sshUser) {
        this.sshUser = sshUser;
    }

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getEnvTag() {
        return envTag;
    }

    public void setEnvTag(String envTag) {
        this.envTag = envTag;
    }

    @Override
    public String toString() {
        return "ServerCreateRequest{name=" + name
                + ", ip=" + ip + ", sshPort=" + sshPort
                + ", sshUser=" + sshUser + ", authType=" + authType
                + ", password=***" + ", envTag=" + envTag + "}";
    }
}
