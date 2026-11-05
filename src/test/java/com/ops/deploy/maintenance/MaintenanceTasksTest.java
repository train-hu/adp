package com.ops.deploy.maintenance;

import com.ops.deploy.audit.AuditRepository;
import com.ops.deploy.deploy.DeploymentDocumentRepository;
import com.ops.deploy.deploy.DeploymentRepository;
import com.ops.deploy.deploy.DeploymentStepRepository;
import com.ops.deploy.notify.NotificationRepository;
import com.ops.deploy.project.PackageStorage;
import com.ops.deploy.project.UploadSessionEntity;
import com.ops.deploy.project.UploadSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MaintenanceTasks 纯逻辑单测（Mockito）：
 * <ul>
 *   <li>分片清理：处理仓储返回的每个过期会话（删分片目录 + 删 DB 记录），空列表不抛异常</li>
 *   <li>保留清理：各仓储 deleteBefore 收到 now-N 天的 cutoff，deployment 先清子表再删主表</li>
 * </ul>
 * 构造用包内构造器显式传保留参数（绕过 @Value）。
 */
@ExtendWith(MockitoExtension.class)
class MaintenanceTasksTest {

    @Mock
    private UploadSessionRepository sessionRepository;
    @Mock
    private PackageStorage storage;
    @Mock
    private AuditRepository auditRepository;
    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private DeploymentRepository deploymentRepository;
    @Mock
    private DeploymentStepRepository stepRepository;
    @Mock
    private DeploymentDocumentRepository documentRepository;

    private MaintenanceTasks tasks;

    @BeforeEach
    void setUp() {
        // 显式保留参数：上传过期 24h，审计/部署保留 365d，通知保留 30d
        tasks = new MaintenanceTasks(sessionRepository, storage, auditRepository,
                notificationRepository, deploymentRepository, stepRepository, documentRepository,
                24L, 365L, 30L, 365L);
    }

    private UploadSessionEntity session(String id, String status) {
        UploadSessionEntity s = new UploadSessionEntity();
        s.setId(id);
        s.setProjectId(1L);
        s.setFilename("app.zip");
        s.setStatus(status);
        s.setCreatedAt(LocalDateTime.now().minusDays(2));
        s.setUpdatedAt(LocalDateTime.now().minusDays(2));
        return s;
    }

    // ---------- 分片上传清理 ----------

    @Test
    void cleanExpiredUploadSessions_shouldProcessEachReturnedSession() {
        UploadSessionEntity s1 = session("sess-1", "UPLOADING");
        UploadSessionEntity s2 = session("sess-2", "FAILED");
        when(sessionRepository.findByStatusNotAndCreatedAtBefore(eq("COMPLETED"), any(LocalDateTime.class)))
                .thenReturn(List.of(s1, s2));

        tasks.cleanExpiredUploadSessions();

        verify(storage).deleteUploadSessionDir("sess-1");
        verify(storage).deleteUploadSessionDir("sess-2");
        verify(sessionRepository).delete(s1);
        verify(sessionRepository).delete(s2);
    }

    @Test
    void cleanExpiredUploadSessions_emptyList_shouldNotThrowAndDoNothing() {
        when(sessionRepository.findByStatusNotAndCreatedAtBefore(eq("COMPLETED"), any(LocalDateTime.class)))
                .thenReturn(List.of());

        assertThatCode(() -> tasks.cleanExpiredUploadSessions()).doesNotThrowAnyException();

        verify(storage, never()).deleteUploadSessionDir(anyString());
        verify(sessionRepository, never()).delete(any(UploadSessionEntity.class));
    }

