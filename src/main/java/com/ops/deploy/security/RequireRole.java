package com.ops.deploy.security;

import com.ops.deploy.user.Role;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法级 RBAC 注解：声明访问该方法所需的最小角色（可列多个，满足其一即可）。
 *
 * <p>角色层级：ADMIN &gt; OPS。持有角色等级 &ge; 任一声明角色即放行。
 * 例如 {@code @RequireRole(Role.OPS)} 表示 ADMIN 与 OPS 均可访问。
 *
 * <p>无此注解的已登录接口默认任何登录角色可访问。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    Role[] value();
}
