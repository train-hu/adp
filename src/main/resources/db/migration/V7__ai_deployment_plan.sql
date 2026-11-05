-- AI 自主部署方案（M2 AI 能力，FR-02/FR-05）。
-- AI 以 ReAct 循环解析部署包 + 探测目标机，产出结构化部署方案（含命令预览与推理轨迹），
-- 人工确认（可编辑）后由平台既有安全管线执行。本表持久化方案与全程轨迹，可回放、可审计。
CREATE TABLE ai_deployment_plan (
  id BIGSERIAL PRIMARY KEY,
  project_id BIGINT NOT NULL,
  version_id BIGINT NOT NULL,
  server_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  source VARCHAR(16) NOT NULL DEFAULT 'AI',
  detected_type VARCHAR(32),
  build_tool VARCHAR(32),
  mode VARCHAR(16) NOT NULL DEFAULT 'PROCESS',
  start_cmd TEXT,
  port INTEGER,
  health_path VARCHAR(256) DEFAULT '/',
  reasoning TEXT,
  trajectory_json TEXT,
  plan_json TEXT,
  deployment_id BIGINT,
  error_summary TEXT,
  operator VARCHAR(64),
  created_at TIMESTAMP NOT NULL DEFAULT now(),
  updated_at TIMESTAMP NOT NULL DEFAULT now(),
  confirmed_at TIMESTAMP
);

CREATE INDEX idx_ai_plan_project ON ai_deployment_plan (project_id);
CREATE INDEX idx_ai_plan_status ON ai_deployment_plan (status);
