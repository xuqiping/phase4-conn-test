package com.superprogrammer.common.security;

import com.superprogrammer.auth.security.RequirePermission;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 权限注解覆盖扫描器（安全体系 S2 · B1，SEC-FR-010）：启动期扫全部 @RequestMapping 端点，
 * 无 {@code @RequirePermission}/{@code @PreAuthorize} 且不在 {@link SecurityEndpointRegistry}
 * 白名单 → WARN 清单 + {@code security.endpoints.unguarded} 计数指标。
 *
 * <p>目的：任何新端点漏标方法级权限即被发现（防「裸端点」随迭代悄悄混入）。
 * 只告警不阻断——是否放行由人评审后登记进白名单， scanner 不替人做安全决定。
 */
@Slf4j
@Component
public class PermissionCoverageScanner implements ApplicationRunner {

    private final RequestMappingHandlerMapping handlerMapping;
    private final MeterRegistry meterRegistry;

    /** Gauge 只注册一次（指标红线④）。 */
    private final AtomicInteger unguardedGauge = new AtomicInteger(0);
    private final AtomicBoolean gaugeRegistered = new AtomicBoolean(false);

    public PermissionCoverageScanner(RequestMappingHandlerMapping handlerMapping, MeterRegistry meterRegistry) {
        this.handlerMapping = handlerMapping;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void run(ApplicationArguments args) {
        int guarded = 0;
        Set<String> reviewed = new TreeSet<>();
        List<String> unguarded = new ArrayList<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod hm = entry.getValue();
            if (hasGuard(hm)) {
                guarded++;
                continue;
            }
            for (String path : patternsOf(entry.getKey())) {
                if (!path.startsWith("/api")) {
                    continue; // 非 API（静态资源/error 等）不评
                }
                SecurityEndpointRegistry.Coverage c = SecurityEndpointRegistry.categorize(path);
                if (c == SecurityEndpointRegistry.Coverage.PUBLIC_WHITELIST) {
                    continue;
                }
                String line = path + " -> " + hm.getShortLogMessage();
                if (c == SecurityEndpointRegistry.Coverage.AUTH_ONLY_REVIEWED) {
                    reviewed.add(line);
                } else {
                    unguarded.add(line);
                }
            }
        }

        log.info("权限覆盖扫描(B1): 注解保护={} 已评审仅登录={} 待评审未覆盖={}",
                guarded, reviewed.size(), unguarded.size());
        for (String line : unguarded) {
            log.warn("权限覆盖扫描(B1) 未覆盖端点（无 @RequirePermission/@PreAuthorize 且未登记白名单）: {}", line);
        }

        // 计数指标（Gauge 注册一次；数值即启动扫描结果，供看板/告警消费）
        if (gaugeRegistered.compareAndSet(false, true)) {
            meterRegistry.gauge("security.endpoints.unguarded", unguardedGauge);
        }
        unguardedGauge.set(unguarded.size());
    }

    /** 方法或类上有 @RequirePermission / @PreAuthorize 即视为已保护。 */
    static boolean hasGuard(HandlerMethod hm) {
        return AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(), RequirePermission.class) != null
                || AnnotatedElementUtils.findMergedAnnotation(hm.getBeanType(), RequirePermission.class) != null
                || AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(), PreAuthorize.class) != null
                || AnnotatedElementUtils.findMergedAnnotation(hm.getBeanType(), PreAuthorize.class) != null;
    }

    /** 兼容 PathPatterns（Boot 3 默认）与 Ant Patterns 两种映射条件。 */
    static Set<String> patternsOf(RequestMappingInfo info) {
        PathPatternsRequestCondition ppc = info.getPathPatternsCondition();
        if (ppc != null) {
            return ppc.getPatternValues();
        }
        PatternsRequestCondition pc = info.getPatternsCondition();
        return pc == null ? Set.of() : pc.getPatterns();
    }
}
