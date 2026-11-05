package com.ops.deploy.database;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 数据库资源分配仓库。联合主键实体（{@link DatabaseGrantEntity}）。
 */
public interface DatabaseGrantRepository
        extends JpaRepository<DatabaseGrantEntity, DatabaseGrantId> {

    List<DatabaseGrantEntity> findByDatabaseIdOrderByGrantedAtAsc(Long databaseId);

    boolean existsByDatabaseIdAndUserId(Long databaseId, Long userId);

    /** 该用户是否持有任一数据库分配（删除用户前引用检查用）。 */
    boolean existsByUserId(Long userId);

    /** 该用户被分配的全部 databaseId（OPS 列表隔离用）。
     *  复合主键实体无法用派生投影只取 databaseId（Hibernate 报 QueryTypeMismatch），改用显式 JPQL。 */
    @Query("select g.databaseId from DatabaseGrantEntity g where g.userId = :userId")
    List<Long> findDatabaseIdByUserId(@Param("userId") Long userId);

    void deleteByDatabaseId(Long databaseId);
}
