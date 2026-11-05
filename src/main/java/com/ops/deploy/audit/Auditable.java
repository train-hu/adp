package com.ops.deploy.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记控制器方法需要写审计日志。{@link com.ops.deploy.audit.AuditAspect}
 * 会记录操作人（用户名/角色）、action、targetType、参数摘要与客户端 IP。
 *
 * <p>本任务仅切到显式标注此注解的 Controller 方法（示范接线：AuthController.login）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {

    /** 操作名，如 AUTH_LOGIN / DEPLOY_CREATE。 */
    String action();

    /** 操作对象类型，如 USER / PROJECT / DEPLOYMENT。 */
    String targetType() default "";
}
