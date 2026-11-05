package com.ops.deploy.notify;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WebhookConfigRepository extends JpaRepository<WebhookConfigEntity, Long> {

    /** 启用的 webhook 配置（告警推送用）。 */
    List<WebhookConfigEntity> findByEnabledTrue();
}
