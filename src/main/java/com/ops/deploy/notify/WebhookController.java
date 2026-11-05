package com.ops.deploy.notify;

import com.ops.deploy.audit.Auditable;
import com.ops.deploy.common.Result;
import com.ops.deploy.security.RequireRole;
import com.ops.deploy.user.Role;
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
 * webhook 告警配置接口（/api/webhooks，仅 ADMIN）。
 * JWT 认证由 JwtAuthFilter 统一拦截，角色由 @RequireRole 切面校验，写操作由 @Auditable 审计。
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private final WebhookService service;

    public WebhookController(WebhookService service) {
        this.service = service;
    }

    @GetMapping
    @RequireRole(Role.ADMIN)
    public Result<List<WebhookVO>> list() {
        return Result.ok(service.list());
    }

    @PostMapping
    @RequireRole(Role.ADMIN)
    @Auditable(action = "WEBHOOK_CREATE", targetType = "WEBHOOK")
    public ResponseEntity<Result<WebhookVO>> create(@Valid @RequestBody WebhookCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Result.ok(service.create(req)));
    }

    @PutMapping("/{id}")
    @RequireRole(Role.ADMIN)
    @Auditable(action = "WEBHOOK_UPDATE", targetType = "WEBHOOK")
    public Result<WebhookVO> update(@PathVariable Long id, @Valid @RequestBody WebhookUpdateRequest req) {
        return Result.ok(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    @RequireRole(Role.ADMIN)
    @Auditable(action = "WEBHOOK_DELETE", targetType = "WEBHOOK")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.<Void>ok(null);
    }
}
