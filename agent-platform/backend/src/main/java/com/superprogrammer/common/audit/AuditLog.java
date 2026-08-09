package com.superprogrammer.common.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作审计注解（日志系统 LOG-FR-10）：敏感写操作标此注解 → AOP 自动异步落 audit_logs。
 *
 * <p>留痕内容：谁（MDC userId/username）/何时/哪个模块动作/对象/参数摘要（脱敏截断）/结果/IP/UA/traceId。
 * targetId 约定：取方法第一个 Long 参数（通常是 @PathVariable id），无则空。
 *
 * <p>用法：{@code @AuditLog(module = "role", action = "update_permissions", targetType = "role")}
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {

    /** 业务模块：auth/user/role/agent/kb/system/billing... */
    String module();

    /** 动作：role_update/kb_delete/agent_publish... */
    String action();

    /** 对象类型：role/agent/kb/document...（可空） */
    String targetType() default "";
}
