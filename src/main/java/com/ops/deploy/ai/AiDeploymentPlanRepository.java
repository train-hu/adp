package com.ops.deploy.ai;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** AI 部署方案仓库。 */
public interface AiDeploymentPlanRepository extends JpaRepository<AiDeploymentPlanEntity, Long> {

    /** 某项目最近的方案（部署页展示"AI 方案"入口用）。 */
    List<AiDeploymentPlanEntity> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    /** 关联到某部署任务的方案（确认执行后 deployment_id 回写，用于文档"AI 决策记录"）。 */
    List<AiDeploymentPlanEntity> findByDeploymentId(Long deploymentId);
}
