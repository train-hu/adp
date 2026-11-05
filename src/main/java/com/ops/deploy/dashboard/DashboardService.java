package com.ops.deploy.dashboard;

import com.ops.deploy.deploy.DeploymentEntity;
import com.ops.deploy.project.ProjectRepository;
import com.ops.deploy.project.ProjectEntity;
import com.ops.deploy.database.DatabaseRepository;
import com.ops.deploy.server.ServerRepository;
import com.ops.deploy.deploy.DeploymentRepository;
import com.ops.deploy.deploy.ServiceInstanceEntity;
import com.ops.deploy.deploy.ServiceInstanceRepository;
import com.ops.deploy.project.ProjectAccessService;
import com.ops.deploy.security.AuthUser;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 仪表盘统计（GET /api/dashboard）。
 *
 * <p>口径：
 * <ul>
 *   <li>deploymentTotal30d / deploymentSuccess30d：30 天内创建的部署数 / 其中 SUCCESS 数；</li>
 *   <li>instanceTotal / instanceRunning / instanceStopped：服务实例按 status 统计；</li>
 *   <li>serverTotal：服务器总数；</li>
 *   <li>databaseTotal：数据库总数；</li>
 *   <li>recentFailures：最近 5 条「创建时间倒序」中第一个非 SUCCESS 的终态部署
 *       （id / projectName / serverId / status / finishedAt / errorSummary）。</li>
 * </ul>
 *
 * <p>项目隔离：非 ADMIN 只统计其可见项目内的部署与实例（ADMIN 全量）。
 */
@Service
public class DashboardService {

    /** 仪表盘统计时间窗口（天）。 */
    static final int STATS_WINDOW_DAYS = 30;
    /** 最近失败列表条数。 */
    static final int RECENT_FAILURES_LIMIT = 5;

    /** 部署终态集合（与 DeploymentService.TERMINAL_STATUSES 对齐）。 */
    static final List<String> TERMINAL_STATUSES =
            List.of("SUCCESS", "FAILED", "ROLLED_BACK", "NEEDS_ATTENTION", "CANCELLED");

    private final DeploymentRepository deploymentRepository;
    private final ServiceInstanceRepository instanceRepository;
    private final ServerRepository serverRepository;
    private final DatabaseRepository databaseRepository;
    private final ProjectRepository projectRepository;
    private final ProjectAccessService accessService;

    public DashboardService(DeploymentRepository deploymentRepository,
                            ServiceInstanceRepository instanceRepository,
                            ServerRepository serverRepository,
                            DatabaseRepository databaseRepository,
                            ProjectRepository projectRepository,
                            ProjectAccessService accessService) {
        this.deploymentRepository = deploymentRepository;
        this.instanceRepository = instanceRepository;
        this.serverRepository = serverRepository;
        this.databaseRepository = databaseRepository;
        this.projectRepository = projectRepository;
        this.accessService = accessService;
    }

    /** 汇总统计（按当前用户可见项目隔离）。 */
    public DashboardStatsVO stats(AuthUser user) {
        List<Long> visible = accessService.visibleProjectIds(user);

        // ---- 30 天部署 ----
        LocalDateTime since = LocalDateTime.now().minusDays(STATS_WINDOW_DAYS);
        List<DeploymentEntity> recent = deploymentRepository.findByCreatedAtAfter(since);
        long total30d = 0;
        long success30d = 0;
        for (DeploymentEntity d : recent) {
            if (!visibleProject(d.getProjectId(), visible)) {
                continue;
            }
            total30d++;
            if ("SUCCESS".equals(d.getStatus())) {
                success30d++;
            }
        }

        // ---- 实例状态分布 ----
        long instanceTotal = 0;
        long instanceRunning = 0;
        long instanceStopped = 0;
        for (ServiceInstanceEntity inst : instanceRepository.findAll()) {
            if (!visibleProject(inst.getProjectId(), visible)) {
                continue;
            }
            instanceTotal++;
            if ("RUNNING".equals(inst.getStatus())) {
                instanceRunning++;
            } else {
                instanceStopped++;
            }
        }

        // ---- 最近失败（创建时间倒序取前 5 条非 SUCCESS 终态，按可见项目过滤） ----
        Map<Long, String> projectNames = projectRepository.findAll().stream()
                .collect(Collectors.toMap(ProjectEntity::getId, ProjectEntity::getName, (a, b) -> a));
        List<DashboardStatsVO.RecentFailure> failures = deploymentRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(d -> visibleProject(d.getProjectId(), visible))
                .filter(d -> TERMINAL_STATUSES.contains(d.getStatus()) && !"SUCCESS".equals(d.getStatus()))
                .limit(RECENT_FAILURES_LIMIT)
                .map(d -> {
                    DashboardStatsVO.RecentFailure f = new DashboardStatsVO.RecentFailure();
                    f.setId(d.getId());
                    f.setProjectId(d.getProjectId());
                    f.setProjectName(projectNames.getOrDefault(d.getProjectId(), "project-" + d.getProjectId()));
                    f.setServerId(d.getServerId());
                    f.setStatus(d.getStatus());
                    f.setFinishedAt(d.getFinishedAt());
                    f.setErrorSummary(d.getErrorSummary());
                    return f;
                })
                .toList();

        DashboardStatsVO vo = new DashboardStatsVO();
        vo.setDeploymentTotal30d(total30d);
        vo.setDeploymentSuccess30d(success30d);
        vo.setInstanceTotal(instanceTotal);
        vo.setInstanceRunning(instanceRunning);
        vo.setInstanceStopped(instanceStopped);
        vo.setServerTotal(serverRepository.count());
        vo.setDatabaseTotal(databaseRepository.count());
        vo.setRecentFailures(failures);
        return vo;
    }

    /** 可见性判定：visible==null 表示 ADMIN 全可见；否则项目须在集合内。 */
    private static boolean visibleProject(Long projectId, List<Long> visible) {
        return visible == null || visible.contains(projectId);
    }
}
