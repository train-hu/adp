-- 同一服务器同一时刻只允许一个非终态部署：并发 create 的 DB 兜底（防 check-then-act 竞态）。
-- 与 DeploymentService.create 的 existsByServerIdAndStatusNotIn 应用层检查互补。
CREATE UNIQUE INDEX uq_deployment_active_per_server ON deployment(server_id)
    WHERE status NOT IN ('SUCCESS','FAILED','ROLLED_BACK','NEEDS_ATTENTION','CANCELLED');
