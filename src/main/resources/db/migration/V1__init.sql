CREATE TABLE app_user (
  id BIGSERIAL PRIMARY KEY,
  username VARCHAR(64) UNIQUE NOT NULL,
  password_hash VARCHAR(128) NOT NULL,
  role VARCHAR(16) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE server_info (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(64) UNIQUE NOT NULL,
  ip VARCHAR(64) NOT NULL,
  ssh_port INT NOT NULL DEFAULT 22,
  ssh_user VARCHAR(64) NOT NULL,
  auth_type VARCHAR(16) NOT NULL,
  credential_cipher BYTEA NOT NULL,
  env_tag VARCHAR(16) NOT NULL DEFAULT 'dev',
  status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
  profile_json JSONB,
  last_check_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE project (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(64) UNIQUE NOT NULL,
  description VARCHAR(255),
  detected_type VARCHAR(32),
  default_start_cmd VARCHAR(512),
  default_port INT,
  default_health_path VARCHAR(128),
  created_by BIGINT REFERENCES app_user(id),
  created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE package_version (
  id BIGSERIAL PRIMARY KEY,
  project_id BIGINT NOT NULL REFERENCES project(id),
  file_name VARCHAR(255) NOT NULL,
  storage_path VARCHAR(512) NOT NULL,
  sha256 VARCHAR(64) NOT NULL,
  size_bytes BIGINT NOT NULL,
  version_note VARCHAR(255),
  detected_json JSONB,
  uploaded_by BIGINT REFERENCES app_user(id),
  created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_package_version_project ON package_version(project_id);

CREATE TABLE deployment (
  id BIGSERIAL PRIMARY KEY,
  project_id BIGINT NOT NULL REFERENCES project(id),
  version_id BIGINT NOT NULL REFERENCES package_version(id),
  server_id BIGINT NOT NULL REFERENCES server_info(id),
  status VARCHAR(24) NOT NULL,
  mode VARCHAR(16) NOT NULL DEFAULT 'PROCESS',
  start_cmd VARCHAR(512),
  port INT,
  health_path VARCHAR(128),
  workdir VARCHAR(255),
  old_version_id BIGINT,
  operator BIGINT REFERENCES app_user(id),
  error_summary VARCHAR(1024),
  created_at TIMESTAMP NOT NULL DEFAULT now(),
  started_at TIMESTAMP,
  finished_at TIMESTAMP
);
CREATE INDEX idx_deployment_server ON deployment(server_id, status);
CREATE INDEX idx_deployment_project ON deployment(project_id);

CREATE TABLE deployment_step (
  id BIGSERIAL PRIMARY KEY,
  deployment_id BIGINT NOT NULL REFERENCES deployment(id),
  seq INT NOT NULL,
  name VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  detail JSONB,
  log_text TEXT,
  started_at TIMESTAMP,
  finished_at TIMESTAMP,
  UNIQUE(deployment_id, seq)
);

CREATE TABLE service_instance (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(128) UNIQUE NOT NULL,
  project_id BIGINT NOT NULL REFERENCES project(id),
  server_id BIGINT NOT NULL REFERENCES server_info(id),
  deployment_id BIGINT REFERENCES deployment(id),
  unit_name VARCHAR(128) NOT NULL,
  port INT,
  health_path VARCHAR(128),
  status VARCHAR(16) NOT NULL DEFAULT 'STOPPED',
  online BOOLEAN NOT NULL DEFAULT TRUE,
  deployed_at TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_service_instance_server ON service_instance(server_id);

CREATE TABLE deploy_doc (
  id BIGSERIAL PRIMARY KEY,
  deployment_id BIGINT NOT NULL REFERENCES deployment(id),
  doc_type VARCHAR(16) NOT NULL,
  storage_path VARCHAR(512) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE audit_log (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT,
  username VARCHAR(64),
  role VARCHAR(16),
  action VARCHAR(64) NOT NULL,
  target_type VARCHAR(32),
  target_id VARCHAR(64),
  detail TEXT,
  ip VARCHAR(64),
  created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_created ON audit_log(created_at);
