-- 服务器/数据库资源可见性：ADMIN 可见全部并可分配；OPS 可见"任一 OPS 创建 + 被分配"的并集。
-- 1) server_info / database_info 增加 created_by（存量行留空 = 历史资源，经分配后对 OPS 可见）；
-- 2) server_grant / database_grant 按用户分配表（联合主键 + 双向 ON DELETE CASCADE，
--    删除服务器/数据库或用户时自动清理，无需应用层维护）。
ALTER TABLE server_info ADD COLUMN created_by BIGINT REFERENCES app_user(id);
ALTER TABLE database_info ADD COLUMN created_by BIGINT REFERENCES app_user(id);

CREATE TABLE server_grant (
    server_id  BIGINT NOT NULL REFERENCES server_info(id) ON DELETE CASCADE,
    user_id    BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    granted_at TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (server_id, user_id)
);

CREATE TABLE database_grant (
    database_id BIGINT NOT NULL REFERENCES database_info(id) ON DELETE CASCADE,
    user_id     BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    granted_at  TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (database_id, user_id)
);
