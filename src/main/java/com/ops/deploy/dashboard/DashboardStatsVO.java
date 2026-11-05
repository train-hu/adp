package com.ops.deploy.dashboard;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 仪表盘统计数据（GET /api/dashboard 响应体）。
 */
public class DashboardStatsVO {

    /** 30 天内创建的部署数。 */
    private long deploymentTotal30d;
    /** 30 天内 SUCCESS 的部署数。 */
    private long deploymentSuccess30d;
    /** 服务实例总数。 */
    private long instanceTotal;
    /** 运行中（RUNNING）实例数。 */
    private long instanceRunning;
    /** 非运行中实例数。 */
    private long instanceStopped;
    /** 服务器总数。 */
    private long serverTotal;
    /** 数据库总数。 */
    private long databaseTotal;
    /** 最近 5 条非 SUCCESS 终态部署（创建时间倒序）。 */
    private List<RecentFailure> recentFailures;

    public long getDeploymentTotal30d() {
        return deploymentTotal30d;
    }

    public void setDeploymentTotal30d(long deploymentTotal30d) {
        this.deploymentTotal30d = deploymentTotal30d;
    }

    public long getDeploymentSuccess30d() {
        return deploymentSuccess30d;
    }

    public void setDeploymentSuccess30d(long deploymentSuccess30d) {
        this.deploymentSuccess30d = deploymentSuccess30d;
    }

    public long getInstanceTotal() {
        return instanceTotal;
    }

    public void setInstanceTotal(long instanceTotal) {
        this.instanceTotal = instanceTotal;
    }

    public long getInstanceRunning() {
        return instanceRunning;
    }

    public void setInstanceRunning(long instanceRunning) {
        this.instanceRunning = instanceRunning;
    }

    public long getInstanceStopped() {
        return instanceStopped;
    }

    public void setInstanceStopped(long instanceStopped) {
        this.instanceStopped = instanceStopped;
    }

    public long getServerTotal() {
        return serverTotal;
    }

    public void setServerTotal(long serverTotal) {
        this.serverTotal = serverTotal;
    }

    public long getDatabaseTotal() {
        return databaseTotal;
    }

    public void setDatabaseTotal(long databaseTotal) {
        this.databaseTotal = databaseTotal;
    }

    public List<RecentFailure> getRecentFailures() {
        return recentFailures;
    }

    public void setRecentFailures(List<RecentFailure> recentFailures) {
        this.recentFailures = recentFailures;
    }

    /** 最近失败部署条目。 */
    public static class RecentFailure {
        private Long id;
        private Long projectId;
        private String projectName;
        private Long serverId;
        private String status;
        private LocalDateTime finishedAt;
        private String errorSummary;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public Long getProjectId() {
            return projectId;
        }

        public void setProjectId(Long projectId) {
            this.projectId = projectId;
        }

        public String getProjectName() {
            return projectName;
        }

        public void setProjectName(String projectName) {
            this.projectName = projectName;
        }

        public Long getServerId() {
            return serverId;
        }

        public void setServerId(Long serverId) {
            this.serverId = serverId;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public LocalDateTime getFinishedAt() {
            return finishedAt;
        }

        public void setFinishedAt(LocalDateTime finishedAt) {
            this.finishedAt = finishedAt;
        }

        public String getErrorSummary() {
            return errorSummary;
        }

        public void setErrorSummary(String errorSummary) {
            this.errorSummary = errorSummary;
        }
    }
}
