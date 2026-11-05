package com.ops.deploy.deploy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface DeploymentStepRepository extends JpaRepository<DeploymentStepEntity, Long> {

    /** 某部署的步骤列表，按 seq 升序。 */
    List<DeploymentStepEntity> findByDeploymentIdOrderBySeq(Long deploymentId);

    /** 删除某部署的全部步骤（删除项目时级联清理）。 */
    void deleteByDeploymentId(Long deploymentId);

    /** 批量删除多个部署的步骤（数据保留清理用，先删子表再删 deployment 主表）。 */
    @Modifying
    @Query("delete from DeploymentStepEntity s where s.deploymentId in :ids")
    int deleteByDeploymentIds(@Param("ids") Collection<Long> ids);
}
