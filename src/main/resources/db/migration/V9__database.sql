-- 数据库连接管理：登记已有的 MySQL/PostgreSQL/Redis 实例，供部署时选择注入连接信息，
-- 或作为项目独立库的登记来源。密码经 AES-256-GCM 加密后存 password_cipher（与 server_info 同模式）。
CREATE TABLE database_info (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(64) UNIQUE NOT NULL,
  db_type VARCHAR(16) NOT NULL,
  host VARCHAR(128) NOT NULL,
  port INT NOT NULL,
  database_name VARCHAR(128),
  username VARCHAR(128),
  password_cipher BYTEA,
  status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
  last_check_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT now()
);
