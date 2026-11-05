-- 分片上传会话：前端把大文件切成固定大小的分片逐个上传，合并完成后走标准落库链路。
-- id 为 UUID 字符串（前端持有，断点续传时重新查询已传分片）。
-- 分片本体存 {data-dir}/.uploads/{sessionId}/{index}.part（不入库）。
-- status: UPLOADING / COMPLETED / FAILED。
CREATE TABLE upload_session (
    id            VARCHAR(36) PRIMARY KEY,
    project_id    BIGINT      NOT NULL,
    filename      VARCHAR(255) NOT NULL,
    total_size    BIGINT      NOT NULL,
    chunk_size    BIGINT      NOT NULL,
    total_chunks  INT         NOT NULL,
    sha256        VARCHAR(64),
    status        VARCHAR(16) NOT NULL DEFAULT 'UPLOADING',
    created_at    TIMESTAMP NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_upload_session_project ON upload_session (project_id, created_at DESC);
