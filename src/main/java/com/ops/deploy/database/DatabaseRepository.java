package com.ops.deploy.database;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DatabaseRepository extends JpaRepository<DatabaseEntity, Long> {

    boolean existsByName(String name);

    /** 按 id 集合查询（OPS 列表可见性过滤用）。 */
    List<DatabaseEntity> findByIdIn(List<Long> ids);
}
