package com.ops.deploy.user;

import com.ops.deploy.audit.Auditable;
import com.ops.deploy.common.Result;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.security.CurrentUser;
import com.ops.deploy.security.RequireRole;
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

/**
 * 用户管理接口（仅 ADMIN 可访问）：用户 CRUD + 角色分配。
 * JWT 认证由 JwtAuthFilter 统一拦截，角色由 @RequireRole 切面校验，写操作由 @Auditable 审计。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @RequireRole(Role.ADMIN)
    public Result<List<UserVO>> list() {
        return Result.ok(userService.list());
    }

    @GetMapping("/{id}")
    @RequireRole(Role.ADMIN)
    public Result<UserVO> get(@PathVariable Long id) {
        return Result.ok(userService.get(id));
    }

    @PostMapping
    @RequireRole(Role.ADMIN)
    @Auditable(action = "USER_CREATE", targetType = "USER")
    public ResponseEntity<Result<UserVO>> create(@Valid @RequestBody UserCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Result.ok(userService.create(req)));
    }

    @PutMapping("/{id}")
    @RequireRole(Role.ADMIN)
    @Auditable(action = "USER_UPDATE", targetType = "USER")
    public Result<UserVO> update(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest req) {
        return Result.ok(userService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @RequireRole(Role.ADMIN)
    @Auditable(action = "USER_DELETE", targetType = "USER")
    public Result<Void> delete(@PathVariable Long id, @CurrentUser AuthUser user) {
        if (id.equals(user.getUserId())) {
            throw new com.ops.deploy.common.BizException(
                    com.ops.deploy.common.ErrorCode.BAD_REQUEST, "cannot delete your own account");
        }
        userService.delete(id);
        return Result.<Void>ok(null);
    }
}
