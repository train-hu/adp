package com.ops.deploy.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 项目成员仓库。联合主键实体（{@link ProjectMemberEntity}）。
 */
public interface ProjectMemberRepository
        extends JpaRepository<ProjectMemberEntity, ProjectMemberId> {

    List<ProjectMemberEntity> findByProjectIdOrderByJoinedAtAsc(Long projectId);

    List<ProjectMemberEntity> findByUserId(Long userId);

    boolean existsByProjectIdAndUserId(Long projectId, Long userId);

    /** 该用户是否为任一项目成员（删除用户前引用检查用）。 */
    boolean existsByUserId(Long userId);

    /** 用户作为成员的全部 projectId（非 ADMIN 列表隔离用）。
     *  复合主键实体无法用派生投影只取 projectId（Hibernate 报 QueryTypeMismatch），改用显式 JPQL。 */
    @Query("select m.projectId from ProjectMemberEntity m where m.userId = :userId")
    List<Long> findProjectIdByUserId(@Param("userId") Long userId);

    void deleteByProjectId(Long projectId);
}
