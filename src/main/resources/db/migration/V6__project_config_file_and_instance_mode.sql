-- 项目配置文件（nginx/docker-compose/.env 等）：页面可编辑，部署时下发目标机。
-- file_key 预设三个值（nginx / compose / env），也允许自定义 key（如 extra-nginx-snippet）。
-- 同一项目下 file_key 唯一（upsert 语义）。content 上限 1MB（TEXT 列够用，应用层再限）。
CREATE TABLE project_config_file (
  id BIGSERIAL PRIMARY KEY,
  project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  file_key VARCHAR(64) NOT NULL,
  content TEXT NOT NULL,
  remote_path VARCHAR(512),
  created_at TIMESTAMP NOT NULL DEFAULT now(),
  updated_at TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE (project_id, file_key)
);
CREATE INDEX idx_project_config_file_project ON project_config_file(project_id);

-- service_instance 扩展部署模式：mode（PROCESS/NGINX/DOCKER）。
-- unit_name 仅 PROCESS 模式有值（systemd unit），NGINX/DOCKER 模式无 unit → 放宽为可空。
-- workdir 记录该版本部署目录（NGINX 软链源 / DOCKER compose 路径均派生自此，停启复用）。
ALTER TABLE service_instance ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'PROCESS';
ALTER TABLE service_instance ALTER COLUMN unit_name DROP NOT NULL;
ALTER TABLE service_instance ADD COLUMN workdir VARCHAR(255);
