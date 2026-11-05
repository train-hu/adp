-- webhook 告警配置：部署失败/回滚失败/服务健康探针 DOWN 时，
-- 由 WebhookNotifier 异步 POST 到飞书/钉钉机器人。
-- type: 'FEISHU' / 'DINGTALK'；enabled=false 时不参与推送。
CREATE TABLE webhook_config (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(128)  NOT NULL,
    type        VARCHAR(16)   NOT NULL,
    url         VARCHAR(1024) NOT NULL,
    enabled     BOOLEAN       NOT NULL DEFAULT true,
    created_at  TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP
);

CREATE INDEX idx_webhook_config_enabled ON webhook_config (enabled);
