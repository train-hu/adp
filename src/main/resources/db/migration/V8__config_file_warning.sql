-- 配置文件可用性校验告警（校验不通过不阻断入库，warning 存提示文案供前端展示）
ALTER TABLE project_config_file ADD COLUMN warning TEXT;
