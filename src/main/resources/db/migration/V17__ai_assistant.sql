-- AI 助手（M2 AI 能力）：对话消息落库（会话与消息合一，按 conversation_id 分组）。
-- conversation_id 为 UUID 字符串（前端持有）；role 取 user / assistant；
-- trajectory_json 记录 assistant 消息的 ReAct 轨迹（工具名/参数/结果摘要），可回放可审计。
CREATE TABLE ai_chat_message (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL,
    user_id         BIGINT      NOT NULL,
    username        VARCHAR(64) NOT NULL,
    role            VARCHAR(16) NOT NULL,
    model_name      VARCHAR(128),
    content         TEXT        NOT NULL,
    trajectory_json TEXT,
    degraded        BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE INDEX idx_ai_chat_message_conversation ON ai_chat_message (conversation_id, id);
CREATE INDEX idx_ai_chat_message_user ON ai_chat_message (user_id, created_at DESC);

-- LLM 模型"默认"标记：部分唯一索引保证至多一个 is_default = TRUE。
ALTER TABLE llm_model_config ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE;
CREATE UNIQUE INDEX uq_llm_model_config_default ON llm_model_config (is_default) WHERE is_default = TRUE;
