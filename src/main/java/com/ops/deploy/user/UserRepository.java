package com.ops.deploy.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByUsername(String username);

    boolean existsByUsername(String username);

    /** 启用中的指定角色用户数（最后一个 ADMIN 保护用）。 */
    long countByRoleAndEnabled(String role, boolean enabled);
}
