-- 站内通知：服务健康探针状态翻转、部署事件等写此处，前端顶栏铃铛展示未读。
-- level: INFO / WARN；read_at 为 null 表示未读。
CREATE TABLE notification (
    id          BIGSERIAL PRIMARY KEY,
    level       VARCHAR(32)  NOT NULL,
    title       VARCHAR(256) NOT NULL,
    message     VARCHAR(1024),
    project_id  BIGINT,
    instance_id BIGINT,
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    read_at     TIMESTAMP
);

CREATE INDEX idx_notification_unread ON notification (created_at DESC) WHERE read_at IS NULL;
