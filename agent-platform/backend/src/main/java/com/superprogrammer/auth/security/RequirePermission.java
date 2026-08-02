// agent-platform/backend/src/main/java/com/superprogrammer/auth/security/RequirePermission.java
package com.superprogrammer.auth.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /**
     * 所需权限编码，格式: resource:action
     * 例如: "agent:create", "user:manage"
     */
    String value();
}
