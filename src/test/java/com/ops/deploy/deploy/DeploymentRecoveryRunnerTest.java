package com.ops.deploy.deploy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 启动恢复 Runner {@link DeploymentRecoveryRunner} 单测（mock 仓库 + Runner）。
 *
 * <p>覆盖：
 * <ol>
 *   <li>近期 PENDING（startedAt=null）→ 重新触发 run(id)；</li>
 *   <li>超时 PENDING（僵尸）→ 置 NEEDS_ATTENTION + save，不触发；</li>
 *   <li>中间态（如 UPLOADING，重启时正在跑）→ 置 NEEDS_ATTENTION + save；</li>
 *   <li>无非终态任务 → 与 Runner 无任何交互；</li>
 *   <li>单条 save 抛异常 → 吞掉后继续处理其余（不逸出）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class DeploymentRecoveryRunnerTest {

    @Mock
    private DeploymentRepository deploymentRepository;
    @Mock
    private DeploymentRunner runner;

    @Captor
    private ArgumentCaptor<DeploymentEntity> entityCaptor;

    private DeploymentRecoveryRunner recoveryRunner;

    @BeforeEach
    void setUp() {
        // 超时阈值 10 分钟：createdAt 距今 > 10 分钟的 PENDING 视为僵尸
        recoveryRunner = new DeploymentRecoveryRunner(deploymentRepository, runner, 10L);
        lenient().when(deploymentRepository.save(any(DeploymentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("启动恢复：近期 PENDING（startedAt=null）→ 重新触发 run(id)")
    void recovery_recentPending_reTriggered() {
        DeploymentEntity d = pending(1L, LocalDateTime.now());
        when(deploymentRepository.findByStatusNotIn(anyCollection())).thenReturn(List.of(d));

        recoveryRunner.run(null);

        verify(runner).run(1L);
        verify(deploymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("启动恢复：超时 PENDING（僵尸）→ NEEDS_ATTENTION + save，不触发 run")
    void recovery_zombiePending_markedNeedsAttention() {
        DeploymentEntity d = pending(1L, LocalDateTime.now().minusMinutes(30));
        when(deploymentRepository.findByStatusNotIn(anyCollection())).thenReturn(List.of(d));

        recoveryRunner.run(null);

        assertThat(d.getStatus()).isEqualTo(DeploymentStatus.NEEDS_ATTENTION.name());
        assertThat(d.getErrorSummary()).isEqualTo("zombie PENDING on startup, auto-cancelled");
        assertThat(d.getFinishedAt()).isNotNull();
        verify(deploymentRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue()).isSameAs(d);
        verify(runner, never()).run(anyLong());
    }

    @Test
    @DisplayName("启动恢复：中间态（UPLOADING，重启时正在跑）→ NEEDS_ATTENTION + save")
    void recovery_midState_markedNeedsAttention() {
        DeploymentEntity d = pending(1L, LocalDateTime.now());
        d.setStatus(DeploymentStatus.UPLOADING.name());
        d.setStartedAt(LocalDateTime.now());
        when(deploymentRepository.findByStatusNotIn(anyCollection())).thenReturn(List.of(d));

        recoveryRunner.run(null);

        assertThat(d.getStatus()).isEqualTo(DeploymentStatus.NEEDS_ATTENTION.name());
        assertThat(d.getErrorSummary()).contains("interrupted by restart in status UPLOADING");
        assertThat(d.getFinishedAt()).isNotNull();
        verify(deploymentRepository).save(d);
        verify(runner, never()).run(anyLong());
    }

    @Test
    @DisplayName("启动恢复：无非终态任务 → 与 Runner 无交互、不落库")
    void recovery_noActives_noInteraction() {
        when(deploymentRepository.findByStatusNotIn(anyCollection())).thenReturn(List.of());

        recoveryRunner.run(null);

        verifyNoInteractions(runner);
        verify(deploymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("启动恢复：单条 save 抛异常 → 吞掉后其余仍被处理（不逸出）")
    void recovery_singleSaveFails_othersStillProcessed() {
        DeploymentEntity d1 = pending(1L, LocalDateTime.now());
        d1.setStatus(DeploymentStatus.UPLOADING.name());
        DeploymentEntity d2 = pending(2L, LocalDateTime.now());
        d2.setStatus(DeploymentStatus.UPLOADING.name());
        when(deploymentRepository.findByStatusNotIn(anyCollection()))
                .thenReturn(List.of(d1, d2));
        when(deploymentRepository.save(eq(d1))).thenThrow(new RuntimeException("db down"));

        recoveryRunner.run(null);

        assertThat(d1.getStatus()).isEqualTo(DeploymentStatus.NEEDS_ATTENTION.name());
        assertThat(d2.getStatus()).isEqualTo(DeploymentStatus.NEEDS_ATTENTION.name());
        verify(deploymentRepository).save(eq(d1));
        verify(deploymentRepository).save(eq(d2));
    }

    /** 构造指定 createdAt 的 PENDING（startedAt=null）实体。 */
    private static DeploymentEntity pending(long id, LocalDateTime createdAt) {
        DeploymentEntity d = new DeploymentEntity();
        d.setId(id);
        d.setProjectId(1L);
        d.setServerId(id);
        d.setVersionId(10L);
        d.setStatus(DeploymentStatus.PENDING.name());
        d.setMode("PROCESS");
        d.setStartCmd("java -jar app.jar");
        d.setPort(8080);
        d.setHealthPath("/health");
        d.setCreatedAt(createdAt);
        return d;
    }
}
