// agent-platform/backend/src/main/java/com/superprogrammer/common/security/ai/OutputSanitizer.java
package com.superprogrammer.common.security.ai;

import com.superprogrammer.common.metrics.BizMetrics;
import com.superprogrammer.common.security.SecurityEventPublisher;
import com.superprogrammer.common.security.event.ApplicationSecurityEvent;
import com.superprogrammer.system.service.SystemSettingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * LLM 输出净化收口（安全体系 S3 · SEC-FR-052/053 / LLM02+LLM07②）：
 * LlmGateway 咽喉唯一出口——同步 {@link #maskSync}；流式 {@link #openStream} 逐 CHUNK。
 *
 * <p>两层：①{@link SensitivePatternCatalog} 敏感模式打码（{@code ***}）；
 * ②{@link PromptLeakDetector} 静态 prompt 指纹遮蔽（命中 → KIND_PROMPT_LEAK HIGH 事件+指标）。
 * 开关：{@code security.ai.output-mask.enabled}（打码）/ {@code security.ai.prompt-leak.enabled}（指纹），
 * 默认全开，RuleConfigView 热调。<b>任何异常透传原文 + ERROR 日志（检测层不自残——净化挂了
 * 宁可漏打码也不能掐断全部对话）</b>。
 *
 * <p>流式 carry：敏感模式最长 40 字符量级，逐 CHUNK 直接扫会跨块漏检——每发扣留尾 40 字符
 * 与下块拼接扫描（末尾在 {@link StreamMasker#flush} 补发）。指纹检测在 (carry+chunk) 窗口上跑，
 * 泄露事件每流只发一次。
 */
@Slf4j
@Component
public class OutputSanitizer {

    /** 流式扣留长度：≥ 最长敏感模式（身份证 18 + 前后看零宽 + kv 值域上限）。 */
    static final int CARRY = 40;

    private final SystemSettingService systemSettingService;
    private final PromptLeakDetector promptLeakDetector;

    /** 横切可选依赖（沉淀范式）：测试/切片无 bean 时降级直通。 */
    @Autowired(required = false)
    private SecurityEventPublisher securityEventPublisher;
    @Autowired(required = false)
    private BizMetrics bizMetrics;

    public OutputSanitizer(SystemSettingService systemSettingService,
                           PromptLeakDetector promptLeakDetector) {
        this.systemSettingService = systemSettingService;
        this.promptLeakDetector = promptLeakDetector;
    }

    /** 同步净化：null/空原样返回；任一层关/异常 → 原文。 */
    public String maskSync(String content, Long userId) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        try {
            String out = content;
            if (systemSettingService.getAiOutputMaskEnabled()) {
                String masked = SensitivePatternCatalog.mask(out);
                if (masked != out) {
                    countMasked();
                    out = masked;
                }
            }
            if (systemSettingService.getAiPromptLeakEnabled()) {
                String leaked = promptLeakDetector.maskIfLeaked(out);
                if (leaked != null) {
                    publishLeak(userId, "SYNC", out.length());
                    out = leaked;
                }
            }
            return out;
        } catch (Exception e) {
            log.error("输出净化异常(透传原文): {}", e.getMessage(), e);
            return content;
        }
    }

    /** 流式净化器：每次订阅一个实例（LlmGateway.chatStream 的 Flux.defer 内创建）。 */
    public StreamMasker openStream(Long userId) {
        return new StreamMasker(userId);
    }

    /** 单流状态：carry 缓冲 + 泄露去重。非线程安全（Reactor 串行 onNext，够用）。 */
    public class StreamMasker {

        private final Long userId;
        private final StringBuilder carry = new StringBuilder();
        private boolean leakPublished = false;

        private StreamMasker(Long userId) {
            this.userId = userId;
        }

        /** 喂一个 CHUNK，返回本拍可安全下发的文本（尾部 ≤CARRY 字符扣留）。 */
        public String feed(String chunk) {
            if (chunk == null || chunk.isEmpty()) {
                return "";
            }
            try {
                boolean maskOn = systemSettingService.getAiOutputMaskEnabled();
                boolean leakOn = systemSettingService.getAiPromptLeakEnabled();
                // 全关且无历史扣留 → 直通零开销
                if (!maskOn && !leakOn && carry.length() == 0) {
                    return chunk;
                }
                carry.append(chunk);
                String buffered = carry.toString();
                String safe;
                if (maskOn) {
                    String masked = SensitivePatternCatalog.mask(buffered);
                    if (masked != buffered) {
                        countMasked();
                    }
                    safe = masked;
                } else {
                    safe = buffered;
                }
                if (leakOn) {
                    String leaked = promptLeakDetector.maskIfLeaked(safe);
                    if (leaked != null) {
                        if (!leakPublished) {
                            leakPublished = true;
                            publishLeak(userId, "STREAM", safe.length());
                        }
                        safe = leaked;
                    }
                }
                // 扣留尾段；剩余即本拍产出。产出为空（全被扣留）也合法——下拍合并再出。
                if (safe.length() <= CARRY) {
                    carry.setLength(0);
                    carry.append(safe);
                    return "";
                }
                String emit = safe.substring(0, safe.length() - CARRY);
                carry.setLength(0);
                carry.append(safe.substring(safe.length() - CARRY));
                return emit;
            } catch (Exception e) {
                // 异常：倾空扣留，本拍原文直通（不再留缓冲——错误状态下别吞内容）
                String all = carry.append(chunk).toString();
                carry.setLength(0);
                log.error("流式净化异常(透传原文): {}", e.getMessage(), e);
                return all;
            }
        }

        /** 流终结（DONE/ERROR 前或 doFinally）补发扣留尾段。 */
        public String flush() {
            if (carry.length() == 0) {
                return "";
            }
            String rest = carry.toString();
            carry.setLength(0);
            return rest;
        }
    }

    private void countMasked() {
        if (bizMetrics != null) {
            try {
                bizMetrics.outputMasked();
            } catch (Exception ignore) {
                // 指标绝不阻断主链路
            }
        }
    }

    /** 泄露事件：payload 只带长度/通道——绝不带输出原文（PII 红线 #6）。 */
    private void publishLeak(Long userId, String channel, int chars) {
        if (securityEventPublisher != null) {
            securityEventPublisher.publish(ApplicationSecurityEvent.KIND_PROMPT_LEAK, userId,
                    Map.of("channel", channel, "chars", chars));
        }
        if (bizMetrics != null) {
            try {
                bizMetrics.promptLeak();
            } catch (Exception ignore) {
                // 指标绝不阻断主链路
            }
        }
    }
}
