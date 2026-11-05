package com.ops.deploy.deploy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

public interface DeploymentRepository extends JpaRepository<DeploymentEntity, Long> {

    /** 项目部署历史，按创建时间倒序。 */
    List<DeploymentEntity> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    /** 某项目的全部部署（用于删除项目时级联清理，deployment.project_id 为 RESTRICT 外键）。 */
    List<DeploymentEntity> findByProjectId(Long projectId);

    /** 某子服务的全部部署（删除子服务时级联清理，deployment.service_id 为 RESTRICT 外键）。 */
    List<DeploymentEntity> findByServiceId(Long serviceId);

    /** 该服务器上是否仍存在指定状态集合之外的部署（create 时用于同 server 并发互斥）。 */
    boolean existsByServerIdAndStatusNotIn(Long serverId, Collection<String> statuses);

    /** 全部部署，按创建时间倒序（GET /api/deployments）。 */
    List<DeploymentEntity> findAllByOrderByCreatedAtDesc();

    /** 指定状态的全部部署（AI 助手知识库检索 SUCCESS 文档用）。 */
    List<DeploymentEntity> findByStatus(String status);

    /** 指定时间之后创建的部署（仪表盘 30 天统计口径）。 */
    List<DeploymentEntity> findByCreatedAtAfter(LocalDateTime after);

    /**
     * 同 (project, server) 上指定版本最近一次 SUCCESS 部署（回滚重建 unit 时取其 startCmd）。
     *
     * <p>回滚必须恢复"旧版本当时上线"的启动配置（含端口等内联环境变量），不能沿用失败部署的
     * startCmd——后者可能带新的端口（如 PORT=8091），导致回滚后进程 bind 失败、崩溃循环。
     * 同版本多次成功部署取最近一次；无记录返回空。
     */
    Optional<DeploymentEntity> findFirstByProjectIdAndVersionIdAndServerIdAndStatusOrderByIdDesc(
            Long projectId, Long versionId, Long serverId, String status);

    /** 所有非终态（活跃）部署（启动恢复用：进程重启后接管/收敛遗留任务）。 */
    List<DeploymentEntity> findByStatusNotIn(Collection<String> statuses);

    /** 该用户是否为任一部署的操作人（删除用户前引用检查用）。 */
    boolean existsByOperator(Long operator);

    /**
     * 终态（status 在 statuses 内）且创建早于 cutoff 的部署 id 列表（数据保留清理用，
     * 先取 id 删子表再删主表，避免外键冲突）。
     *
     * <p>排除被 service_instance / ai_deployment_plan 引用的部署：
     * service_instance.deployment_id 是 NOT NULL 语义的在线实例回滚锚点（外键无 ON DELETE，
     * 误删会让整轮清理事务回滚）；ai_deployment_plan 同理，避免产生孤儿引用行。
     */
    @Query("select d.id from DeploymentEntity d where d.status in :statuses and d.createdAt < :cutoff"
            + " and not exists (select 1 from ServiceInstanceEntity si where si.deploymentId = d.id)"
            + " and not exists (select 1 from AiDeploymentPlanEntity p where p.deploymentId = d.id)")
    List<Long> findTerminalIdsBefore(@Param("statuses") Collection<String> statuses,
                                     @Param("cutoff") LocalDateTime cutoff);

    /**
     * 批量删除指定 id 的部署记录，返回删除行数（数据保留清理用，须先清子表外键）。
     *
     * <p>遗留表 deploy_doc（V1 建表，无实体、无业务写入，deployment_id NOT NULL 外键）
     * 一并清理，防止其存在旧数据时删除 deployment 触发 23503 外键冲突。
     * 拆成两条 native 语句（Spring 单条 SQL 不支持参数在多处复用 + 语句分隔）。
     */
    @Modifying
    @Query(value = "delete from deploy_doc where deployment_id = any(:ids)", nativeQuery = true)
    int deleteLegacyDocsByIds(@Param("ids") Collection<Long> ids);

    @Modifying
    @Query(value = "delete from deployment where id = any(:ids)", nativeQuery = true)
    int deleteByIds(@Param("ids") Collection<Long> ids);
}
