-- V21: 子服务级启动参数（start_cmd/port）+ 项目多服务标记
-- 1) project_sub_service: start_cmd（服务级启动命令，多服务项目部署时优先于 project 默认）、
--    port（服务级端口，部署/compose 宿主机映射优先于请求端口）
-- 2) project: multi_service（多选"多个服务"的新项目不再自动创建默认服务，由用户手动创建服务并填启动命令/端口）

ALTER TABLE project_sub_service ADD COLUMN start_cmd VARCHAR(512);
ALTER TABLE project_sub_service ADD COLUMN port INT;
ALTER TABLE project ADD COLUMN multi_service BOOLEAN NOT NULL DEFAULT FALSE;
