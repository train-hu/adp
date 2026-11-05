package com.ops.deploy.monitor;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 监控指标汇总视图（GET /api/monitor/summary，任何已登录角色可看，
 * 部署/实例口径与仪表盘一致按可见项目隔离；JVM/HTTP 为平台自身指标不隔离）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MonitorStatsVO {

    /** 近 30 天部署总数（按创建时间）。 */
    private long deploymentTotal30d;
    /** 近 30 天 SUCCESS 数。 */
    private long deploymentSuccess30d;
    /** 近 30 天 FAILED 数。 */
    private long deploymentFailed30d;
    /** 近 30 天终态部署的平均耗时（秒，startedAt→finishedAt 实算）。 */
    private Double avgDurationSeconds;
    /** 近 30 天逐日趋势（按创建日期升序）。 */
    private List<DailyTrend> trend;

    /** JVM 摘要（平台后端进程自身）。 */
    private JvmSummary jvm;
    /** HTTP 请求摘要（Micrometer http_server_requests 累计值）。 */
    private HttpSummary http;

    /** 服务实例健康（按可见项目隔离）。 */
    private long instanceTotal;
    private long instanceRunning;
    private long instanceStopped;
    private List<InstanceHealth> instances;

    /** 逐日趋势：total 总部署数 / success 成功数 / failed 失败数。 */
    public static class DailyTrend {
        private String day;
        private long total;
        private long success;
        private long failed;

        public String getDay() { return day; }
        public void setDay(String day) { this.day = day; }
        public long getTotal() { return total; }
        public void setTotal(long total) { this.total = total; }
        public long getSuccess() { return success; }
        public void setSuccess(long success) { this.success = success; }
        public long getFailed() { return failed; }
        public void setFailed(long failed) { this.failed = failed; }
    }

    /** JVM 摘要：堆内存/非堆内存/线程数。 */
    public static class JvmSummary {
        /** 已用堆内存（字节）。 */
        private long heapUsedBytes;
        /** 最大堆内存（字节，-1 表示未设上限）。 */
        private long heapMaxBytes;
        /** 已用非堆内存（字节）。 */
        private long nonHeapUsedBytes;
        /** 存活线程数。 */
        private long threadCount;
        /** 守护线程数。 */
        private long daemonThreadCount;
        /** 峰值线程数。 */
        private long peakThreadCount;

        public long getHeapUsedBytes() { return heapUsedBytes; }
        public void setHeapUsedBytes(long heapUsedBytes) { this.heapUsedBytes = heapUsedBytes; }
        public long getHeapMaxBytes() { return heapMaxBytes; }
        public void setHeapMaxBytes(long heapMaxBytes) { this.heapMaxBytes = heapMaxBytes; }
        public long getNonHeapUsedBytes() { return nonHeapUsedBytes; }
        public void setNonHeapUsedBytes(long nonHeapUsedBytes) { this.nonHeapUsedBytes = nonHeapUsedBytes; }
        public long getThreadCount() { return threadCount; }
        public void setThreadCount(long threadCount) { this.threadCount = threadCount; }
        public long getDaemonThreadCount() { return daemonThreadCount; }
        public void setDaemonThreadCount(long daemonThreadCount) { this.daemonThreadCount = daemonThreadCount; }
        public long getPeakThreadCount() { return peakThreadCount; }
        public void setPeakThreadCount(long peakThreadCount) { this.peakThreadCount = peakThreadCount; }
    }

    /** HTTP 请求摘要：累计请求数 / 5xx 数 / 总耗时（秒）。 */
    public static class HttpSummary {
        private long totalRequests;
        private long serverErrors;
        private Double totalDurationSeconds;

        public long getTotalRequests() { return totalRequests; }
        public void setTotalRequests(long totalRequests) { this.totalRequests = totalRequests; }
        public long getServerErrors() { return serverErrors; }
        public void setServerErrors(long serverErrors) { this.serverErrors = serverErrors; }
        public Double getTotalDurationSeconds() { return totalDurationSeconds; }
        public void setTotalDurationSeconds(Double totalDurationSeconds) { this.totalDurationSeconds = totalDurationSeconds; }
    }

    /** 服务实例健康条目（online=true 的为当前生效实例）。 */
    public static class InstanceHealth {
        private Long id;
        private String name;
        private Long projectId;
        private Long serverId;
        /** 目标机 ip（用于前端展示，缺失时前端兜底显示 serverId）。 */
        private String serverIp;
        private String status;
        private Boolean online;
        private LocalDateTime deployedAt;
        private LocalDateTime updatedAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Long getProjectId() { return projectId; }
        public void setProjectId(Long projectId) { this.projectId = projectId; }
        public Long getServerId() { return serverId; }
        public void setServerId(Long serverId) { this.serverId = serverId; }
        public String getServerIp() { return serverIp; }
        public void setServerIp(String serverIp) { this.serverIp = serverIp; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Boolean getOnline() { return online; }
        public void setOnline(Boolean online) { this.online = online; }
        public LocalDateTime getDeployedAt() { return deployedAt; }
        public void setDeployedAt(LocalDateTime deployedAt) { this.deployedAt = deployedAt; }
        public LocalDateTime getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    }

    public long getDeploymentTotal30d() { return deploymentTotal30d; }
    public void setDeploymentTotal30d(long deploymentTotal30d) { this.deploymentTotal30d = deploymentTotal30d; }
    public long getDeploymentSuccess30d() { return deploymentSuccess30d; }
    public void setDeploymentSuccess30d(long deploymentSuccess30d) { this.deploymentSuccess30d = deploymentSuccess30d; }
    public long getDeploymentFailed30d() { return deploymentFailed30d; }
    public void setDeploymentFailed30d(long deploymentFailed30d) { this.deploymentFailed30d = deploymentFailed30d; }
    public Double getAvgDurationSeconds() { return avgDurationSeconds; }
    public void setAvgDurationSeconds(Double avgDurationSeconds) { this.avgDurationSeconds = avgDurationSeconds; }
    public List<DailyTrend> getTrend() { return trend; }
    public void setTrend(List<DailyTrend> trend) { this.trend = trend; }
    public JvmSummary getJvm() { return jvm; }
    public void setJvm(JvmSummary jvm) { this.jvm = jvm; }
    public HttpSummary getHttp() { return http; }
    public void setHttp(HttpSummary http) { this.http = http; }
    public long getInstanceTotal() { return instanceTotal; }
    public void setInstanceTotal(long instanceTotal) { this.instanceTotal = instanceTotal; }
    public long getInstanceRunning() { return instanceRunning; }
    public void setInstanceRunning(long instanceRunning) { this.instanceRunning = instanceRunning; }
    public long getInstanceStopped() { return instanceStopped; }
    public void setInstanceStopped(long instanceStopped) { this.instanceStopped = instanceStopped; }
    public List<InstanceHealth> getInstances() { return instances; }
    public void setInstances(List<InstanceHealth> instances) { this.instances = instances; }
}
