package com.ops.deploy.llm;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** LLM 模型配置仓储。 */
public interface LlmModelConfigRepository extends JpaRepository<LlmModelConfigEntity, Long> {

    boolean existsByName(String name);

    List<LlmModelConfigEntity> findAllByOrderByCreatedAtAsc();
}
