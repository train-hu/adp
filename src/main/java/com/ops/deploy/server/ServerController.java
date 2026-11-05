package com.ops.deploy.server;

import com.ops.deploy.audit.Auditable;
import com.ops.deploy.common.Result;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.security.CurrentUser;
import com.ops.deploy.security.RequireRole;
import com.ops.deploy.security.ResourceAccessService;
import com.ops.deploy.security.ResourceGrantRequest;
import com.ops.deploy.security.ResourceGrantVO;
import com.ops.deploy.user.Role;
import com.ops.deploy.user.UserService;
import com.ops.deploy.user.UserVO;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 服务器管理接口。JWT 认证由 JwtAuthFilter 统一拦截（/api/** 除登录外都需 token），
 * 角色由 @RequireRole 切面校验（ADMIN &gt; OPS），写操作由 @Auditable 审计。
 *
 * <p>可见性隔离（见 {@link ResourceAccessService}）：
 * - list/get：任何登录用户，但 OPS 仅限其可见资源（任一 OPS 创建 ∪ 被管理员分配），
 *   不可见资源对其 404，不泄露存在性；ADMIN 全量；
 * - 创建/更新/删除/连通性测试：OPS 及以上，且仅限其可见资源；
 * - 分配/取消分配（grants）：仅 ADMIN。
 */
@RestController
@RequestMapping("/api/servers")
public class ServerController {

    private final ServerService serverService;
    private final ResourceAccessService accessService;
    private final UserService userService;

    public ServerController(ServerService serverService,
                            ResourceAccessService accessService,
                            UserService userService) {
        this.serverService = serverService;
        this.accessService = accessService;
        this.userService = userService;
    }

    @PostMapping
    @RequireRole(Role.OPS)
    @Auditable(action = "SERVER_CREATE", targetType = "SERVER")
    public ResponseEntity<Result<ServerVO>> create(@Valid @RequestBody ServerCreateRequest req,
                                                   @CurrentUser AuthUser user) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Result.ok(serverService.create(req, user.getUserId())));
    }

    @GetMapping
    public Result<List<ServerVO>> list(@CurrentUser AuthUser user) {
        return Result.ok(serverService.listFor(user));
    }

    @GetMapping("/{id}")
    public Result<ServerVO> get(@PathVariable Long id, @CurrentUser AuthUser user) {
        return Result.ok(serverService.viewFor(id, user));
    }

    @PutMapping("/{id}")
    @RequireRole(Role.OPS)
    @Auditable(action = "SERVER_UPDATE", targetType = "SERVER")
    public Result<ServerVO> update(@PathVariable Long id, @Valid @RequestBody ServerUpdateRequest req,
                                   @CurrentUser AuthUser user) {
        return Result.ok(serverService.updateFor(id, req, user));
    }

    @DeleteMapping("/{id}")
    @RequireRole(Role.OPS)
    @Auditable(action = "SERVER_DELETE", targetType = "SERVER")
    public Result<Void> delete(@PathVariable Long id, @CurrentUser AuthUser user) {
        serverService.deleteFor(id, user);
        return Result.<Void>ok(null);
    }

    @PostMapping("/{id}/test")
    @RequireRole(Role.OPS)
    @Auditable(action = "SERVER_TEST", targetType = "SERVER")
    public Result<ServerTestResultVO> test(@PathVariable Long id, @CurrentUser AuthUser user) {
        return Result.ok(serverService.testConnectionFor(id, user));
    }

    // ---------- 资源分配管理（仅 ADMIN，把服务器/运维可见资源分配给指定用户） ----------

    @GetMapping("/{id}/grants")
    @RequireRole(Role.ADMIN)
    public Result<List<ResourceGrantVO>> listGrants(@PathVariable Long id) {
        return Result.ok(accessService.listServerGrants(id));
    }

    /** 分配候选用户列表（ADMIN 可见）：仅返回 id + 用户名 + 角色 + 启用状态，
     *  不回传 projectIds（避免泄露候选人在其他项目的成员关系）。 */
    @GetMapping("/grant-candidates")
    @RequireRole(Role.ADMIN)
    public Result<List<UserVO>> grantCandidates() {
        List<UserVO> candidates = userService.list().stream()
                .map(v -> {
                    UserVO vo = new UserVO();
                    vo.setId(v.getId());
                    vo.setUsername(v.getUsername());
                    vo.setRole(v.getRole());
                    vo.setEnabled(v.isEnabled());
                    vo.setCreatedAt(v.getCreatedAt());
                    return vo;
                })
                .collect(Collectors.toList());
        return Result.ok(candidates);
    }

    @PostMapping("/{id}/grants")
    @RequireRole(Role.ADMIN)
    @Auditable(action = "SERVER_GRANT_ADD", targetType = "SERVER")
    public Result<ResourceGrantVO> addGrant(@PathVariable Long id,
                                            @Valid @RequestBody ResourceGrantRequest req,
                                            @CurrentUser AuthUser user) {
        return Result.ok(accessService.addServerGrant(id, req.getUserId(), user));
    }

    @DeleteMapping("/{id}/grants/{userId}")
    @RequireRole(Role.ADMIN)
    @Auditable(action = "SERVER_GRANT_REMOVE", targetType = "SERVER")
    public Result<Void> removeGrant(@PathVariable Long id, @PathVariable Long userId,
                                    @CurrentUser AuthUser user) {
        accessService.removeServerGrant(id, userId, user);
        return Result.<Void>ok(null);
    }
}
