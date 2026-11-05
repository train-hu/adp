package com.ops.deploy.security;

import com.ops.deploy.common.BizException;
import com.ops.deploy.common.ErrorCode;
import com.ops.deploy.user.Role;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

/**
 * RBAC 切面：校验 {@link RequireRole} 注解声明的角色。
 *
 * <p>角色层级 ADMIN &gt; OPS：持有角色等级 &ge; 声明角色（取列出角色中最低等级）即放行。
 * 无 {@link RequireRole} 注解的已登录接口默认任何登录角色可访问。
 *
 * <p>{@link Order} 取较小值（更高优先级，外层），保证 RBAC 校验先于审计切面执行：
 * RBAC 拒绝时不会触发审计落库。
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequireRoleAspect {

    static final int LEVEL_ADMIN = 3;
    static final int LEVEL_OPS = 2;

    @Around("@annotation(com.ops.deploy.security.RequireRole)")
    public Object checkRole(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        RequireRole ann = method.getAnnotation(RequireRole.class);
        if (ann == null) {
            return pjp.proceed();
        }

        AuthUser user = resolveUser(pjp.getArgs());
        int userLevel = levelOf(user);
        int requiredLevel = minimumRequiredLevel(ann.value());
        if (userLevel < requiredLevel) {
            throw new BizException(ErrorCode.FORBIDDEN, "insufficient role for this operation");
        }
        return pjp.proceed();
    }

    /**
     * 解析当前用户：优先从方法参数中的 {@link AuthUser} 取（便于单测与内部调用），
     * 否则回退到 request attribute（Web 请求由 JwtAuthFilter 注入）。
     */
    private AuthUser resolveUser(Object[] args) {
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof AuthUser) {
                    return (AuthUser) arg;
                }
            }
        }
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                Object attr = attrs.getRequest().getAttribute(JwtAuthFilter.CURRENT_USER_ATTRIBUTE);
                if (attr instanceof AuthUser) {
                    return (AuthUser) attr;
                }
            }
        } catch (RuntimeException ignored) {
            // 非 Web 上下文，忽略
        }
        return null;
    }

    static int levelOf(AuthUser user) {
        if (user == null || user.getRole() == null) {
            return 0;
        }
        try {
            return levelOf(Role.valueOf(user.getRole()));
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    static int levelOf(Role role) {
        return switch (role) {
            case ADMIN -> LEVEL_ADMIN;
            case OPS -> LEVEL_OPS;
        };
    }

    /** 多角色取最低等级（最宽松）作为门槛：持有等级 &ge; 任一列出角色即放行。 */
    static int minimumRequiredLevel(Role[] roles) {
        int min = Integer.MAX_VALUE;
        for (Role r : roles) {
            min = Math.min(min, levelOf(r));
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }
}
