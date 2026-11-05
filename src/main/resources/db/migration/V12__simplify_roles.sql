-- 角色模型简化：全局角色仅保留 ADMIN/OPS，取消项目内 OWNER/MEMBER 两级。
-- 1) 存量 VIEWER 用户转为 OPS；
-- 2) project_member 不再区分项目内角色（成员即可完全管理项目），删除 role 列。
UPDATE app_user SET role = 'OPS' WHERE role = 'VIEWER';

ALTER TABLE project_member DROP COLUMN role;
