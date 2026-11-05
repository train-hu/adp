-- 部署文档（Task 8）：每次部署成功后生成部署/维护两份 Markdown 全文，
-- M1 不引入 MinIO，直接以 TEXT 存库；deployment_id 唯一（同一部署一份文档记录）。
CREATE TABLE deployment_document (
  id BIGSERIAL PRIMARY KEY,
  deployment_id BIGINT NOT NULL UNIQUE REFERENCES deployment(id),
  deploy_markdown TEXT,
  maintain_markdown TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT now(),
  updated_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_deployment_document_deployment ON deployment_document(deployment_id);
