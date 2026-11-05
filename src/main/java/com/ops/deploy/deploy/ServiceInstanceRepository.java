package com.ops.deploy.deploy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ServiceInstanceRepository extends JpaRepository<ServiceInstanceEntity, Long> {

    /** 全部在线实例（健康探针遍历用）。 */
    List<ServiceInstanceEntity> findAllByOnlineTrue();

    /** 某 (project, server) 当前 online 的实例（回滚目标/防并发的依据）。 */
    Optional<ServiceInstanceEntity> findFirstByProjectIdAndServerIdAndOnlineTrue(
            Long projectId, Long serverId);

    /** 某 (project, server, service) 当前 online 的实例（服务级在线唯一性）。 */
    Optional<ServiceInstanceEntity> findFirstByProjectIdAndServerIdAndServiceIdAndOnlineTrue(
            Long projectId, Long serverId, Long serviceId);

    /** 某项目指定状态的全部实例（如 RUNNING，用于删除项目前的运行中服务校验）。 */
    List<ServiceInstanceEntity> findByProjectIdAndStatus(Long projectId, String status);

    /** 某项目的全部实例（用于删除项目时级联清理，service_instance.project_id 为 RESTRICT 外键）。 */
    List<ServiceInstanceEntity> findByProjectId(Long projectId);

    /** 某服务器上的全部实例。 */
    List<ServiceInstanceEntity> findByServerId(Long serverId);

    /** 某子服务的全部实例（删除子服务时级联清理，service_instance.service_id 为 RESTRICT 外键）。 */
    List<ServiceInstanceEntity> findByServiceId(Long serviceId);

    /** 按唯一名查实例（如 {project}-{server}-{versionId}）。 */
    Optional<ServiceInstanceEntity> findByName(String name);

    /** 某项目的全部实例（按部署时间倒序）。 */
    List<ServiceInstanceEntity> findByProjectIdOrderByDeployedAtDesc(Long projectId);

    /** 某 (project, server) 的全部实例（按部署时间倒序）。 */
    List<ServiceInstanceEntity> findByProjectIdAndServerIdOrderByDeployedAtDesc(Long projectId, Long serverId);

    /** 某服务器的全部实例（按部署时间倒序）。 */
    List<ServiceInstanceEntity> findByServerIdOrderByDeployedAtDesc(Long serverId);

    /** 全部实例（按部署时间倒序）。 */
    List<ServiceInstanceEntity> findAllByOrderByDeployedAtDesc();
}
