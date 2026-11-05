package com.ops.deploy.server;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ServerRepository extends JpaRepository<ServerEntity, Long> {

    Optional<ServerEntity> findByName(String name);

    boolean existsByName(String name);

    /** 按 id 集合查询（OPS 列表可见性过滤用）。 */
    List<ServerEntity> findByIdIn(List<Long> ids);
}
