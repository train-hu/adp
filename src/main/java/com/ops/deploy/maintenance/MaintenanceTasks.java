package com.ops.deploy.maintenance;

import com.ops.deploy.audit.AuditRepository;
import com.ops.deploy.deploy.DeploymentDocumentRepository;
import com.ops.deploy.deploy.DeploymentRepository;
import com.ops.deploy.deploy.DeploymentStepRepository;
import com.ops.deploy.notify.NotificationRepository;
import com.ops.deploy.project.PackageStorage;
import com.ops.deploy.project.UploadSessionEntity;
import com.ops.deploy.project.UploadSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时清理任务：
 * <ul>
 *   <li>{@link #cleanExpiredUploadSessions()}：清理过期分片上传会话（磁盘分片目录 + DB 记录）</li>
 *   <li>{@link #cleanExpiredRecords()}：数据保留清理（audit_log / notification / deployment）</li>
 * </ul>
 *
 * <p><b>单实例假设</b>：定时任务假设单实例部署，多实例需自行加分布式锁，
 * 否则同一批记录会被多个实例并发处理（本类清理均为幂等删除，后果可控，
 * 但日志计数与锁竞争开销不可控）。
 */
@Component
public class MaintenanceTasks {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceTasks.class);

    /** 分片上传会话视为过期的状态值（COMPLETED 会话由上传流程自行清理，不在此处理）。 */
    private static final String UPLOAD_SESSION_COMPLETED = "COMPLETED";

    /** 参与保留清理的部署终态（DeploymentStatus 中终态为 SUCCESS/FAILED/ROLLED_BACK/NEEDS_ATTENTION/CANCELLED，
     *  按需求口径取前四个；CANCELLED 记录量少且含用户主动取消语义，不主动清理）。 */
    static final List<String> TERMINAL_STATUSES =
            List.of("SUCCESS", "FAILED", "ROLLED_BACK", "NEEDS_ATTENTION");

    private final UploadSessionRepository sessionRepository;
    private final PackageStorage storage;
    private final AuditRepository auditRepository;
    private final NotificationRepository notificationRepository;
    private final DeploymentRepository deploymentRepository;
    private final DeploymentStepRepository stepRepository;
    private final DeploymentDocumentRepository documentRepository;

    /** 未完成的上传会话超过该小时数视为过期。 */
    private final long uploadExpireHours;
    /** audit_log 保留天数。 */
    private final long auditRetentionDays;
    /** notification（已读）保留天数。 */
    private final long notificationRetentionDays;
    /** deployment（终态）保留天数。 */
    private final long deploymentRetentionDays;

    /**
     * 构造器：@Value 注入各项保留配置（Spring 单构造器注入，无需显式 @Autowired）。
     */
    public MaintenanceTasks(UploadSessionRepository sessionRepository,
                            PackageStorage storage,
                            AuditRepository auditRepository,
                            NotificationRepository notificationRepository,
                            DeploymentRepository deploymentRepository,
                            DeploymentStepRepository stepRepository,
                            DeploymentDocumentRepository documentRepository,
                            @Value("${deploy.cleanup.upload-expire-hours:24}") long uploadExpireHours,
                            @Value("${deploy.cleanup.audit-retention-days:365}") long auditRetentionDays,
                            @Value("${deploy.cleanup.notification-retention-days:30}") long notificationRetentionDays,
                            @Value("${deploy.cleanup.deployment-retention-days:365}") long deploymentRetentionDays) {
        this.sessionRepository = sessionRepository;
        this.storage = storage;
        this.auditRepository = auditRepository;
        this.notificationRepository = notificationRepository;
        this.deploymentRepository = deploymentRepository;
        this.stepRepository = stepRepository;
        this.documentRepository = documentRepository;
        this.uploadExpireHours = uploadExpireHours;
        this.auditRetentionDays = auditRetentionDays;
        this.notificationRetentionDays = notificationRetentionDays;
        this.deploymentRetentionDays = deploymentRetentionDays;
    }

    /**
     * 清理过期分片上传会话：status != 'COMPLETED' 且 createdAt 早于 now-{upload-expire-hours}h。
     *
     * <p>每个会话：先删磁盘分片目录（{@code {data-dir}/.uploads/{sessionId}}，
     * 目录规则由 PackageStorage 统一维护，不存在则跳过、失败仅告警），再删 DB 记录。
     * 单会话删除失败不影响其余会话。
     */
    @Scheduled(fixedDelayString = "${deploy.cleanup.upload-scan-delay-ms:3600000}")
    public void cleanExpiredUploadSessions() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(uploadExpireHours);
        List<UploadSessionEntity> expired =
                sessionRepository.findByStatusNotAndCreatedAtBefore(UPLOAD_SESSION_COMPLETED, cutoff);
        if (expired.isEmpty()) {
            log.info("upload session cleanup: nothing to clean (cutoff={})", cutoff);
            return;
        }
        int dbDeleted = 0;
        for (UploadSessionEntity session : expired) {
            try {
                storage.deleteUploadSessionDir(session.getId());
                sessionRepository.delete(session);
                dbDeleted++;
            } catch (RuntimeException e) {
                // 单会话失败不阻断整轮清理，残留由下一轮扫描重试
                log.warn("failed to clean upload session {}: {}", session.getId(), e.getMessage());
            }
        }
        log.info("upload session cleanup: cleaned {}/{} expired sessions (status != {}, created before {})",
                dbDeleted, expired.size(), UPLOAD_SESSION_COMPLETED, cutoff);
    }

    /**
     * 数据保留清理（每天 03:30 默认）：
     * <ul>
     *   <li>audit_log：删 createdAt 早于 now-{audit-retention-days}d 的记录</li>
     *   <li>notification：删 createdAt 早于 now-{notification-retention-days}d 且已读（read_at 非空）的记录</li>
     *   <li>deployment：删"终态（status in {TERMINAL_STATUSES}）且 createdAt 早于
     *       now-{deployment-retention-days}d"的记录；deployment_step / deployment_document 的
     *       deployment_id 为 NOT NULL 外键（V1 / V4 DDL），须先删子表再删主表。
     *       另排除被 service_instance 引用的部署（其 deployment_id 为可空外键，
     *       且在线实例回滚目标 oldVersionId 依赖该引用）。</li>
     * </ul>
     */
    @Transactional
    @Scheduled(cron = "${deploy.cleanup.retention-cron:0 30 3 * * *}")
    public void cleanExpiredRecords() {
        LocalDateTime now = LocalDateTime.now();

        int audits = auditRepository.deleteBefore(now.minusDays(auditRetentionDays));
        int notifications = notificationRepository.deleteBefore(now.minusDays(notificationRetentionDays));

        // deployment：先取待删 id（排除 service_instance 引用的），再先子后主批量删
        List<Long> deploymentIds = deploymentRepository.findTerminalIdsBefore(
                TERMINAL_STATUSES, now.minusDays(deploymentRetentionDays));
        int steps = 0;
        int docs = 0;
        int deployments = 0;
        if (!deploymentIds.isEmpty()) {
            steps = stepRepository.deleteByDeploymentIds(deploymentIds);
            docs = documentRepository.deleteByDeploymentIds(deploymentIds);
            deploymentRepository.deleteLegacyDocsByIds(deploymentIds);
            deployments = deploymentRepository.deleteByIds(deploymentIds);
        }

        log.info("retention cleanup: audit_log={}, notification={}, deployment={} (steps={}, docs={})",
                audits, notifications, deployments, steps, docs);
    }
}
