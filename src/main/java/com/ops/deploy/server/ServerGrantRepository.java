package com.ops.deploy.server;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 服务器资源分配仓库。联合主键实体（{@link ServerGrantEntity}）。
 */
public interface ServerGrantRepository
        extends JpaRepository<ServerGrantEntity, ServerGrantId> {

    List<ServerGrantEntity> findByServerIdOrderByGrantedAtAsc(Long serverId);

    boolean existsByServerIdAndUserId(Long serverId, Long userId);

    /** 该用户是否持有任一服务器分配（删除用户前引用检查用）。 */
    boolean existsByUserId(Long userId);

    /** 该用户被分配的全部 serverId（OPS 列表隔离用）。
     *  复合主键实体无法用派生投影只取 serverId（Hibernate 报 QueryTypeMismatch），改用显式 JPQL。 */
    @Query("select g.serverId from ServerGrantEntity g where g.userId = :userId")
    List<Long> findServerIdByUserId(@Param("userId") Long userId);

    void deleteByServerId(Long serverId);
}
