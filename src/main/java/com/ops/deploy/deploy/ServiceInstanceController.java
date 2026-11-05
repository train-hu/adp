package com.ops.deploy.deploy;

import com.ops.deploy.audit.Auditable;
import com.ops.deploy.common.Result;
import com.ops.deploy.security.AuthUser;
import com.ops.deploy.security.CurrentUser;
import com.ops.deploy.security.RequireRole;
import com.ops.deploy.ssh.SshExecutor;
import com.ops.deploy.user.Role;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 服务实例接口（/api/service-instances，Task 7）。JWT 认证由 JwtAuthFilter 统一拦截，
 * 角色由 @RequireRole 切面校验（ADMIN &gt; OPS），写操作由 @Auditable 审计。
 *
 * <p>start / stop / restart 需 OPS（运维写操作）；list / get 无角色限制（任何已登录角色可读）。
 * logs 通过 SSE 推送 journalctl -f 实时日志（异步线程执行，推送方在 {@link Executor} 上）。
 */
@RestController
@RequestMapping("/api/service-instances")
public class ServiceInstanceController {

    private final ServiceInstanceService service;
    private final Executor logSseExecutor;

    public ServiceInstanceController(ServiceInstanceService service,
                                     @Qualifier("logSseExecutor") Executor logSseExecutor) {
        this.service = service;
        this.logSseExecutor = logSseExecutor;
    }

    @GetMapping
    public Result<List<ServiceInstanceVO>> list(@RequestParam(required = false) Long projectId,
                                                @RequestParam(required = false) Long serverId,
                                                @RequestParam(required = false) Long serviceId,
                                                @CurrentUser AuthUser user) {
        return Result.ok(service.list(projectId, serverId, serviceId, user));
    }

    @GetMapping("/{id}")
    public Result<ServiceInstanceVO> get(@PathVariable Long id, @CurrentUser AuthUser user) {
        return Result.ok(service.get(id, user));
    }

    @PostMapping("/{id}/start")
    @RequireRole(Role.OPS)
    @Auditable(action = "INSTANCE_START", targetType = "SERVICE_INSTANCE")
    public Result<ServiceInstanceVO> start(@PathVariable Long id, @CurrentUser AuthUser user) {
        return Result.ok(service.start(id, user));
    }

    @PostMapping("/{id}/stop")
    @RequireRole(Role.OPS)
    @Auditable(action = "INSTANCE_STOP", targetType = "SERVICE_INSTANCE")
    public Result<ServiceInstanceVO> stop(@PathVariable Long id, @CurrentUser AuthUser user) {
        return Result.ok(service.stop(id, user));
    }

    @PostMapping("/{id}/restart")
    @RequireRole(Role.OPS)
    @Auditable(action = "INSTANCE_RESTART", targetType = "SERVICE_INSTANCE")
    public Result<ServiceInstanceVO> restart(@PathVariable Long id, @CurrentUser AuthUser user) {
        return Result.ok(service.restart(id, user));
    }

    @DeleteMapping("/{id}")
    @RequireRole(Role.OPS)
    @Auditable(action = "INSTANCE_DELETE", targetType = "SERVICE_INSTANCE")
    public Result<Void> delete(@PathVariable Long id, @CurrentUser AuthUser user) {
        service.delete(id, user);
        return Result.ok(null);
    }

    @GetMapping(value = "/{id}/logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter logs(@PathVariable Long id, @CurrentUser AuthUser user) {
        // 权限校验必须在主线程完成（SSE 异步内抛异常只能 completeWithError，状态码丢失）
        service.assertView(id, user);
        SseEmitter emitter = new SseEmitter(30L * 60 * 1000);
        AtomicReference<SshExecutor.StreamHandle> handleRef = new AtomicReference<>();
        Runnable cancel = () -> {
            SshExecutor.StreamHandle h = handleRef.get();
            if (h != null) {
                h.cancel();
            }
        };
        emitter.onCompletion(cancel);
        emitter.onTimeout(() -> {
            cancel.run();
            emitter.complete();
        });
        emitter.onError(t -> cancel.run());
        logSseExecutor.execute(() -> {
            try {
                SshExecutor.StreamHandle handle = service.streamLogs(id, user,
                        line -> {
                            try {
                                emitter.send(SseEmitter.event().name("log").data(line));
                            } catch (Exception e) {
                                // 发送失败（如客户端断开）→ 取消流
                                cancel.run();
                            }
                        },
                        exitCode -> emitter.complete());
                handleRef.set(handle);
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}
