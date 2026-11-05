-- 同项目内包内容（sha256）唯一，保证并发上传相同内容时数据库层面拒绝重复
ALTER TABLE package_version ADD CONSTRAINT uk_package_version_project_sha UNIQUE (project_id, sha256);
