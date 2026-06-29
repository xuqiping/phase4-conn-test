// agent-platform/backend/src/main/java/com/superprogrammer/auth/security/SecurityConfig.java
package com.superprogrammer.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.result.R;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
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
    private final ObjectMapper objectMapper;

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
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
