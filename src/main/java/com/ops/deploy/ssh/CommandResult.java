package com.ops.deploy.ssh;

/**
 * 命令执行结果值对象。
 *
 * <p>工厂方法语义：
 * <ul>
 *   <li>{@link #ok} — 命令正常结束（exitCode 可为非 0，表示命令自身失败）</li>
 *   <li>{@link #timeout} — 超时被中断，timedOut=true，exitCode=-1</li>
 *   <li>{@link #fail} — 非超时的失败路径（保留给未来扩展，如协议层错误）</li>
 * </ul>
 */
public final class CommandResult {

    public static final int TIMEOUT_EXIT_CODE = -1;

    private final int exitCode;
    private final String stdout;
    private final String stderr;
    private final boolean timedOut;
    private final long durationMs;

    private CommandResult(int exitCode, String stdout, String stderr, boolean timedOut, long durationMs) {
        this.exitCode = exitCode;
        this.stdout = stdout;
        this.stderr = stderr;
        this.timedOut = timedOut;
        this.durationMs = durationMs;
    }

    public static CommandResult ok(String stdout, int exitCode) {
        return new CommandResult(exitCode, stdout, "", false, 0L);
    }

    /** 带真实耗时的成功结果（生产通道实现使用；0 耗时版本供测试/fake 使用）。 */
    public static CommandResult ok(String stdout, int exitCode, long durationMs) {
        return new CommandResult(exitCode, stdout, "", false, durationMs);
    }

    public static CommandResult timeout(long durationMs) {
        return new CommandResult(TIMEOUT_EXIT_CODE, "", "", true, durationMs);
    }

    public static CommandResult fail(int exitCode, String stdout, String stderr) {
        return new CommandResult(exitCode, stdout, stderr, false, 0L);
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getStdout() {
        return stdout;
    }

    public String getStderr() {
        return stderr;
    }

    public boolean getTimedOut() {
        return timedOut;
    }

    public long getDurationMs() {
        return durationMs;
    }

    @Override
    public String toString() {
        return "CommandResult{exitCode=" + exitCode + ", timedOut=" + timedOut
                + ", durationMs=" + durationMs + ", stdoutLen=" + (stdout == null ? 0 : stdout.length())
                + ", stderrLen=" + (stderr == null ? 0 : stderr.length()) + "}";
    }
}
