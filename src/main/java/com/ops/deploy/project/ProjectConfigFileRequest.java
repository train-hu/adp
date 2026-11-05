package com.ops.deploy.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 保存/更新项目配置文件请求。
 *
 * <p>fileKey 白名单：预设四个（nginx / compose / env / dockerfile），也允许自定义
 * 相对路径（小写字母/数字/中划线/点/斜杠，防注入与超长）。PROCESS 模式下若 fileKey
 * 为相对路径（含 {@code /}），部署时解压后按该相对路径覆盖包内文件，实现
 * "包内文件按环境覆盖"。content 上限 1MB（应用层兜底）。
 */
public class ProjectConfigFileRequest {

    @NotBlank(message = "fileKey is required")
    @Size(max = 512)
    @Pattern(regexp = "^[a-z0-9][a-z0-9._/-]*$", message = "fileKey must match ^[a-z0-9][a-z0-9._/-]*$")
    private String fileKey;

    @NotNull(message = "content is required")
    @Size(max = 1048576)
    private String content;

    /** 可选：目标机上的提示路径（仅供展示，实际下发路径由执行器决定）。 */
    @Size(max = 512)
    @Pattern(regexp = "^[^\\n\\r]*$")
    private String remotePath;

    public String getFileKey() {
        return fileKey;
    }

    public void setFileKey(String fileKey) {
        this.fileKey = fileKey;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getRemotePath() {
        return remotePath;
    }

    public void setRemotePath(String remotePath) {
        this.remotePath = remotePath;
    }
}
