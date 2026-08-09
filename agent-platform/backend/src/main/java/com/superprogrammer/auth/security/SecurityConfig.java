// agent-platform/backend/src/main/java/com/superprogrammer/auth/security/SecurityConfig.java
package com.superprogrammer.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.billing.context.BillingContextFilter;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.result.R;
import com.superprogrammer.runtime.security.RuntimeCallbackSecurityFilter;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    /** 计费归户：请求入口种 userId，排 JWT 之后（principal 已就位）。 */
    private final BillingContextFilter billingContextFilter;
    /** 日志系统 LOG-FR-03：userId/username/clientIp 入 MDC，排 JWT 之后。 */
    private final com.superprogrammer.common.logging.MdcUserFilter mdcUserFilter;
    private final ObjectMapper objectMapper;

    /** Sidecar 回调共享密钥（安全审计 #1）。env RUNTIME_CALLBACK_TOKEN。空 → fail-closed。 */
    @Value("${runtime.callback.token:}")
    private String runtimeCallbackToken;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 禁用CSRF（前后端分离，使用JWT）
                .csrf(AbstractHttpConfigurer::disable)
                // 无状态Session
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 配置请求授权
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        // 白名单路径：登录、注册、刷新Token
                        .requestMatchers("/api/auth/login").permitAll()
                        .requestMatchers("/api/auth/register").permitAll()
                        .requestMatchers("/api/auth/refresh").permitAll()
                        .requestMatchers("/api/auth/login/dingtalk").permitAll()
                        .requestMatchers("/api/runtime/callbacks/**").permitAll()
                        // WebSocket端点（通过拦截器认证）
                        .requestMatchers("/ws/chat").permitAll()
                        // 其他路径需要认证
                        .anyRequest().authenticated()
                )
                // 异常处理
                .exceptionHandling(exceptions -> exceptions
                        // 未认证处理
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setContentType("application/json;charset=UTF-8");
                            response.setStatus(401);
                            R<Void> result = R.fail(ErrorCode.UNAUTHORIZED);
                            response.getWriter().write(objectMapper.writeValueAsString(result));
                        })
                        // 无权限处理
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setContentType("application/json;charset=UTF-8");
                            response.setStatus(403);
                            R<Void> result = R.fail(ErrorCode.FORBIDDEN);
                            response.getWriter().write(objectMapper.writeValueAsString(result));
                        })
                )
                // 添加JWT过滤器
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // 日志系统 LOG-FR-03：排 JWT 之后，SecurityContext 已就绪 → userId/username/clientIp 入 MDC
                .addFilterAfter(mdcUserFilter, JwtAuthenticationFilter.class)
                // 计费归户：排 JWT 之后，从 principal 种 userId（自动计费基础设施）
                .addFilterAfter(billingContextFilter, JwtAuthenticationFilter.class)
                // 安全审计 #1：sidecar 回调端点共享密钥校验（permitAll 路径上的独立咽喉点）
                .addFilterBefore(new RuntimeCallbackSecurityFilter(runtimeCallbackToken), JwtAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
