package com.superprogrammer.chat.service.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.entity.MemoryTag;
import com.superprogrammer.chat.mapper.MemoryTagMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.knowledge.service.RagConfig;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmMessage;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.system.service.SystemSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 计划12 B：写时标签归一（四路径 + 并发兜底）。
 * <p>
 * 写记忆 turn 前解析标签，把同义标签归一到同一条 {@code memory_tags} 行（tag_id 复用进 turn）：
 * <ol>
 *   <li><b>精确槽位</b>：{@code (user, subject, topic)} 命中（UNIQUE 键，真实去重单元）→ 复用，
 *       label 不同则滚进 aliases。</li>
 *   <li><b>aliases 命中</b>：incoming label ∈ 某标签 aliases → 复用。</li>
 *   <li><b>anchor ≤ 阈值 + 二次 LLM 批判</b>：anchor 半向量近邻粗筛 → LLM 批量判真同义 → 复用 + 滚进 aliases。
 *       LLM 失败/无真同义 → 安全落空（走④，防误并）。</li>
 *   <li><b>全 miss 新建</b>：插新 tag，anchor 一并落库。</li>
 * </ol>
 * <p>
 * <b>并发兜底</b>：{@code UNIQUE(user_id,subject,topic)} 拦截同槽并发插入 → 捕获
 * {@link DuplicateKeyException} 改查已建行复用（10 线程同义词 → 一条）。
 * <p>
 * <b>禁归并/拆分/重抽</b>：本类无任何 merge/split/re-extract 出口（误并不可逆，已生成 summary 的 tag_id 会漂移）。
 * 误并只能由 owner 改 label/补 aliases（tag_id 不变，走 controller）。
 *
 * @see MemoryTagAnchorService anchor 构建
 * @see MemoryTagMapper 数据出口
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryTagResolver {

    /** 路径③ anchor 近邻候选上限（个人标签规模小，10 足够粗筛；超规模走 D 迭代 RRF）。 */
    private static final int ANCHOR_CANDIDATE_LIMIT = 10;
    /** subject 缺省值（L0 主体默认「我」）。 */
    private static final String DEFAULT_SUBJECT = "我";
    /** 路径③ LLM 批判调用上限（失败兜底 null，不阻塞归一）。 */
    private static final int LLM_MAX_ATTEMPTS = 2;

    private final MemoryTagMapper tagMapper;
    private final MemoryTagAnchorService anchorService;
    private final SystemSettingService systemSettingService;
    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;

    /**
     * 解析标签 → 返回归一后的 tag_id（写 turn 前调用）。
     *
     * @param userId  归属用户
     * @param subject L0 主体（blank → 「我」）
     * @param topic   L0 主题（blank → BAD_REQUEST）
     * @param label   对外展示名（blank → BAD_REQUEST）
     * @return 复用或新建的 tag_id
     */
    public Long resolve(Long userId, String subject, String topic, String label) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "标签归一缺 userId");
        }
        String subj = normalizeSubject(subject);
        String normTopic = topic == null ? "" : topic.trim();
        String normLabel = label == null ? "" : label.trim();
        if (normTopic.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "标签 topic 不能为空");
        }
        if (normLabel.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "标签 label 不能为空");
        }

        // ① 精确槽位 (user,subject,topic)——UNIQUE 真实去重单元
        MemoryTag slot = tagMapper.findByUserSubjectTopic(userId, subj, normTopic);
        if (slot != null) {
            tagMapper.incrementUsage(slot.getId());
            // 同槽不同名 → 同义别名滚进（label 是槽位的另一表面形式）
            if (!normLabel.equals(slot.getLabel())) {
                tagMapper.appendAlias(slot.getId(), normLabel);
            }
            return slot.getId();
        }

        // ② label ∈ 某标签 aliases（跨槽同义命中）
        MemoryTag aliasHit = tagMapper.findByLabelInAliases(userId, normLabel);
        if (aliasHit != null) {
            tagMapper.incrementUsage(aliasHit.getId());
            return aliasHit.getId();
        }

        // ③ anchor ≤ 阈值 → 二次 LLM 批判（防误并）
        MemoryTagAnchorService.AnchorPayload anchor = anchorService.build(userId, subj, normTopic, normLabel, null);
        if (anchor != null) {
            double threshold = systemSettingService.getMemoryTagAnchorThreshold();
            List<MemoryTag> candidates = tagMapper.findWithinAnchorThreshold(
                    userId, anchor.halfvec(), threshold, ANCHOR_CANDIDATE_LIMIT);
            if (!candidates.isEmpty()) {
                Long synonymId = judgeSynonym(userId, normLabel, subj, normTopic, candidates);
                if (synonymId != null) {
                    tagMapper.incrementUsage(synonymId);
                    tagMapper.appendAlias(synonymId, normLabel);
                    return synonymId;
                }
                // LLM 判无真同义 / 调用失败 → 安全落空走④（防误并）
            }
        }

        // ④ 全 miss 新建
        return insertNew(userId, subj, normTopic, normLabel, anchor);
    }

    /** 路径④：新建 tag（anchor 可空 = embed 失败降级，后续 owner 改 label 重生补回）。 */
    private Long insertNew(Long userId, String subject, String topic, String label,
                           MemoryTagAnchorService.AnchorPayload anchor) {
        MemoryTag m = new MemoryTag();
        m.setUserId(userId);
        m.setSubject(subject);
        m.setTopic(topic);
        m.setLabel(label);
        m.setUsageCount(0);
        m.setAliases(List.of());
        m.setCreatedBy(userId);
        m.setUpdatedBy(userId);
        try {
            tagMapper.insertWithAnchor(m,
                    anchor != null ? anchor.halfvec() : null,
                    anchor != null ? anchor.tokens() : null);
            return m.getId();
        } catch (DuplicateKeyException dup) {
            // 并发兜底：UNIQUE(user_id,subject,topic) 拦截 → 改查已建行复用（10 线程同义 → 一条）
            log.info("标签归一并发撞 UNIQUE userId={} subject={} topic={} → 复用已建行",
                    userId, subject, topic);
            MemoryTag winner = tagMapper.findByUserSubjectTopic(userId, subject, topic);
            if (winner != null) {
                tagMapper.incrementUsage(winner.getId());
                if (!label.equals(winner.getLabel())) {
                    tagMapper.appendAlias(winner.getId(), label);
                }
                return winner.getId();
            }
            // 理论不可达（UNIQUE 拦了必有行）——抛出让上层感知
            throw dup;
        }
    }

    /**
     * 路径③二次 LLM 批判：批量判 incoming 与候选哪些是真同义。
     * <p>
     * 安全默认：LLM 失败/解析异常/空集 → 返 null（走④新建，绝不误并）。
     * 返回首个判定同义的候选 id（候选已按距离升序，最近者优先）。
     */
    private Long judgeSynonym(Long userId, String label, String subject, String topic, List<MemoryTag> candidates) {
        // 构候选展示串（不暴露 aliases 全集给 LLM？无妨——LLM 判官需要 aliases 辅判，且不出系统）
        StringBuilder candList = new StringBuilder();
        Set<Long> validIds = new HashSet<>();
        for (int i = 0; i < candidates.size(); i++) {
            MemoryTag c = candidates.get(i);
            validIds.add(c.getId());
            candList.append(String.format(
                    "{\"id\":%d,\"label\":%s,\"subject\":%s,\"topic\":%s,\"aliases\":%s}",
                    c.getId(), quote(c.getLabel()), quote(c.getSubject()), quote(c.getTopic()),
                    quoteAliases(c.getAliases())));
            if (i < candidates.size() - 1) candList.append(',');
        }
        String prompt = String.format(SYN_JUDGE_PROMPT, quote(label), quote(subject), quote(topic), candList);

        String model = systemSettingService.getMemoryJudgeModel();
        Exception last = null;
        for (int attempt = 1; attempt <= LLM_MAX_ATTEMPTS; attempt++) {
            try {
                String raw = llmGateway.chat(LlmRequest.builder()
                        .model(model)
                        .messages(List.of(LlmMessage.builder().role("user").content(prompt).build()))
                        .temperature(0.0)
                        .maxTokens(200)
                        .build(), userId).getContent();
                Long pick = parseFirstValidId(raw, validIds);
                if (pick != null) return pick;
                // 解析成功但空集 = LLM 明确判无同义 → 直接落空（不再重试）
                if (raw != null && !raw.isBlank()) return null;
            } catch (Exception e) {
                last = e;
                log.warn("标签同义 LLM 批判异常(第{}/{}次) userId={} label={}: {}",
                        attempt, LLM_MAX_ATTEMPTS, userId, label, e.getMessage());
            }
        }
        if (last != null) {
            log.warn("标签同义 LLM 批判 {} 次均失败 userId={} label={} → null 降级（走④新建）",
                    LLM_MAX_ATTEMPTS, userId, label);
        }
        return null;
    }

    /** 解析 LLM 返回的 JSON int 数组，返第一个 ∈ validIds 的 id；无/异常 → null。 */
    private Long parseFirstValidId(String raw, Set<Long> validIds) {
        String json = stripFence(raw);
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) return null;
            for (JsonNode n : root) {
                long id;
                if (n.isIntegralNumber()) {
                    id = n.asLong();
                } else if (n.isTextual()) {
                    try {
                        id = Long.parseLong(n.asText().trim());
                    } catch (NumberFormatException nx) {
                        continue;
                    }
                } else {
                    continue;
                }
                if (validIds.contains(id)) return id;
            }
        } catch (Exception e) {
            log.warn("标签同义 LLM 返回解析失败 raw={} → null: {}", truncate(raw), e.getMessage());
        }
        return null;
    }

    private static String normalizeSubject(String subject) {
        if (subject == null || subject.isBlank()) return DEFAULT_SUBJECT;
        return subject.trim();
    }

    /** JSON 字符串字面量包装（简易转义，anchor 文本不含复杂引号场景）。 */
    private static String quote(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String quoteAliases(List<String> aliases) {
        if (aliases == null || aliases.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < aliases.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(quote(aliases.get(i)));
        }
        return sb.append(']').toString();
    }

    private static String stripFence(String json) {
        if (json == null) return null;
        json = json.trim();
        if (json.startsWith("```")) {
            int s = json.indexOf('\n') + 1;
            int e = json.lastIndexOf("```");
            if (e > s) json = json.substring(s, e).trim();
            else if (s > 0) json = json.substring(s).trim();
        }
        int start = json.indexOf('[');
        if (start > 0) json = json.substring(start);
        return json.trim();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 80 ? s : s.substring(0, 80) + "...";
    }

    /** 同义批判 prompt（纯 JSON 契约：返同义候选 id 数组，无则 []）。 */
    private static final String SYN_JUDGE_PROMPT = """
            你是标签归一判官。判断「待判标签」与「候选标签列表」里哪些表达的是【完全相同的现实概念】（可合并为同义）。
            严格标准：只有指向同一概念才算同义（例：「居住地/住址/家」同义、「手机号/联系方式」视情况）；
            相近但不同概念不算（例：「居住地」vs「工作地」、「妻子」vs「母亲」）。
            待判标签：{"label":%s,"subject":%s,"topic":%s}
            候选标签列表：[%s]
            只返回一个 JSON 整数数组，元素是判定为同义的候选 id；无任何同义返回 []。禁止任何解释文字。
            """;
}
