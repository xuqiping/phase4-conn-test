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
    /** 日志系统 LOG-FR-06：每请求一行耗时摘要，排 MdcUserFilter 之后（MDC 字段已就位）。 */
    private final com.superprogrammer.common.logging.RequestLogFilter requestLogFilter;
    /** 11x 加固 P2-C5：安全门（IP黑名单→全局限流→注入特征），排 MDC 之后（日志含 traceId）。
     *  ObjectProvider：@WebMvcTest 切片不加载该 @Component 时优雅跳过。 */
    private final org.springframework.beans.factory.ObjectProvider<com.superprogrammer.common.security.SecurityGateFilter> securityGateFilterProvider;
    private final ObjectMapper objectMapper;
    /** S5 F2：回调 HMAC 模式热更读取（system_settings security.runtime.callback.hmac-mode）。 */
    private final com.superprogrammer.system.service.SystemSettingService systemSettingService;
    /** S5 F2：回调鉴权结果计数（可选依赖，缺席不影响鉴权）。 */
    private final org.springframework.beans.factory.ObjectProvider<com.superprogrammer.common.metrics.BizMetrics> bizMetricsProvider;

    /** Sidecar 回调共享密钥（安全审计 #1）。env RUNTIME_CALLBACK_TOKEN。空 → fail-closed。 */
    @Value("${runtime.callback.token:}")
    private String runtimeCallbackToken;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 禁用CSRF（前后端分离，使用JWT）
                .csrf(AbstractHttpConfigurer::disable)
                // CORS 内联进安全链（corsConfigurationSource bean，见 CorsConfig）——预检 OPTIONS 须在
                // 授权判定之前处理，否则白名单 Origin 的跨域预检被 anyRequest().authenticated() 401 截杀。
                .cors(org.springframework.security.config.Customizer.withDefaults())
                // 安全体系 S1 · SEC-FR-002 安全响应头：CSP / X-Frame-Options / nosniff / Referrer-Policy。
                // style-src 'unsafe-inline' 是 Naive UI 内联样式的放行（plan 联动点）；HSTS 待 HTTPS 落地后开启。
                .headers(headers -> headers
                        .contentTypeOptions(org.springframework.security.config.Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(ref -> ref.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                        .ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; style-src 'self' 'unsafe-inline'; "
                                        + "img-src 'self' data: blob:; media-src 'self' blob:"))
                )
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
                        // 认证系统增强：通道开关公开查询（前端登录页渲染依赖，仅返布尔标志无密钥）
                        .requestMatchers("/api/auth/channels").permitAll()
                        // 认证系统增强：邮箱激活/重发（公开端点，注册后激活场景）
                        .requestMatchers("/api/auth/verify/email").permitAll()
                        .requestMatchers("/api/auth/resend/email").permitAll()
                        // 认证系统增强：手机验证码登录/发码 + 滑块验证码（公开端点）
                        .requestMatchers("/api/auth/sms/code").permitAll()
                        .requestMatchers("/api/auth/login/sms").permitAll()
                        .requestMatchers("/api/auth/captcha").permitAll()
                        .requestMatchers("/api/auth/captcha/verify").permitAll()
                        // 认证系统增强：微信扫码登录（公开端点，微信回调 GET）
                        .requestMatchers("/api/auth/login/wechat/redirect").permitAll()
                        .requestMatchers("/api/auth/login/wechat/callback").permitAll()
                        // 安全体系 S5 · SEC-FR-006（A6 TOTP）：两步登录第二屏公开（mfaToken 即凭证，
                        // 5min 一次性+5 次试错封顶在 AuthService 内自限）；bind/status/unbind 走认证
                        .requestMatchers("/api/auth/mfa/verify").permitAll()
                        // 认证系统增强：找回密码（公开端点）
                        .requestMatchers("/api/auth/password/forgot").permitAll()
                        .requestMatchers("/api/auth/password/reset").permitAll()
                        .requestMatchers("/api/runtime/callbacks/**").permitAll()
                        // Ark 参考视频回拉：无 JWT，但必须通过 HMAC 签名和短期 expires 校验。
                        .requestMatchers("/api/media/reference/**").permitAll()
                        // WebSocket端点（通过拦截器认证）
                        .requestMatchers("/ws/chat").permitAll()
                        // 运维系统 OPS-FR-01：健康检查/指标端点 permitAll（Prometheus 抓取与部署探活无 JWT）。
                        // 暴露面控制不在这一层——Nginx 不反代 /actuator + 防火墙仅内网（见 application.yml 红线注释）。
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
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
                // 日志系统 LOG-FR-06：排 MdcUserFilter 之后，摘要行含 userId/traceId（MDC）
                .addFilterAfter(requestLogFilter, com.superprogrammer.common.logging.MdcUserFilter.class)
                // 计费归户：排 JWT 之后，从 principal 种 userId（自动计费基础设施）
                .addFilterAfter(billingContextFilter, JwtAuthenticationFilter.class)
                // 安全审计 #1 + S5 F2：sidecar 回调共享密钥 + HMAC 防重放（permitAll 路径上的独立咽喉点；
                // 双轨兼容：无签名头回落静态 token，security.runtime.callback.hmac-mode 热更切 enforce）
                .addFilterBefore(new RuntimeCallbackSecurityFilter(runtimeCallbackToken,
                                systemSettingService::getRuntimeCallbackHmacMode, bizMetricsProvider.getIfAvailable()),
                        JwtAuthenticationFilter.class);

        // 11x 加固 P2-C5：安全门排 MDC 之后（事件日志含 traceId/userId）；切片缺 bean 时跳过
        com.superprogrammer.common.security.SecurityGateFilter securityGateFilter =
                securityGateFilterProvider.getIfAvailable();
        if (securityGateFilter != null) {
            http.addFilterAfter(securityGateFilter, com.superprogrammer.common.logging.MdcUserFilter.class);
        }

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
