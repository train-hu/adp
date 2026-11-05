package com.ops.deploy.ssh;

/**
 * 后台长任务状态。
 *
 * <p>state 取值：RUNNING / DONE / FAILED / UNKNOWN。
 * exitCode 在 RUNNING/UNKNOWN 时为 null。
 */
public final class BackgroundStatus {

    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String UNKNOWN = "UNKNOWN";

    private final String state;
    private final Integer exitCode;

    private BackgroundStatus(String state, Integer exitCode) {
        this.state = state;
        this.exitCode = exitCode;
    }

    public static BackgroundStatus of(String state, Integer exitCode) {
        return new BackgroundStatus(state, exitCode);
    }

    public String getState() {
        return state;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public boolean isRunning() {
        return RUNNING.equals(state);
    }

    public boolean isFinished() {
        return DONE.equals(state) || FAILED.equals(state);
    }

    @Override
    public String toString() {
        return "BackgroundStatus{state='" + state + "', exitCode=" + exitCode + "}";
    }
}
