-- 方案 C：将 file_key 从固定短 key 升级为支持相对路径（如 conf/config.toml）。
-- 保留 UNIQUE (project_id, file_key) 语义不变，仅放宽列宽与字符集。
ALTER TABLE project_config_file ALTER COLUMN file_key TYPE VARCHAR(512);
COMMENT ON COLUMN project_config_file.file_key IS '配置文件 key：预设 nginx/compose/env/dockerfile，或相对路径（如 conf/config.toml，PROCESS 模式下解压后按该相对路径覆盖包内文件）';
