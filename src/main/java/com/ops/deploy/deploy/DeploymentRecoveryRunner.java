package com.ops.deploy.deploy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 启动恢复 Runner（I-1）：进程重启后接管遗留的非终态部署任务。
 *
 * <p>背景：部署执行是异步的（{@link DeploymentRunner#run} 经 @Async 线程池），进程重启会
 * 丢失正在执行的线程，遗留任务将永久悬挂在 PENDING / 中间态。本 Runner 在应用启动后
 * 扫描所有非终态任务（终态/活跃判定以状态机为单一事实源，不硬编码）：
 * <ul>
 *   <li>近期 PENDING（startedAt=null 且 createdAt 在超时阈值内）→ 视为正常在途，
 *       重新触发 {@code runner.run(id)}（跨 Bean 调用，@Async 代理生效）；</li>
 *   <li>超时 PENDING（僵尸：创建已久仍未开始）→ 置 NEEDS_ATTENTION 交人工接管；</li>
 *   <li>其余中间态（UPLOADING/INSTALLING/STARTING/VERIFYING/ROLLING_BACK，重启时正在跑）
 *       → 置 NEEDS_ATTENTION（error_summary 记录中断前的状态）。</li>
 * </ul>
 * 单条处理失败仅记录日志，不中断其余任务的恢复。
 */
@Component
public class DeploymentRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DeploymentRecoveryRunner.class);

    private final DeploymentRepository deploymentRepository;
    private final DeploymentRunner runner;
    private final long pendingTimeoutMinutes;

    public DeploymentRecoveryRunner(DeploymentRepository deploymentRepository,
                                    DeploymentRunner runner,
                                    @Value("${deploy.runner.recovery-pending-timeout-minutes:10}")
                                            long pendingTimeoutMinutes) {
        this.deploymentRepository = deploymentRepository;
        this.runner = runner;
        this.pendingTimeoutMinutes = pendingTimeoutMinutes;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 终态列表以状态机为单一事实源（不硬编码），据此查"非终态"遗留任务
        List<String> terminal = Arrays.stream(DeploymentStatus.values())
                .filter(DeploymentStateMachine::isTerminal)
                .map(Enum::name)
                .collect(Collectors.toList());
        List<DeploymentEntity> actives = deploymentRepository.findByStatusNotIn(terminal);
        for (DeploymentEntity d : actives) {
            try {
                recoverOne(d);
            } catch (Exception e) {
                log.error("recovery failed for deployment {}: {}", d.getId(), e.getMessage(), e);
            }
        }
    }

    /** 处理单条遗留任务：PENDING 区分"在途/僵尸"，中间态一律置 NEEDS_ATTENTION。 */
    private void recoverOne(DeploymentEntity d) {
        if (DeploymentStatus.PENDING.name().equals(d.getStatus()) && d.getStartedAt() == null) {
            LocalDateTime deadline = LocalDateTime.now().minusMinutes(pendingTimeoutMinutes);
            if (d.getCreatedAt() != null && d.getCreatedAt().isAfter(deadline)) {
                // 正常在途（可能只是异步队列里还没轮到）：重新触发（跨 Bean 调用，@Async 代理生效）
                runner.run(d.getId());
                log.info("re-triggered recent pending deployment {} on startup", d.getId());
                return;
            }
            // 僵尸 PENDING：创建已久仍未开始执行 → 人工接管
            d.setStatus(DeploymentStatus.NEEDS_ATTENTION.name());
            d.setErrorSummary("zombie PENDING on startup, auto-cancelled");
            d.setFinishedAt(LocalDateTime.now());
            deploymentRepository.save(d);
            log.warn("zombie PENDING deployment {} marked NEEDS_ATTENTION on startup", d.getId());
            return;
        }
        // 中间态（UPLOADING/INSTALLING/STARTING/VERIFYING/ROLLING_BACK）：重启时正在跑，无法续跑
        String old = d.getStatus();
        d.setStatus(DeploymentStatus.NEEDS_ATTENTION.name());
        d.setErrorSummary("interrupted by restart in status " + old);
        d.setFinishedAt(LocalDateTime.now());
        deploymentRepository.save(d);
        log.warn("deployment {} interrupted by restart in status {}, marked NEEDS_ATTENTION",
                d.getId(), old);
    }
}
