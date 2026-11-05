-- 项目成员表：项目与用户绑定隔离（严格隔离：非成员不可见项目及其部署/实例/版本）。
-- role：OWNER=项目内完全控制（可删项目/改配置/管理成员）；MEMBER=可上传包、发起部署。
-- 全局角色 ADMIN 始终绕过成员校验（见 ProjectAccessService）。
-- 存量项目过渡：created_by 用户回填 OWNER（无外键用户残留行直接删除）。
CREATE TABLE project_member (
  project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  role VARCHAR(16) NOT NULL,
  joined_at TIMESTAMP NOT NULL DEFAULT now(),
  PRIMARY KEY (project_id, user_id)
);

CREATE INDEX idx_project_member_user ON project_member(user_id);

-- 存量项目归创建人：仅当 created_by 存在时回填 OWNER
INSERT INTO project_member (project_id, user_id, role)
SELECT p.id, p.created_by, 'OWNER'
FROM project p
WHERE p.created_by IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM project_member m WHERE m.project_id = p.id
  );
