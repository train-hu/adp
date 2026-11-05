package com.ops.deploy.ssh;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;

import java.util.regex.Pattern;

/**
 * SSH 远端路径形态校验（纯函数）。
 *
 * <p>背景：{@code shellQuote} 放行 {@code /}，若 taskName/remoteDir 含 {@code ..} 段或
 * 任意路径片段，会让 {@code mkdir -p} / SFTP 在目标机创建任意目录、把文件写到预期
 * {@code .deploy} 目录外。command 本身是 AI/用户 shell 片段（平台特性，不做约束），
 * 但 taskName/fileName 不是命令、remoteDir 应强制绝对路径，此处收口校验。
 *
 * <p>校验失败抛 {@code BizException(BAD_REQUEST)}。
 */
public final class SshPathRules {

    /** 文件名形态：字母/数字/点/下划线/连字符（不含 {@code /}，天然排除 {@code ..} 路径穿越）。 */
    private static final Pattern FILE_NAME = Pattern.compile("^[A-Za-z0-9._-]+$");

    private SshPathRules() {
    }

    /** taskName 用于拼状态/日志文件名，必须符合文件名形态（不含 {@code /} 与 {@code ..}）。 */
    public static void validateTaskName(String taskName) {
        if (taskName == null || !FILE_NAME.matcher(taskName).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "invalid task name: " + taskName);
        }
    }

    /** fileName 用于 SFTP 上传/重命名，必须符合文件名形态（不含 {@code /}，天然排除路径穿越）。 */
    public static void validateFileName(String fileName) {
        if (fileName == null || !FILE_NAME.matcher(fileName).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "invalid file name: " + fileName);
        }
    }

    /**
     * remoteDir 必须为绝对路径且不含 {@code ..} 段。
     * 根路径 {@code "/"} 允许；{@code "/a..b"}（连续点号非 {@code ..} 段）允许。
     */
    public static void validateRemoteDir(String remoteDir) {
        if (remoteDir == null || !remoteDir.startsWith("/")) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "remote dir must be an absolute path: " + remoteDir);
        }
        for (String seg : remoteDir.split("/")) {
            if ("..".equals(seg)) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "remote dir must not contain '..' segment: " + remoteDir);
            }
        }
    }
}
