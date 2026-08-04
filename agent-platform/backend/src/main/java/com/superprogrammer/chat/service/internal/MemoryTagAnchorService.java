package com.superprogrammer.chat.service.internal;

import com.superprogrammer.knowledge.service.RagConfig;
import com.superprogrammer.knowledge.util.HalfVecUtil;
import com.superprogrammer.knowledge.util.JiebaTokenizer;
import com.superprogrammer.llm.LlmGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 计划12 B：标签 anchor 构建（语义向量 + BM25 词法双通道原料）。
 * <p>
 * anchor = label + subject + topic + aliases 拼串（V47 表注释约定）。语义向量走
 * {@link LlmGateway#embed}（doubao-embedding-vision 2048 维），词法走 {@link JiebaTokenizer}
 * 分词空格串（DB 侧 to_tsvector('simple') 生成 anchor_tokens_tsv）。
 * <p>
 * <b>失败兜底</b>：embed 任一环异常 → 返 null（路径③自然跳过，标签仍可无 anchor 落库；
 * 后续 owner 改 label 会重生 anchor 补回）。这契合全局「LLM 降级：选标签失败→null」约定。
 * <p>
 * 纯构建服务，不碰 DB——写入由 {@code MemoryTagResolver} 调本服务拿 payload 后走 mapper。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryTagAnchorService {

    private final LlmGateway llmGateway;

    /** anchor 双通道原料：halfvec 文本字面量 '[..]' + jieba 分词空格串。null = 构建失败，调用方降级。 */
    public record AnchorPayload(String halfvec, String tokens) {
    }

    /**
     * 构建标签 anchor（写时归一路径③/④ + owner 改 label 重生用）。
     *
     * @param userId  归属用户（embed 走用户 provider 优先，null 走全局）
     * @param subject L0 主体（默认「我」）
     * @param topic   L0 主题
     * @param label   对外展示名
     * @param aliases 同义别名集（null/空 tolerated）
     * @return anchor payload；embed/序列化失败 → null
     */
    public AnchorPayload build(Long userId, String subject, String topic, String label, List<String> aliases) {
        String text = buildAnchorText(subject, topic, label, aliases);
        if (text.isBlank()) {
            // 没有任何可 embed 文本（极端空输入）→ 不浪费 LLM 调用，直接降级。
            log.debug("anchor 文本为空 userId={} label={} → null", userId, label);
            return null;
        }
        try {
            float[] vec = llmGateway.embed(text, RagConfig.MEMORY_EMBED_MODEL, userId);
            String halfvec = HalfVecUtil.toHalfVec(vec);
            String tokens = JiebaTokenizer.tokenize(text);
            return new AnchorPayload(halfvec, tokens);
        } catch (Exception e) {
            // 兜底：embed/序列化异常不阻断归一主链路，路径③跳过即可。
            log.warn("anchor 构建失败 userId={} label={} text.len={} → null 降级（路径③跳过）: {}",
                    userId, label, text.length(), e.getMessage());
            return null;
        }
    }

    /**
     * 拼串 anchor 文本：去重去空白后空格拼接（embed 输入 + jieba 分词输入同源）。
     * 顺序固定（label → subject → topic → aliases）保证同标签同输入 → 同向量（可复现/可重生）。
     */
    static String buildAnchorText(String subject, String topic, String label, List<String> aliases) {
        Set<String> parts = new LinkedHashSet<>();
        addIfNotBlank(parts, label);
        addIfNotBlank(parts, subject);
        addIfNotBlank(parts, topic);
        if (aliases != null) {
            for (String a : aliases) {
                addIfNotBlank(parts, a);
            }
        }
        return String.join(" ", parts);
    }

    private static void addIfNotBlank(Set<String> parts, String s) {
        if (s != null && !s.isBlank()) {
            parts.add(s.trim());
        }
    }
}
