package com.ops.deploy.project;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<ProjectEntity, Long> {

    boolean existsByName(String name);

    /** 该用户是否创建过任一项目（删除用户前引用检查用）。 */
    boolean existsByCreatedBy(Long createdBy);
}
