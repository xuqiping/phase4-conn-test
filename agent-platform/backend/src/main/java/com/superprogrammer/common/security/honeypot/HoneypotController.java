// agent-platform/backend/src/main/java/com/superprogrammer/common/security/honeypot/HoneypotController.java
package com.superprogrammer.common.security.honeypot;

import com.superprogrammer.common.metrics.BizMetrics;
import com.superprogrammer.common.security.ClientIpResolver;
import com.superprogrammer.common.security.SecurityEventService;
import com.superprogrammer.common.security.SecurityEventTypes;
import com.superprogrammer.system.service.SystemSettingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 安全体系 S5 · SEC-FR-133（M4 蜜罐端点）：扫描器探测可感知。
 *
 * <p>注册四条 canary 路由（正常用户/前端永远不会请求，命中者基本是自动化扫描器）：
 * {@code /wp-admin}（WordPress 后台探测）、{@code /.env}（环境变量泄露经典目标）、
 * {@code /.git/config}（源码仓库泄露）、{@code /api/admin/config.php}（PHP 配置面）。
 *
 * <p>响应策略：<b>404 伪装</b>——返回与 Spring 默认 404 同构的 JSON，不暴露「这是蜜罐」，
 * 扫描器当作普通死链继续；同时落 KIND_HONEYPOT HIGH 事件（走既有 AlertRouter → 钉钉即时告警）
 * + {@code security.honeypot.hit{path}} 指标。
 *
 * <p>**防刷爆**：复用 {@link SecurityEventService} 既有去重窗（同 type+ip 5min 只落 1 行）——
 * 扫描器高频打蜜罐不会灌爆事件表（比计划的 60s 聚合更紧）。
 *
 * <p>开关 {@code security.honeypot.enabled}（默认开，EDITABLE_KEYS 热更）：关=纯 404 不告警
 * （误报高时降级用）。事件落库失败/开关读取异常均吞——蜜罐绝不影响响应本身。
 */
@Slf4j
@RestController
public class HoneypotController {

    private final SecurityEventService securityEventService;
    private final SystemSettingService systemSettingService;
    private final ClientIpResolver clientIpResolver;
    /** 可选依赖：metrics bean 缺席（切片测试）时跳过计数。 */
    private final BizMetrics bizMetrics;

    public HoneypotController(SecurityEventService securityEventService,
                              SystemSettingService systemSettingService,
                              ClientIpResolver clientIpResolver,
                              BizMetrics bizMetrics) {
        this.securityEventService = securityEventService;
        this.systemSettingService = systemSettingService;
        this.clientIpResolver = clientIpResolver;
        this.bizMetrics = bizMetrics;
    }

    @RequestMapping("/wp-admin")
    public ResponseEntity<Map<String, Object>> wpAdmin(HttpServletRequest request) {
        return respond(request, "/wp-admin");
    }

    @RequestMapping("/.env")
    public ResponseEntity<Map<String, Object>> env(HttpServletRequest request) {
        return respond(request, "/.env");
    }

    @RequestMapping("/.git/config")
    public ResponseEntity<Map<String, Object>> gitConfig(HttpServletRequest request) {
        return respond(request, "/.git/config");
    }

    @RequestMapping("/api/admin/config.php")
    public ResponseEntity<Map<String, Object>> phpConfig(HttpServletRequest request) {
        return respond(request, "/api/admin/config.php");
    }

    /** 统一处理：告警（可开关）+ 404 伪装（与 Spring 默认 404 JSON 同构，不暴露蜜罐身份）。 */
    private ResponseEntity<Map<String, Object>> respond(HttpServletRequest request, String path) {
        try {
            if (systemSettingService.getHoneypotEnabled()) {
                String ip = clientIpResolver.resolve(request);
                String detail = "{\"path\":\"" + path + "\",\"userAgent\":"
                        + quote(request.getHeader("User-Agent")) + "}";
                securityEventService.record(
                        SecurityEventTypes.KIND_HONEYPOT, SecurityEventTypes.SEV_HIGH,
                        null, ip, "HONEYPOT", detail, SecurityEventTypes.ACT_NONE);
                if (bizMetrics != null) {
                    bizMetrics.honeypotHit(path);
                }
                log.warn("蜜罐命中 path={} ip={} ua={}", path, ip, request.getHeader("User-Agent"));
            }
        } catch (Exception e) {
            // 检测层不自残：告警失败不影响 404 响应本身
            log.warn("蜜罐告警失败(已吞) path={} : {}", path, e.getMessage());
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "status", 404,
                "error", "Not Found",
                "path", path));
    }

    private static String quote(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
