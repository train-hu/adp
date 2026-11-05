package com.ops.deploy.dashboard;

import com.ops.deploy.common.Result;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仪表盘接口（/api/dashboard）。任何已登录角色可访问（统计已按可见项目隔离）。
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping
    public Result<DashboardStatsVO> stats(@CurrentUser AuthUser user) {
        return Result.ok(service.stats(user));
    }
}
