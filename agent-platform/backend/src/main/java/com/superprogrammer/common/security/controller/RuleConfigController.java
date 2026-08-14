// agent-platform/backend/src/main/java/com/superprogrammer/common/security/controller/RuleConfigController.java
package com.superprogrammer.common.security.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.common.result.R;
import com.superprogrammer.system.service.SystemSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 安全规则配置端点（11x 加固 · P4-C12）：security.* 阈值/开关读写，白名单键 + 热生效。
 *
 * <p>三权之一：security:rule:manage。webhook secret 只写不读（GET 恒返掩码）。</p>
 */
@RestController
@RequestMapping("/api/security/rules")
@RequiredArgsConstructor
public class RuleConfigController {

    private final SystemSettingService systemSettingService;

    /** 可配置键白名单（键 → 默认值；GET 未设置时返默认）。 */
    private static final Map<String, String> EDITABLE_KEYS = new LinkedHashMap<>() {{
        // 总闸/响应/告警
        put("security.rate.enabled", "true");
        put("security.ai.fence.enabled", "true");
        put("security.ai.kb-scan.enabled", "true");
        put("security.ai.output-mask.enabled", "true");
        put("security.ai.prompt-leak.enabled", "true");
        // SEC-FR-056 会话 token 上限（0=关）
        put("security.llm.session-token-cap", "500000");
        // 安全体系 S4 · SEC-FR-031 上传 magic number 嗅探（F-2）
        put("security.upload.magic-sniff.enabled", "true");
        // 安全体系 S4 · SEC-FR-032 解析炸弹上限（F-3：像素/解析文本）
        put("security.upload.max-pixels", "100000000");
        put("security.upload.max-parse-chars", "100000");
        // 安全体系 S4 · SEC-FR-033 per-user 存储配额 MB（F-4，0=关）
        put("security.user.storage-quota-mb", "2048");
        // 安全体系 S5 · SEC-FR-004+ refresh token 旋转（A4，关=refresh 固定复用旧行为）
        put("security.auth.refresh-rotation.enabled", "true");
        put("security.response.auto_enabled", "true");
        put("security.response.auto_ip_block", "true");
        put("security.response.auto_account_lock", "true");
        put("security.alert.enabled", "true");
        put("security.alert.webhook.url", "");
        put("security.alert.webhook.secret", "");
        // 限流阈值
        put("security.rate.global_ip.max", "600");
        put("security.rate.chat_send.max", "20");
        put("security.rate.media_submit.max", "10");
        // 安全体系 S3 · SEC-FR-055（LLM10 用户直触入口补齐）
        put("security.rate.canvas_run.max", "10");
        put("security.rate.rag_ask.max", "10");
        put("security.rate.workflow_run.max", "10");
        // 安全体系 S4 · SEC-FR-124 上传频率（L5 补齐，5 入口共用）
        put("security.rate.upload_file.max", "10");
        // 规则阈值（P3 默认）
        put("security.rule.idor.threshold", "10");
        put("security.rule.exfil.threshold", "500");
        put("security.rule.points.threshold", "10000");
        put("security.rule.media.thresholdFen", "10000");
        put("security.rule.prompt.repeat", "3");
        put("security.rule.token.ips", "3");
    }};

    /** 只写不读的敏感键（GET 返掩码占位）。 */
    private static final Set<String> SECRET_KEYS = Set.of("security.alert.webhook.secret");

    /** 读全部可配键（未设置返默认；secret 掩码）。 */
    @GetMapping
    @RequirePermission("security:rule:manage")
    public R<Map<String, String>> list() {
        Map<String, String> result = new LinkedHashMap<>();
        EDITABLE_KEYS.forEach((key, def) -> {
            if (SECRET_KEYS.contains(key)) {
                result.put(key, "");
                return;
            }
            String value = null;
            try {
                value = systemSettingService.getSettingValue(key);
            } catch (Exception ignored) {
                // 读失败用默认
            }
            result.put(key, value == null ? def : value);
        });
        return R.ok(result);
    }

    /** 改单个键（白名单校验 + 长度上限；@AuditLog 留痕）。 */
    @PutMapping("/{key}")
    @RequirePermission("security:rule:manage")
    @AuditLog(module = "security", action = "rule_config_update", targetType = "system_setting")
    public R<Void> update(@PathVariable String key, @RequestBody Map<String, String> body) {
        if (!EDITABLE_KEYS.containsKey(key)) {
            return R.fail(400, "不可配置的键: " + key);
        }
        String value = body == null ? null : body.get("value");
        if (value == null || value.length() > 512) {
            return R.fail(400, "value 必填且 ≤512 字符");
        }
        systemSettingService.upsertSettingValue(key, value, "安全管理页配置");
        return R.ok(null);
    }
}
