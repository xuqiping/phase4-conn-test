// agent-platform/backend/src/main/java/com/superprogrammer/auth/security/JwtAuthenticationFilter.java
package com.superprogrammer.auth.security;

import com.superprogrammer.auth.service.AuthService;
import com.superprogrammer.auth.service.SessionService;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.result.R;
import com.superprogrammer.common.security.BanService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final AuthService authService;
    private final SessionService sessionService;
    /** ObjectProvider 延迟取：@WebMvcTest 切片不加载 BanService（common.security 包），
     *  强依赖会让全部 web 切片测试崩上下文；生产全量扫描必有该 bean。 */
    private final org.springframework.beans.factory.ObjectProvider<BanService> banServiceProvider;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String token = extractToken(request);

        if (StringUtils.hasText(token) && jwtUtil.isTokenValid(token)) {
            // 检查token类型
            String type = jwtUtil.getTypeFromToken(token);
            if (!"access".equals(type)) {
                filterChain.doFilter(request, response);
                return;
            }

            // 检查Redis黑名单
            String jti = jwtUtil.getTokenId(token);
            if (authService.isTokenBlacklisted(jti)) {
                log.warn("Token已失效(jti={}): {}", jti, request.getRequestURI());
                filterChain.doFilter(request, response);
                return;
            }

            // 提取用户信息
            Long userId = jwtUtil.getUserIdFromToken(token);

            // 安全体系 S2 · A8 单点登录（SEC-FR-008）：sid 比对在黑名单之后；
            // 被踢/旧无 sid token → 401 + 40104 固定话术（不透传额外信息）。
            // 开关关闭或 Redis 故障 → isCurrent 降级放行（可用性 > 强制力，与 S1 一致）。
            String sid = jwtUtil.getSidFromToken(token);
            if (!sessionService.isCurrent(userId, sid)) {
                log.warn("会话已被踢出(单点登录) userId={} uri={}", userId, request.getRequestURI());
                writeSessionKicked(response);
                return;
            }

            // 11x 加固 P1-C3：ban 标记兜底（单点登录关时 isCurrent 恒 true，靠它即时踢封号用户）。
            // 固定 SESSION_KICKED 话术——不透传「被封」防探测枚举。
            BanService banService = banServiceProvider.getIfAvailable();
            if (banService != null && banService.isBanned(userId)) {
                log.warn("请求命中 ban 标记(已封号/禁用/锁定) userId={} uri={}", userId, request.getRequestURI());
                writeSessionKicked(response);
                return;
            }

            String username = jwtUtil.getUsernameFromToken(token);
            List<String> roles = jwtUtil.getRolesFromToken(token);

            // 查询用户完整权限
            List<String> permissions = authService.getUserPermissionCodes(userId);

            // 构建GrantedAuthority列表（包含角色和权限）
            List<SimpleGrantedAuthority> authorities = new ArrayList<>(permissions.stream()
                    .map(SimpleGrantedAuthority::new)
                    .collect(Collectors.toList()));
            if (roles != null) {
                authorities.addAll(roles.stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .toList());
            }

            // 设置SecurityContext
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userId, username, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);

            log.debug("JWT认证成功: userId={}, username={}, permissions={}",
                    userId, username, permissions);
        }

        filterChain.doFilter(request, response);
    }

    /** 401 SESSION_KICKED 固定话术（单点踢出与 ban 标记共用，不透传原因防探测）。 */
    private void writeSessionKicked(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(R.fail(ErrorCode.SESSION_KICKED)));
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
