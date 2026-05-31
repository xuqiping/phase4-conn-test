// agent-platform/backend/src/main/java/com/superprogrammer/auth/security/PermissionEvaluator.java
package com.superprogrammer.auth.security;

import com.superprogrammer.auth.entity.Permission;
import com.superprogrammer.auth.mapper.PermissionMapper;
import com.superprogrammer.auth.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionEvaluator {

    private final UserMapper userMapper;

    /**
     * 检查当前用户是否拥有指定权限
     * @param authentication 当前认证信息
     * @param permissionCode 权限编码，如 "agent:create"
     * @return 是否有权限
     */
    public boolean hasPermission(Authentication authentication, String permissionCode) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        return authorities.contains(permissionCode);
    }
}
