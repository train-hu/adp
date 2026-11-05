-- V20: upload_session 补 service_id（V19 遗漏：实体 UploadSessionEntity 已含 serviceId，
-- 分片上传会话需按子服务落库，合并完成后版本归属该服务）
ALTER TABLE upload_session ADD COLUMN service_id BIGINT NOT NULL DEFAULT 0;
