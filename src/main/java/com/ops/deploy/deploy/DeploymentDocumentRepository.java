package com.ops.deploy.deploy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DeploymentDocumentRepository extends JpaRepository<DeploymentDocumentEntity, Long> {

    /** 某部署的文档记录（deployment_id 唯一，至多一条）。 */
    Optional<DeploymentDocumentEntity> findByDeploymentId(Long deploymentId);

    /** 批量取文档（知识库检索用，防 N+1）。 */
    List<DeploymentDocumentEntity> findByDeploymentIdIn(List<Long> deploymentIds);

    /** 删除某部署的文档记录（删除项目时级联清理）。 */
    void deleteByDeploymentId(Long deploymentId);

    /** 批量删除多个部署的文档记录（数据保留清理用，先删子表再删 deployment 主表）。 */
    @Modifying
    @Query("delete from DeploymentDocumentEntity x where x.deploymentId in :ids")
    int deleteByDeploymentIds(@Param("ids") Collection<Long> ids);
}
