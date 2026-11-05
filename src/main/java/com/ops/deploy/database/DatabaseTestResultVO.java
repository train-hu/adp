package com.ops.deploy.database;

/**
 * 连通性测试（TCP 端口探测）结果。
 */
public class DatabaseTestResultVO {

    private boolean reachable;
    private long latencyMs;
    private String status;

    public boolean isReachable() {
        return reachable;
    }

    public void setReachable(boolean reachable) {
        this.reachable = reachable;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
