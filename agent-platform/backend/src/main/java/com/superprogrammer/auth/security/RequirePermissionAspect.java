// agent-platform/backend/src/main/java/com/superprogrammer/auth/security/RequirePermissionAspect.java
package com.superprogrammer.auth.security;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Aspect
@Component
@RequiredArgsConstructor
public class RequirePermissionAspect {

    private final PermissionEvaluator permissionEvaluator;

    @Around("@annotation(requirePermission)")
    public Object checkPermission(ProceedingJoinPoint joinPoint, RequirePermission requirePermission) throws Throwable {
        String permissionCode = requirePermission.value();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        if (!permissionEvaluator.hasPermission(auth, permissionCode)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        return joinPoint.proceed();
    }
}
