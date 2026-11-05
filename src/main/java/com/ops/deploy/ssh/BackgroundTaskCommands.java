package com.ops.deploy.ssh;

import java.util.regex.Pattern;

/**
 * 后台长任务命令构造（纯函数，可单测）。
 *
 * <p>生成的 shell 命令语义（整体形态 {@code nohup sh -c '<wrapper>' > /dev/null 2>&1 &}）：
 * <ol>
 *   <li>整个 wrapper（cd、写 RUNNING、wait、写终态）都包在 {@code sh -c} 里并由
 *       外层 {@code nohup} 启动 —— 负责 wait/写终态的进程本身免疫 SIGHUP，
 *       避免 sshd 会话清理时外层子 shell 被杀导致状态文件永远停在 RUNNING；</li>
 *   <li>内层命令 {@code nohup {command} >> {logFile} 2>&1} 双保险（命令进程自身也忽略 SIGHUP），
 *       日志追加不覆盖；</li>
 *   <li>先写 {@code RUNNING} 状态文件，命令结束按退出码写 {@code DONE:<code>} /
 *       {@code FAILED:<code>}；</li>
 *   <li>末尾 {@code &} 使 SSH exec 通道立即返回（不阻塞会话）。</li>
 * </ol>
 *
 * <p>内层命令片段由远端 shell 解释（不加引号）；所有路径经
 * {@link #shellQuote} 单引号转义（含 {@code '} 的路径以 {@code '\''} 转义，
 * 在外层 {@code sh -c '...'} 内同样成立）。
 *
 * <p>状态文件格式：{@code RUNNING} / {@code DONE:<exitCode>} / {@code FAILED:<exitCode>}，
 * 由 {@link #parseStatus(String)} 解析。
 */
public final class BackgroundTaskCommands {

    private static final Pattern DONE = Pattern.compile("DONE:(\\d+)");
    private static final Pattern FAILED = Pattern.compile("FAILED:(\\d+)");

    private BackgroundTaskCommands() {
    }

    /**
     * 构造 nohup 包裹的后台任务命令。所有路径做单引号转义（路径含空格/单引号/特殊字符安全）。
     */
    public static String buildNohupCommand(String workdir, String command,
                                           String logFile, String statusFile) {
        String wd = shellQuote(workdir);
        String lf = shellQuote(logFile);
        String sf = shellQuote(statusFile);
        // 命令本身不加引号（它本身是 shell 片段，需由远端 shell 直接解释）
        String wrapper = "cd " + wd
                + " && echo RUNNING > " + sf
                + " && nohup " + command + " >> " + lf + " 2>&1 & pid=$!;"
                + " wait $pid; code=$?;"
                + " if [ $code -eq 0 ]; then echo DONE:$code > " + sf
                + "; else echo FAILED:$code > " + sf + "; fi";
        // 外层 nohup 保护负责写终态的 wrapper 进程本身（免疫 SIGHUP）；
        // nohup 自身输出丢弃（真实输出全部进日志/状态文件）
        return "nohup sh -c '" + wrapper + "' > /dev/null 2>&1 &";
    }

    /**
     * 解析状态文件内容。
     * "DONE:0"→(DONE,0)、"FAILED:1"→(FAILED,1)、"RUNNING"→(RUNNING,null)、
     * 空/乱串→(UNKNOWN,null)。
     */
    public static BackgroundStatus parseStatus(String fileContent) {
        if (fileContent == null) {
            return BackgroundStatus.of(BackgroundStatus.UNKNOWN, null);
        }
        String content = fileContent.strip();
        if (content.isEmpty()) {
            return BackgroundStatus.of(BackgroundStatus.UNKNOWN, null);
        }
        if (content.equals("RUNNING")) {
            return BackgroundStatus.of(BackgroundStatus.RUNNING, null);
        }
        var m = DONE.matcher(content);
        if (m.matches()) {
            return BackgroundStatus.of(BackgroundStatus.DONE, Integer.valueOf(m.group(1)));
        }
        m = FAILED.matcher(content);
        if (m.matches()) {
            return BackgroundStatus.of(BackgroundStatus.FAILED, Integer.valueOf(m.group(1)));
        }
        return BackgroundStatus.of(BackgroundStatus.UNKNOWN, null);
    }

    /** shell 单引号转义：' → '\''（POSIX 兼容）。null/空串返回空串。 */
    static String shellQuote(String s) {
        if (s == null || s.isEmpty()) {
            return "''";
        }
        if (s.indexOf('\'') < 0 && s.chars().allMatch(c -> Character.isLetterOrDigit(c)
                || "/._-".indexOf(c) >= 0)) {
            // 无特殊字符 → 原样返回（更可读）
            return s;
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
