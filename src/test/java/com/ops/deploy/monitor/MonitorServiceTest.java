package com.ops.deploy.monitor;

import com.ops.deploy.deploy.DeploymentEntity;
import com.ops.deploy.deploy.DeploymentRepository;
import com.ops.deploy.deploy.ServiceInstanceEntity;
import com.ops.deploy.deploy.ServiceInstanceRepository;
import com.ops.deploy.project.ProjectAccessService;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.server.ServerEntity;
import com.ops.deploy.server.ServerRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 监控指标汇总 {@link MonitorService} 单测（mock 仓库/访问服务，真实 SimpleMeterRegistry）。
 *
 * <p>覆盖：30 天部署计数与成功率口径、逐日趋势、平均耗时实算、实例健康分布（online 排序）、
 * JVM/HTTP 指标聚合、ADMIN 全量 vs 非 ADMIN 按可见项目过滤。
 */
@ExtendWith(MockitoExtension.class)
class MonitorServiceTest {

    @Mock
    private DeploymentRepository deploymentRepository;
    @Mock
    private ServiceInstanceRepository instanceRepository;
    @Mock
    private ServerRepository serverRepository;
    @Mock
    private ProjectAccessService accessService;

    private SimpleMeterRegistry registry;
    private MonitorService service;

    private static final AuthUser ADMIN = new AuthUser(9L, "admin", "ADMIN");
    private static final AuthUser OPS = new AuthUser(8L, "ops", "OPS");

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new MonitorService(deploymentRepository, instanceRepository,
                serverRepository, accessService, registry);
        // 各仓储默认空列表（按需覆盖）
        lenient().when(deploymentRepository.findByCreatedAtAfter(any(LocalDateTime.class)))
                .thenReturn(List.of());
        lenient().when(instanceRepository.findAll()).thenReturn(List.of());
        lenient().when(serverRepository.findAll()).thenReturn(List.of());
    }

    private DeploymentEntity deployment(long id, long projectId, String status,
                                        LocalDateTime createdAt,
                                        LocalDateTime startedAt, LocalDateTime finishedAt) {
        DeploymentEntity d = new DeploymentEntity();
        d.setId(id);
        d.setProjectId(projectId);
        d.setServerId(2L);
        d.setStatus(status);
        d.setCreatedAt(createdAt);
        d.setStartedAt(startedAt);
        d.setFinishedAt(finishedAt);
        return d;
    }

    private ServiceInstanceEntity instance(long id, long projectId, String status, boolean online) {
        ServiceInstanceEntity e = new ServiceInstanceEntity();
        e.setId(id);
        e.setName("demo-2-" + id);
        e.setProjectId(projectId);
        e.setServerId(2L);
        e.setStatus(status);
        e.setOnline(online);
        return e;
    }

    @Test
    @DisplayName("30 天部署：总数/成功/失败计数、逐日趋势、平均耗时实算")
    void summary_shouldAggregateDeploymentsTrendAndAvgDuration() {
        when(accessService.visibleProjectIds(ADMIN)).thenReturn(null);
        LocalDateTime base = LocalDateTime.now().minusDays(2);
        when(deploymentRepository.findByCreatedAtAfter(any(LocalDateTime.class))).thenReturn(List.of(
                deployment(1, 100L, "SUCCESS", base.minusDays(1),
                        base.minusDays(1), base.minusDays(1).plusMinutes(2)),
                deployment(2, 100L, "FAILED", base.minusDays(1),
                        base.minusDays(1), base.minusDays(1).plusMinutes(4)),
                deployment(3, 100L, "CANCELLED", base, null, null)));

        MonitorStatsVO vo = service.summary(ADMIN);

        assertThat(vo.getDeploymentTotal30d()).isEqualTo(3);
        assertThat(vo.getDeploymentSuccess30d()).isEqualTo(1);
        assertThat(vo.getDeploymentFailed30d()).isEqualTo(1);
        // 平均耗时只算两端非空：(120 + 240) / 2 = 180s
        assertThat(vo.getAvgDurationSeconds()).isEqualTo(180.0);
        // 逐日趋势按日期升序，共 2 天：day0 含 SUCCESS+FAILED，day1 含 CANCELLED
        assertThat(vo.getTrend()).hasSize(2);
        assertThat(vo.getTrend().get(0).getTotal()).isEqualTo(2);
        assertThat(vo.getTrend().get(0).getSuccess()).isEqualTo(1);
        assertThat(vo.getTrend().get(0).getFailed()).isEqualTo(1);
        assertThat(vo.getTrend().get(1).getTotal()).isEqualTo(1);
    }

    @Test
    @DisplayName("无部署数据：平均耗时为 null，趋势为空")
    void summary_emptyDeployments_avgDurationNull() {
        when(accessService.visibleProjectIds(ADMIN)).thenReturn(null);
        MonitorStatsVO vo = service.summary(ADMIN);
        assertThat(vo.getAvgDurationSeconds()).isNull();
        assertThat(vo.getTrend()).isEmpty();
        assertThat(vo.getDeploymentTotal30d()).isZero();
    }

    @Test
    @DisplayName("实例健康：总数/运行分布 + online 在前排序 + 服务器 ip 关联")
    void summary_shouldListInstanceHealthOnlineFirstWithServerIp() {
        when(accessService.visibleProjectIds(ADMIN)).thenReturn(null);
        ServerEntity server = new ServerEntity();
        server.setId(2L);
        server.setIp("10.0.0.2");
        when(serverRepository.findAll()).thenReturn(List.of(server));
        when(instanceRepository.findAll()).thenReturn(List.of(
                instance(1, 100L, "STOPPED", false),
                instance(2, 100L, "RUNNING", true)));

        MonitorStatsVO vo = service.summary(ADMIN);

        assertThat(vo.getInstanceTotal()).isEqualTo(2);
        assertThat(vo.getInstanceRunning()).isEqualTo(1);
        assertThat(vo.getInstanceStopped()).isEqualTo(1);
        assertThat(vo.getInstances()).hasSize(2);
        // online 实例排在前
        assertThat(vo.getInstances().get(0).getId()).isEqualTo(2);
        assertThat(vo.getInstances().get(0).getStatus()).isEqualTo("RUNNING");
        assertThat(vo.getInstances().get(0).getServerIp()).isEqualTo("10.0.0.2");
    }

    @Test
    @DisplayName("JVM/HTTP 摘要：gauge 与 http_server_requests Timer 聚合")
    void summary_shouldAggregateJvmAndHttpFromRegistry() {
        when(accessService.visibleProjectIds(ADMIN)).thenReturn(null);
        // 模拟 Micrometer 自动采集的 gauge（堆按内存池分片，验证按 area 求和）
        final long[] heapEden = {1024L * 1024L * 256};
        Gauge.builder("jvm.memory.used", () -> heapEden[0])
                .tags("area", "heap", "id", "G1 Eden Space").register(registry);
        final long[] heapOld = {1024L * 1024L * 256};
        Gauge.builder("jvm.memory.used", () -> heapOld[0])
                .tags("area", "heap", "id", "G1 Old Gen").register(registry);
        final long[] metaspace = {1024L * 1024L * 90};
        Gauge.builder("jvm.memory.used", () -> metaspace[0])
                .tags("area", "nonheap", "id", "Metaspace").register(registry);
        final double[] threads = {42};
        Gauge.builder("jvm.threads.live", () -> threads[0]).register(registry);
        // 模拟 http.server.requests Timer（ok / 5xx 各一条；micrometer 内部名为点分）
        registry.timer("http.server.requests",
                        "uri", "/api/ping", "method", "GET", "status", "200")
                .record(100, TimeUnit.MILLISECONDS);
        registry.timer("http.server.requests",
                        "uri", "/api/users", "method", "GET", "status", "500")
                .record(300, TimeUnit.MILLISECONDS);

        MonitorStatsVO vo = service.summary(ADMIN);

        assertThat(vo.getJvm().getHeapUsedBytes()).isEqualTo(512L * 1024L * 1024L);
        assertThat(vo.getJvm().getNonHeapUsedBytes()).isEqualTo(90L * 1024L * 1024L);
        assertThat(vo.getJvm().getThreadCount()).isEqualTo(42);
        assertThat(vo.getHttp().getTotalRequests()).isEqualTo(2);
        assertThat(vo.getHttp().getServerErrors()).isEqualTo(1);
        // 总耗时 0.1s + 0.3s
        assertThat(vo.getHttp().getTotalDurationSeconds()).isCloseTo(0.4,
                org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("非 ADMIN：按可见项目过滤部署与实例")
    void summary_nonAdmin_shouldFilterByVisibleProjects() {
        when(accessService.visibleProjectIds(OPS)).thenReturn(List.of(100L));
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        when(deploymentRepository.findByCreatedAtAfter(any(LocalDateTime.class))).thenReturn(List.of(
                deployment(1, 100L, "SUCCESS", base, base, base.plusMinutes(1)),
                deployment(2, 200L, "SUCCESS", base, base, base.plusMinutes(1))));
        when(instanceRepository.findAll()).thenReturn(List.of(
                instance(1, 100L, "RUNNING", true),
                instance(2, 200L, "RUNNING", true)));

        MonitorStatsVO vo = service.summary(OPS);

        assertThat(vo.getDeploymentTotal30d()).isEqualTo(1);
        assertThat(vo.getInstanceTotal()).isEqualTo(1);
        assertThat(vo.getInstances()).hasSize(1);
        assertThat(vo.getInstances().get(0).getProjectId()).isEqualTo(100L);
    }
}
