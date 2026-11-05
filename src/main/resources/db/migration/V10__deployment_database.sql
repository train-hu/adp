-- 部署任务关联数据库：deployment 选择已登记的数据库（database_info）或自动创建的项目独立库
-- （首次部署时平台 SSH 到目标机起 MySQL 容器并自动登记回 database_info，deployment 记录其 id）。
-- 注意：不加外键约束。database_id 允许哨兵值 -1（自动创建项目独立库，首次部署前
-- database_info 中尚不存在对应记录），引用完整性由业务层（DatabaseService.delete
-- 删除前查 deployment 引用、DeploymentService.create 校验 id 存在）保证。
ALTER TABLE deployment
  ADD COLUMN database_id BIGINT;
