package com.ops.deploy.project;

import jakarta.validation.constraints.Size;

/**
 * 部署包 git 来源参数（上传部署包支持填写 git 地址，平台侧 clone 打包）。
 *
 * <p>与上传文件二选一：file 与 gitUrl 均提供或均未提供 → BAD_REQUEST。
 * gitUsername/gitPassword 用于私有仓库（服务端 AES-256-GCM 加密存储，响应不回传）。
 * toString 中密码打码，避免审计 detail（方法参数摘要）泄露明文。
 */
public class PackageGitSource {

    @Size(max = 512)
    private String gitUrl;

    @Size(max = 255)
    private String gitRef;

    @Size(max = 255)
    private String gitUsername;

    @Size(max = 255)
    private String gitPassword;

    public String getGitUrl() {
        return gitUrl;
    }

    public void setGitUrl(String gitUrl) {
        this.gitUrl = gitUrl;
    }

    public String getGitRef() {
        return gitRef;
    }

    public void setGitRef(String gitRef) {
        this.gitRef = gitRef;
    }

    public String getGitUsername() {
        return gitUsername;
    }

    public void setGitUsername(String gitUsername) {
        this.gitUsername = gitUsername;
    }

    public String getGitPassword() {
        return gitPassword;
    }

    public void setGitPassword(String gitPassword) {
        this.gitPassword = gitPassword;
    }

    @Override
    public String toString() {
        return "PackageGitSource{gitUrl=" + gitUrl
                + ", gitRef=" + gitRef
                + ", gitUsername=" + gitUsername
                + ", gitPassword=***" + "}";
    }
}
