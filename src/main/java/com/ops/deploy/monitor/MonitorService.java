package com.ops.deploy.monitor;

import com.ops.deploy.deploy.DeploymentEntity;
import com.ops.deploy.deploy.DeploymentRepository;
import com.ops.deploy.deploy.ServiceInstanceEntity;
import com.ops.deploy.deploy.ServiceInstanceRepository;
import com.ops.deploy.project.ProjectAccessService;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.server.ServerEntity;
import com.ops.deploy.server.ServerRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 监控指标汇总（GET /api/monitor/summary）。
 *
 * <p>数据来源：
 * <ul>
 *   <li>部署统计：DB 聚合近 30 天部署（总数/成功/失败/平均耗时/逐日趋势），
 *       与仪表盘同口径按可见项目隔离（ADMIN 全量）；</li>
 *   <li>JVM 摘要：Micrometer 自动采集的 jvm.memory.used / jvm.threads 等 gauge；</li>
 *   <li>HTTP 摘要：Micrometer http_server_requests Timer（累计请求数 / 5xx 数 / 总耗时）；</li>
 *   <li>服务实例健康：service_instance 全量 + 服务器 ip，按可见项目隔离。</li>
 * </ul>
 * JVM/HTTP 为平台后端进程自身指标，不做项目隔离。
 */
@Service
public class MonitorService {

    /** 监控统计时间窗口（天），与仪表盘一致。 */
    static final int STATS_WINDOW_DAYS = 30;

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("MM-dd");

    private final DeploymentRepository deploymentRepository;
    private final ServiceInstanceRepository instanceRepository;
    private final ServerRepository serverRepository;
    private final ProjectAccessService accessService;
    private final MeterRegistry registry;

    public MonitorService(DeploymentRepository deploymentRepository,
                          ServiceInstanceRepository instanceRepository,
                          ServerRepository serverRepository,
                          ProjectAccessService accessService,
                          MeterRegistry registry) {
        this.deploymentRepository = deploymentRepository;
        this.instanceRepository = instanceRepository;
        this.serverRepository = serverRepository;
        this.accessService = accessService;
        this.registry = registry;
    }

    /** 汇总监控指标（按当前用户可见项目隔离部署/实例口径）。 */
    public MonitorStatsVO summary(AuthUser user) {
        List<Long> visible = accessService.visibleProjectIds(user);
        MonitorStatsVO vo = new MonitorStatsVO();

        // ---- 近 30 天部署统计 + 逐日趋势 ----
        LocalDateTime since = LocalDateTime.now().minusDays(STATS_WINDOW_DAYS);
        Map<String, MonitorStatsVO.DailyTrend> byDay = new TreeMap<>();
        long total = 0;
        long success = 0;
        long failed = 0;
        long durationSumSeconds = 0;
        long durationCount = 0;
        for (DeploymentEntity d : deploymentRepository.findByCreatedAtAfter(since)) {
            if (!visibleProject(d.getProjectId(), visible)) {
                continue;
            }
            total++;
            if ("SUCCESS".equals(d.getStatus())) {
                success++;
            } else if ("FAILED".equals(d.getStatus())) {
                failed++;
            }
            // 逐日趋势（按创建日期）
            String day = d.getCreatedAt().toLocalDate().format(DAY_FMT);
            MonitorStatsVO.DailyTrend t = byDay.computeIfAbsent(day, k -> {
                MonitorStatsVO.DailyTrend nt = new MonitorStatsVO.DailyTrend();
                nt.setDay(k);
                return nt;
            });
            t.setTotal(t.getTotal() + 1);
            if ("SUCCESS".equals(d.getStatus())) {
                t.setSuccess(t.getSuccess() + 1);
            } else if ("FAILED".equals(d.getStatus())) {
                t.setFailed(t.getFailed() + 1);
            }
            // 平均耗时：startedAt→finishedAt 实算（两端非空的部署）
            if (d.getStartedAt() != null && d.getFinishedAt() != null) {
                long seconds = Duration.between(d.getStartedAt(), d.getFinishedAt()).getSeconds();
                if (seconds >= 0) {
                    durationSumSeconds += seconds;
                    durationCount++;
                }
            }
        }
        vo.setDeploymentTotal30d(total);
        vo.setDeploymentSuccess30d(success);
        vo.setDeploymentFailed30d(failed);
        vo.setAvgDurationSeconds(durationCount > 0
                ? (double) durationSumSeconds / durationCount : null);
        vo.setTrend(List.copyOf(byDay.values()));

        // ---- 服务实例健康（按可见项目隔离，online 在前） ----
        Map<Long, ServerEntity> serverMap = new HashMap<>();
        for (ServerEntity s : serverRepository.findAll()) {
            serverMap.put(s.getId(), s);
        }
        long instanceTotal = 0;
        long instanceRunning = 0;
        for (ServiceInstanceEntity inst : instanceRepository.findAll()) {
            if (!visibleProject(inst.getProjectId(), visible)) {
                continue;
            }
            instanceTotal++;
            if ("RUNNING".equals(inst.getStatus())) {
                instanceRunning++;
            }
        }
        vo.setInstanceTotal(instanceTotal);
        vo.setInstanceRunning(instanceRunning);
        vo.setInstanceStopped(instanceTotal - instanceRunning);
        vo.setInstances(instanceRepository.findAll().stream()
                .filter(i -> visibleProject(i.getProjectId(), visible))
                .sorted((a, b) -> Boolean.compare(Boolean.TRUE.equals(b.getOnline()),
                        Boolean.TRUE.equals(a.getOnline())))
                .map(i -> {
                    MonitorStatsVO.InstanceHealth h = new MonitorStatsVO.InstanceHealth();
                    h.setId(i.getId());
                    h.setName(i.getName());
                    h.setProjectId(i.getProjectId());
                    h.setServerId(i.getServerId());
                    ServerEntity s = serverMap.get(i.getServerId());
                    if (s != null) {
                        h.setServerIp(s.getIp());
                    }
                    h.setStatus(i.getStatus());
                    h.setOnline(i.getOnline());
                    h.setDeployedAt(i.getDeployedAt());
                    h.setUpdatedAt(i.getUpdatedAt());
                    return h;
                })
                .toList());

        // ---- JVM / HTTP（平台后端自身，不隔离） ----
        vo.setJvm(jvmSummary());
        vo.setHttp(httpSummary());
        return vo;
    }

