package com.ops.deploy.dashboard;

import com.ops.deploy.deploy.DeploymentEntity;
import com.ops.deploy.deploy.DeploymentRepository;
import com.ops.deploy.deploy.ServiceInstanceEntity;
import com.ops.deploy.deploy.ServiceInstanceRepository;
import com.ops.deploy.database.DatabaseRepository;
import com.ops.deploy.project.ProjectAccessService;
import com.ops.deploy.project.ProjectEntity;
import com.ops.deploy.project.ProjectRepository;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.server.ServerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 仪表盘统计 {@link DashboardService} 单测（mock 六个仓库/服务）。
 *
 * <p>覆盖：30 天部署计数、实例状态分布、服务器总数、最近失败列表（非 SUCCESS 终态取 5 条）、
 * ADMIN 全量 vs 非 ADMIN 按可见项目过滤。
 */
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock
    private DeploymentRepository deploymentRepository;
    @Mock
    private ServiceInstanceRepository instanceRepository;
    @Mock
    private ServerRepository serverRepository;
    @Mock
    private DatabaseRepository databaseRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ProjectAccessService accessService;

    private DashboardService service;

    private static final AuthUser ADMIN = new AuthUser(9L, "admin", "ADMIN");
    private static final AuthUser OPS = new AuthUser(8L, "ops", "OPS");

    @BeforeEach
    void setUp() {
        service = new DashboardService(deploymentRepository, instanceRepository,
                serverRepository, databaseRepository, projectRepository, accessService);
    }

    private DeploymentEntity deployment(long id, long projectId, String status, LocalDateTime createdAt,
                                        LocalDateTime finishedAt, String error) {
        DeploymentEntity d = new DeploymentEntity();
        d.setId(id);
        d.setProjectId(projectId);
        d.setServerId(2L);
        d.setStatus(status);
        d.setCreatedAt(createdAt);
        d.setFinishedAt(finishedAt);
        d.setErrorSummary(error);
        return d;
    }

    private ServiceInstanceEntity instance(long id, long projectId, String status) {
        ServiceInstanceEntity e = new ServiceInstanceEntity();
        e.setId(id);
        e.setName("demo-2-" + id);
        e.setProjectId(projectId);
        e.setServerId(2L);
        e.setStatus(status);
        e.setOnline(Boolean.TRUE);
        return e;
    }

    private void stubProjects(String... names) {
        List<ProjectEntity> projects = new java.util.ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            ProjectEntity p = new ProjectEntity();
            p.setId((long) (i + 1));
            p.setName(names[i]);
            projects.add(p);
        }
        when(projectRepository.findAll()).thenReturn(projects);
    }

    @Test
    @DisplayName("ADMIN：30 天部署计数 + 实例分布 + 服务器数 + 最近失败 5 条")
    void adminFullStats() {
        when(accessService.visibleProjectIds(ADMIN)).thenReturn(null);
        LocalDateTime now = LocalDateTime.now();
        // 30 天内：1 SUCCESS + 1 FAILED
        when(deploymentRepository.findByCreatedAtAfter(any(LocalDateTime.class)))
                .thenReturn(List.of(
                        deployment(1L, 1L, "SUCCESS", now.minusDays(1), now.minusDays(1), null),
                        deployment(2L, 1L, "FAILED", now.minusDays(2), now.minusDays(2), "boom")));
        // 全部部署倒序：含 6 条非 SUCCESS 终态 → 只取前 5
        when(deploymentRepository.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(
                        deployment(2L, 1L, "FAILED", now.minusDays(2), now.minusDays(2), "boom"),
                        deployment(3L, 1L, "ROLLED_BACK", now.minusDays(3), now.minusDays(3), null),
                        deployment(4L, 2L, "CANCELLED", now.minusDays(4), now.minusDays(4), null),
                        deployment(5L, 1L, "NEEDS_ATTENTION", now.minusDays(5), now.minusDays(5), null),
                        deployment(6L, 2L, "FAILED", now.minusDays(6), now.minusDays(6), null),
                        deployment(7L, 1L, "FAILED", now.minusDays(7), now.minusDays(7), null),
                        deployment(1L, 1L, "SUCCESS", now.minusDays(1), now.minusDays(1), null),
                        deployment(8L, 1L, "RUNNING", now.minusDays(0), null, null)));
        when(instanceRepository.findAll()).thenReturn(List.of(
                instance(1L, 1L, "RUNNING"),
                instance(2L, 1L, "RUNNING"),
                instance(3L, 2L, "STOPPED")));
        when(serverRepository.count()).thenReturn(3L);
        when(databaseRepository.count()).thenReturn(2L);
        stubProjects("demo", "other");

        DashboardStatsVO vo = service.stats(ADMIN);

        assertThat(vo.getDeploymentTotal30d()).isEqualTo(2);
        assertThat(vo.getDeploymentSuccess30d()).isEqualTo(1);
        assertThat(vo.getInstanceTotal()).isEqualTo(3);
        assertThat(vo.getInstanceRunning()).isEqualTo(2);
        assertThat(vo.getInstanceStopped()).isEqualTo(1);
        assertThat(vo.getServerTotal()).isEqualTo(3);
        assertThat(vo.getDatabaseTotal()).isEqualTo(2);
        assertThat(vo.getRecentFailures()).hasSize(5);
        assertThat(vo.getRecentFailures().get(0).getId()).isEqualTo(2L);
        assertThat(vo.getRecentFailures().get(0).getProjectName()).isEqualTo("demo");
        assertThat(vo.getRecentFailures().get(0).getStatus()).isEqualTo("FAILED");
        assertThat(vo.getRecentFailures().get(0).getErrorSummary()).isEqualTo("boom");
        // SUCCESS 与活跃（RUNNING）状态不进入失败列表
        assertThat(vo.getRecentFailures()).noneMatch(f -> "SUCCESS".equals(f.getStatus()));
        assertThat(vo.getRecentFailures()).noneMatch(f -> "RUNNING".equals(f.getStatus()));
    }

    @Test
    @DisplayName("非 ADMIN：仅统计可见项目（projectId=1），不可见项目数据被过滤")
    void opsFilterByVisibleProjects() {
        when(accessService.visibleProjectIds(OPS)).thenReturn(List.of(1L));
        LocalDateTime now = LocalDateTime.now();
        when(deploymentRepository.findByCreatedAtAfter(any(LocalDateTime.class)))
                .thenReturn(List.of(
                        deployment(1L, 1L, "SUCCESS", now.minusDays(1), now.minusDays(1), null),
                        deployment(2L, 2L, "SUCCESS", now.minusDays(1), now.minusDays(1), null)));
        when(deploymentRepository.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(
                        deployment(3L, 2L, "FAILED", now.minusDays(2), now.minusDays(2), "hidden"),
                        deployment(4L, 1L, "FAILED", now.minusDays(3), now.minusDays(3), "visible")));
        when(instanceRepository.findAll()).thenReturn(List.of(
                instance(1L, 1L, "RUNNING"),
                instance(2L, 2L, "RUNNING")));
        when(serverRepository.count()).thenReturn(3L);
        when(databaseRepository.count()).thenReturn(2L);
        stubProjects("demo", "other");

        DashboardStatsVO vo = service.stats(OPS);

        assertThat(vo.getDeploymentTotal30d()).isEqualTo(1);
        assertThat(vo.getInstanceTotal()).isEqualTo(1);
        assertThat(vo.getInstanceRunning()).isEqualTo(1);
        assertThat(vo.getRecentFailures()).hasSize(1);
        assertThat(vo.getRecentFailures().get(0).getId()).isEqualTo(4L);
        // 服务器总数为全局资源，不按项目过滤
        assertThat(vo.getServerTotal()).isEqualTo(3L);
        // 数据库总数同为全局资源，不按项目过滤
        assertThat(vo.getDatabaseTotal()).isEqualTo(2L);
    }

    @Test
    @DisplayName("空数据：全部为 0、失败列表为空")
    void emptyStats() {
        when(accessService.visibleProjectIds(ADMIN)).thenReturn(null);
        when(deploymentRepository.findByCreatedAtAfter(any(LocalDateTime.class))).thenReturn(List.of());
        when(deploymentRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());
        when(instanceRepository.findAll()).thenReturn(List.of());
        when(serverRepository.count()).thenReturn(0L);
        when(databaseRepository.count()).thenReturn(0L);
        when(projectRepository.findAll()).thenReturn(List.of());

        DashboardStatsVO vo = service.stats(ADMIN);

        assertThat(vo.getDeploymentTotal30d()).isZero();
        assertThat(vo.getDeploymentSuccess30d()).isZero();
        assertThat(vo.getInstanceTotal()).isZero();
        assertThat(vo.getServerTotal()).isZero();
        assertThat(vo.getDatabaseTotal()).isZero();
        assertThat(vo.getRecentFailures()).isEmpty();
    }
}