    @Test
    void cleanExpiredUploadSessions_singleSessionFailure_shouldContinueWithOthers() {
        UploadSessionEntity s1 = session("sess-1", "UPLOADING");
        UploadSessionEntity s2 = session("sess-2", "UPLOADING");
        when(sessionRepository.findByStatusNotAndCreatedAtBefore(eq("COMPLETED"), any(LocalDateTime.class)))
                .thenReturn(List.of(s1, s2));
        // 第一个会话删目录抛异常，不应影响第二个会话处理
        org.mockito.Mockito.doThrow(new RuntimeException("io error"))
                .when(storage).deleteUploadSessionDir("sess-1");

        assertThatCode(() -> tasks.cleanExpiredUploadSessions()).doesNotThrowAnyException();

        verify(sessionRepository, never()).delete(s1);
        verify(storage).deleteUploadSessionDir("sess-2");
        verify(sessionRepository).delete(s2);
    }

    @Test
    void cleanExpiredUploadSessions_shouldQueryWithExpireCutoff() {
        when(sessionRepository.findByStatusNotAndCreatedAtBefore(eq("COMPLETED"), any(LocalDateTime.class)))
                .thenReturn(List.of());
        LocalDateTime before = LocalDateTime.now();

        tasks.cleanExpiredUploadSessions();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(sessionRepository).findByStatusNotAndCreatedAtBefore(eq("COMPLETED"), captor.capture());
        // cutoff ≈ now-24h（upload-expire-hours=24）
        assertThat(captor.getValue()).isBetween(before.minusHours(24).minusMinutes(2), before.minusHours(24).plusMinutes(2));
    }

    // ---------- 数据保留清理 ----------

    @Test
    void cleanExpiredRecords_shouldDeleteBeforeCorrectCutoffs() {
        when(auditRepository.deleteBefore(any(LocalDateTime.class))).thenReturn(5);
        when(notificationRepository.deleteBefore(any(LocalDateTime.class))).thenReturn(3);
        when(deploymentRepository.findTerminalIdsBefore(anyCollection(), any(LocalDateTime.class)))
                .thenReturn(List.of());
        LocalDateTime before = LocalDateTime.now();

        tasks.cleanExpiredRecords();

        ArgumentCaptor<LocalDateTime> auditCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(auditRepository).deleteBefore(auditCaptor.capture());
        assertThat(auditCaptor.getValue())
                .isBetween(before.minusDays(365).minusMinutes(2), before.minusDays(365).plusMinutes(2));

        ArgumentCaptor<LocalDateTime> notifCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(notificationRepository).deleteBefore(notifCaptor.capture());
        assertThat(notifCaptor.getValue())
                .isBetween(before.minusDays(30).minusMinutes(2), before.minusDays(30).plusMinutes(2));
    }

    @Test
    void cleanExpiredRecords_deploymentFound_shouldDeleteChildrenThenParent() {
        when(deploymentRepository.findTerminalIdsBefore(anyCollection(), any(LocalDateTime.class)))
                .thenReturn(List.of(10L, 11L));
        when(stepRepository.deleteByDeploymentIds(anyCollection())).thenReturn(8);
        when(documentRepository.deleteByDeploymentIds(anyCollection())).thenReturn(2);
        when(deploymentRepository.deleteLegacyDocsByIds(anyCollection())).thenReturn(1);
        when(deploymentRepository.deleteByIds(anyCollection())).thenReturn(2);

        tasks.cleanExpiredRecords();

        // 先子表（step/document）后主表
        verify(stepRepository).deleteByDeploymentIds(eq(List.of(10L, 11L)));
        verify(documentRepository).deleteByDeploymentIds(eq(List.of(10L, 11L)));
        verify(deploymentRepository).deleteByIds(eq(List.of(10L, 11L)));
    }

    @Test
    void cleanExpiredRecords_noDeployment_shouldSkipChildDeletes() {
        when(deploymentRepository.findTerminalIdsBefore(anyCollection(), any(LocalDateTime.class)))
                .thenReturn(List.of());

        tasks.cleanExpiredRecords();

        verify(stepRepository, never()).deleteByDeploymentIds(anyCollection());
        verify(documentRepository, never()).deleteByDeploymentIds(anyCollection());
        verify(deploymentRepository, never()).deleteByIds(anyCollection());
    }
}
