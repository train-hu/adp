package com.ops.deploy.notify;

import com.ops.deploy.common.Result;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 站内通知接口（/api/notifications）。JWT 认证由 JwtAuthFilter 统一拦截；
 * list / unread-count 无角色限制（任何已登录角色可读，已按项目隔离），
 * read-all / read 为轻量写（仅标记已读），不加 OPS 限制。
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public Result<List<NotificationVO>> list(@CurrentUser AuthUser user) {
        return Result.ok(service.list(user));
    }

    /** 未读数量（前端顶栏角标）。 */
    @GetMapping("/unread-count")
    public Result<Map<String, Long>> unreadCount(@CurrentUser AuthUser user) {
        return Result.ok(Map.of("count", service.unreadCount(user)));
    }

    @PostMapping("/read-all")
    public Result<Map<String, Integer>> readAll(@CurrentUser AuthUser user) {
        return Result.ok(Map.of("affected", service.readAll(user)));
    }

    @PostMapping("/{id}/read")
    public Result<Void> read(@PathVariable Long id, @CurrentUser AuthUser user) {
        service.read(id, user);
        return Result.ok(null);
    }
}
