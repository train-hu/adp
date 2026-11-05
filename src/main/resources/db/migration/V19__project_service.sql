-- 项目 1:N 子服务模型：新增 project_sub_service 表，包版本/配置文件/部署/服务实例全部下沉到子服务级。
-- 存量数据自动回填默认子服务：每个已有项目建一条默认子服务（name=项目名，path_key=NULL），
-- 现有 package_version / project_config_file / deployment / service_instance 全部挂到默认子服务名下。
-- 默认子服务 path_key=NULL → 沿用旧口径（workdir=/opt/deploy/{项目}/{版本}、unit=deploy-{项目}.service），
-- 与线上已部署实例完全兼容；仅新建的非默认子服务用 path_key=slug(name) → 新路径/新 unit。
-- 表名用 project_sub_service 以避开既有的 ProjectService（项目 CRUD 服务）类名冲突。

CREATE TABLE project_sub_service (
  id BIGSERIAL PRIMARY KEY,
  project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  name VARCHAR(64) NOT NULL,
  path_key VARCHAR(128),
  created_at TIMESTAMP NOT NULL DEFAULT now(),
  updated_at TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE (project_id, name)
);
CREATE INDEX idx_project_sub_service_project ON project_sub_service(project_id);
COMMENT ON COLUMN project_sub_service.path_key IS
  '非默认子服务的目录/单元后缀（slug）；NULL=默认子服务，沿用旧 workdir/unit 口径';

-- 回填：每个项目一条默认子服务（name=项目名，path_key=NULL）
INSERT INTO project_sub_service (project_id, name, path_key, created_at, updated_at)
SELECT p.id, p.name, NULL, now(), now()
FROM project p;

-- package_version 下沉到子服务
ALTER TABLE package_version ADD COLUMN service_id BIGINT NOT NULL DEFAULT 0;
UPDATE package_version pv SET service_id = ps.id
  FROM project_sub_service ps
  WHERE ps.project_id = pv.project_id AND ps.path_key IS NULL;
ALTER TABLE package_version ADD CONSTRAINT fk_package_version_service
  FOREIGN KEY (service_id) REFERENCES project_sub_service(id);
-- 内容去重维度由 (project_id, sha256) 收窄为 (service_id, sha256)：
-- 同项目不同子服务允许上传相同内容的包（包按子服务隔离），但同一子服务内仍去重
ALTER TABLE package_version DROP CONSTRAINT uk_package_version_project_sha;
ALTER TABLE package_version ADD CONSTRAINT uk_package_version_service_sha UNIQUE (service_id, sha256);

-- project_config_file 下沉到子服务（唯一约束由 (project_id, file_key) 收窄为 (service_id, file_key)，
-- 同项目不同子服务可各自持有同名 fileKey）
ALTER TABLE project_config_file ADD COLUMN service_id BIGINT NOT NULL DEFAULT 0;
UPDATE project_config_file pcf SET service_id = ps.id
  FROM project_sub_service ps
  WHERE ps.project_id = pcf.project_id AND ps.path_key IS NULL;
ALTER TABLE project_config_file ADD CONSTRAINT fk_project_config_file_service
  FOREIGN KEY (service_id) REFERENCES project_sub_service(id);
ALTER TABLE project_config_file DROP CONSTRAINT project_config_file_project_id_file_key_key;
ALTER TABLE project_config_file ADD CONSTRAINT uq_config_file_service_key UNIQUE (service_id, file_key);

-- deployment 下沉到子服务（service_id 跟随其版本的 service_id）
ALTER TABLE deployment ADD COLUMN service_id BIGINT NOT NULL DEFAULT 0;
UPDATE deployment dep SET service_id = pv.service_id
  FROM package_version pv
  WHERE pv.id = dep.version_id;
ALTER TABLE deployment ADD CONSTRAINT fk_deployment_service
  FOREIGN KEY (service_id) REFERENCES project_sub_service(id);

-- service_instance 下沉到子服务（在线唯一性改按 (project, server, service) 维度，由应用层查询保证）
ALTER TABLE service_instance ADD COLUMN service_id BIGINT NOT NULL DEFAULT 0;
UPDATE service_instance si SET service_id = pv.service_id
  FROM deployment dep, package_version pv
  WHERE dep.id = si.deployment_id AND pv.id = dep.version_id;
-- deployment_id 为空的遗留实例：挂到所属项目的默认子服务
UPDATE service_instance si SET service_id = ps.id
  FROM project_sub_service ps
  WHERE si.service_id = 0 AND si.deployment_id IS NULL
    AND ps.project_id = si.project_id AND ps.path_key IS NULL;
ALTER TABLE service_instance ADD CONSTRAINT fk_service_instance_service
  FOREIGN KEY (service_id) REFERENCES project_sub_service(id);
CREATE INDEX idx_service_instance_service ON service_instance(service_id);