    /** JVM 摘要：从 Micrometer 自动 gauge 取堆/非堆内存与线程数（缺失字段为 null）。
     *  堆/非堆按 area 维度对所有内存池 gauge 求和（实际按 G1/Metaspace 等池分片，无单一汇总 gauge）。 */
    private MonitorStatsVO.JvmSummary jvmSummary() {
        MonitorStatsVO.JvmSummary jvm = new MonitorStatsVO.JvmSummary();
        jvm.setHeapUsedBytes(sumGaugeByArea("jvm.memory.used", "heap"));
        jvm.setHeapMaxBytes(sumGaugeByArea("jvm.memory.max", "heap"));
        jvm.setNonHeapUsedBytes(sumGaugeByArea("jvm.memory.used", "nonheap"));
        Double peak = doubleGauge("jvm.threads.peak");
        if (peak != null) {
            jvm.setPeakThreadCount(peak.longValue());
        }
        Double live = doubleGauge("jvm.threads.live");
        if (live != null) {
            jvm.setThreadCount(live.longValue());
        }
        return jvm;
    }

    /** HTTP 摘要：聚合 http.server.requests Timer 的 count / 5xx count / 总耗时（秒）。
     *  注意：micrometer 内部名为点分 "http.server.requests"（Prometheus 才渲染成 http_server_requests_*）。 */
    private MonitorStatsVO.HttpSummary httpSummary() {
        MonitorStatsVO.HttpSummary http = new MonitorStatsVO.HttpSummary();
        double totalRequests = 0;
        double serverErrors = 0;
        double totalDurationSeconds = 0;
        for (io.micrometer.core.instrument.Timer t : registry.find("http.server.requests").timers()) {
            String status = t.getId().getTag("status");
            if (status == null) {
                continue;
            }
            totalRequests += t.count();
            totalDurationSeconds += t.totalTime(java.util.concurrent.TimeUnit.SECONDS);
            if (status.length() >= 3 && status.startsWith("5")) {
                serverErrors += t.count();
            }
        }
        http.setTotalRequests((long) totalRequests);
        http.setServerErrors((long) serverErrors);
        http.setTotalDurationSeconds(totalDurationSeconds > 0 ? totalDurationSeconds : null);
        return http;
    }

    /** 取指定 gauge 值（key/value 成对 tag 过滤），缺失为 null。 */
    private Double doubleGauge(String name, String... tagPairs) {
        io.micrometer.core.instrument.search.Search search = registry.find(name);
        if (tagPairs.length > 0) {
            search = search.tags(tagPairs);
        }
        return search.gauges().stream()
                .map(g -> g.value())
                .filter(v -> v != null)
                .findFirst()
                .orElse(null);
    }

    /** 按 area 维度对所有内存池 gauge 求和（堆/非堆总内存），无匹配返回 0。 */
    private long sumGaugeByArea(String name, String area) {
        double sum = 0;
        for (io.micrometer.core.instrument.Gauge g : registry.find(name)
                .tags("area", area).gauges()) {
            Double v = g.value();
            if (v != null) {
                sum += v;
            }
        }
        return (long) sum;
    }

    /** 可见性判定：visible==null 表示 ADMIN 全可见；否则项目须在集合内。 */
    private static boolean visibleProject(Long projectId, List<Long> visible) {
        return visible == null || visible.contains(projectId);
    }
}
