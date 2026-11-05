package com.ops.deploy.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记控制器方法的参数为“当前已认证用户”，由 {@link CurrentUserArgumentResolver}
 * 从 request attribute（JwtAuthFilter 注入）解析。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
