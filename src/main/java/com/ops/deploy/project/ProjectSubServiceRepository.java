package com.ops.deploy.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 项目下子服务（project_sub_service）仓储。
 *
 * <p>"子服务"指一个项目下独立部署的多个服务（如 categraf 项目下既部署
 * categraf 主服务也部署另一个 agent）。表名用 project_sub_service 以避开
 * 既有的 {@link ProjectService}（项目 CRUD 服务）类名冲突。
 */
public interface ProjectSubServiceRepository extends JpaRepository<ProjectSubServiceEntity, Long> {

    /** 某项目下的全部子服务（按创建时间升序）。 */
    List<ProjectSubServiceEntity> findByProjectIdOrderByCreatedAtAsc(Long projectId);

    /** 某项目下的默认子服务（path_key 为 NULL，name=项目名）。迁移后每项目必有且仅有一条。 */
    Optional<ProjectSubServiceEntity> findFirstByProjectIdAndPathKeyIsNull(Long projectId);

    /** 某项目下按名查子服务（同项目 name 唯一）。 */
    Optional<ProjectSubServiceEntity> findByProjectIdAndName(Long projectId, String name);

    /** 某项目下是否存在指定 name 的子服务（防重复）。 */
    boolean existsByProjectIdAndName(Long projectId, String name);

    /** 项目 id 集合下的全部子服务（项目列表页聚合用）。 */
    List<ProjectSubServiceEntity> findByProjectIdIn(List<Long> projectIds);
}
