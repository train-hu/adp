package com.ops.deploy.deploy;

import com.ops.deploy.audit.Auditable;
import com.ops.deploy.common.Result;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.security.CurrentUser;
import com.ops.deploy.security.RequireRole;
import com.ops.deploy.user.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 部署任务接口（/api/deployments，Task 6 F）。JWT 认证由 JwtAuthFilter 统一拦截，
 * 角色由 @RequireRole 切面校验（ADMIN &gt; OPS），写操作由 @Auditable 审计。
 *
 * <p>create / cancel 需 OPS（部署与取消属运维写操作）；list / get / getSteps 无角色限制
 * （任何已登录角色可读部署进度）。
 */
@RestController
@RequestMapping("/api/deployments")
public class DeploymentController {

    private final DeploymentService deploymentService;

    public DeploymentController(DeploymentService deploymentService) {
        this.deploymentService = deploymentService;
    }

    @PostMapping
    @RequireRole(Role.OPS)
    @Auditable(action = "DEPLOY_CREATE", targetType = "DEPLOYMENT")
    public ResponseEntity<Result<DeploymentVO>> create(@Valid @RequestBody DeploymentCreateRequest req,
                                                       @CurrentUser AuthUser user) {
        return ResponseEntity.status(201).body(Result.ok(deploymentService.create(req, user)));
    }

    /** 端口预检：SSH 目标机解析 ss 监听状态（与 create 同权限）；SSH 失败返回 probed=false 由前端放行。 */
    @PostMapping("/check-port")
    @RequireRole(Role.OPS)
    public Result<DeploymentService.PortCheckResult> checkPort(@Valid @RequestBody PortCheckRequest req) {
        return Result.ok(deploymentService.checkPort(req.serverId(), req.port()));
    }

    @GetMapping
    public Result<List<DeploymentVO>> list(@CurrentUser AuthUser user) {
        return Result.ok(deploymentService.list(user));
    }

    @GetMapping("/{id}")
    public Result<DeploymentVO> get(@PathVariable Long id, @CurrentUser AuthUser user) {
        return Result.ok(deploymentService.get(id, user));
    }

    @GetMapping("/{id}/steps")
    public Result<List<DeploymentStepVO>> getSteps(@PathVariable Long id, @CurrentUser AuthUser user) {
        return Result.ok(deploymentService.getSteps(id, user));
    }

    @PostMapping("/{id}/cancel")
    @RequireRole(Role.OPS)
    @Auditable(action = "DEPLOY_CANCEL", targetType = "DEPLOYMENT")
    public Result<DeploymentVO> cancel(@PathVariable Long id, @CurrentUser AuthUser user) {
        return Result.ok(deploymentService.cancel(id, user));
    }

    /** 重新部署：按原部署同参数新建一个部署任务（仅终态部署可重跑，OPS 及以上）。 */
    @PostMapping("/{id}/redeploy")
    @RequireRole(Role.OPS)
    @Auditable(action = "DEPLOY_REDEPLOY", targetType = "DEPLOYMENT")
    public ResponseEntity<Result<DeploymentVO>> redeploy(@PathVariable Long id,
                                                         @CurrentUser AuthUser user) {
        return ResponseEntity.status(201).body(Result.ok(deploymentService.redeploy(id, user)));
    }

    /** 一键版本回退：把某次已完成部署回退到它替代的旧版本（仅终态且有旧版本，OPS 及以上）。 */
    @PostMapping("/{id}/rollback")
    @RequireRole(Role.OPS)
    @Auditable(action = "DEPLOY_ROLLBACK", targetType = "DEPLOYMENT")
    public Result<DeploymentVO> rollback(@PathVariable Long id, @CurrentUser AuthUser user) {
        return Result.ok(deploymentService.rollback(id, user));
    }

    /** 端口预检请求：port 必填（自动分配场景由前端传入计算好的起点端口）。 */
    public record PortCheckRequest(@NotNull Long serverId, @NotNull Integer port) {
    }
}
