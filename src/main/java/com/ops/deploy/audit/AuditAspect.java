package com.ops.deploy.audit;

import com.ops.deploy.security.AuthUser;
import com.ops.deploy.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;

/**
 * 审计切面：对标注 {@link Auditable} 的 Controller 方法写审计日志。
 *
 * <p>记录：操作人（用户名/角色）、action、targetType、参数摘要（detail，截断 2000 字符）、
 * 客户端 IP（含 X-Forwarded-For 第一跳）、时间。
 *
 * <p>{@link Order} 取较大值（较低优先级，内层），保证在 RBAC 切面（外层）之后执行：
 * RBAC 拒绝时不会触发审计落库。
 *
 * <p>审计写库失败不影响主流程：try-catch 吞异常 + error 日志。
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);
    private static final int DETAIL_MAX_LEN = 2000;

    private final AuditRepository auditRepository;

    public AuditAspect(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    @Around("@annotation(com.ops.deploy.audit.Auditable)")
    public Object audit(ProceedingJoinPoint pjp) throws Throwable {
        Object result = pjp.proceed();
        try {
            save(pjp);
        } catch (Exception e) {
            log.error("audit log save failed (ignored to not affect main flow)", e);
        }
        return result;
    }

    private void save(ProceedingJoinPoint pjp) {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        Auditable ann = method.getAnnotation(Auditable.class);
        if (ann == null) {
            return;
        }

        Object[] args = pjp.getArgs();
        AuthUser user = resolveUser(args, currentRequest());

        AuditLog entry = new AuditLog();
        if (user != null) {
            entry.setUserId(user.getUserId());
            entry.setUsername(user.getUsername());
            entry.setRole(user.getRole());
        }
        entry.setAction(ann.action());
        entry.setTargetType(ann.targetType());
        // target_id 保持简单：注解显式声明 action + targetType，targetId 暂留空
        entry.setTargetId(null);
        entry.setDetail(buildDetail(method, args));
        entry.setIp(resolveIp(currentRequest()));
        entry.setCreatedAt(LocalDateTime.now());

        auditRepository.save(entry);
    }

    /** 操作人：优先方法参数中的 AuthUser，否则回退 request attribute。 */
    private AuthUser resolveUser(Object[] args, HttpServletRequest request) {
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof AuthUser) {
                    return (AuthUser) arg;
                }
            }
        }
        if (request != null) {
            Object attr = request.getAttribute(JwtAuthFilter.CURRENT_USER_ATTRIBUTE);
            if (attr instanceof AuthUser) {
                return (AuthUser) attr;
            }
        }
        return null;
    }

    /** detail = 方法名 + 参数摘要，截断到 2000 字符。 */
    private String buildDetail(Method method, Object[] args) {
        String detail = method.getName() + " args=" + Arrays.toString(args);
        if (detail.length() > DETAIL_MAX_LEN) {
            detail = detail.substring(0, DETAIL_MAX_LEN);
        }
        return detail;
    }

    /** 客户端 IP：优先 X-Forwarded-For 第一跳，否则 remoteAddr。无请求上下文返回 null。 */
    private String resolveIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String ip = request.getRemoteAddr();
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String first = xff.split(",")[0].trim();
            if (!first.isEmpty()) {
                ip = first;
            }
        }
        return ip;
    }

    private HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs != null ? attrs.getRequest() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
