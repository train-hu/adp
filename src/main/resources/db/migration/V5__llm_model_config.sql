-- LLM 模型接入配置（M2 AI 能力，FR-11）：OpenAI 兼容协议统一接入，
-- 支持云端 API 与本地私有化部署（base_url 指向任意 /v1 端点），可多模型并存。
-- api_key 以 AES-256-GCM 密文存储（与 server_info.credential_cipher 同一加密体系），
-- 任何 API 响应不暴露明文/密文，仅返回脱敏掩码。
CREATE TABLE llm_model_config (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(64) NOT NULL UNIQUE,
  base_url VARCHAR(512) NOT NULL,
  model VARCHAR(128) NOT NULL,
  api_key_cipher BYTEA NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT now(),
  updated_at TIMESTAMP NOT NULL DEFAULT now()
);
