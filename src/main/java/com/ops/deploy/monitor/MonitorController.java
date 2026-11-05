package com.ops.deploy.monitor;

import com.ops.deploy.common.Result;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 监控指标接口（/api/monitor/summary）。
 * 任何已登录角色可访问（部署/实例口径已按可见项目隔离，JVM/HTTP 为平台自身指标）。
 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private final MonitorService service;

    public MonitorController(MonitorService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public Result<MonitorStatsVO> summary(@CurrentUser AuthUser user) {
        return Result.ok(service.summary(user));
    }
}
