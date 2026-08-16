package com.superprogrammer.chat.service.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.entity.MemorySummary;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmMessage;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.llm.dto.LlmResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 记忆总结冲突判定器（计划12 · H 收尾瘦身版）。
 * <p>
 * <b>v2 瘦身（H'-2）</b>：legacy chat 路径的事实抽取 / batch 冲突判定 / 答复路由 / 三维筛（依赖
 * {@code UserMemory}/{@code ExtractedFact} 等 legacy 簇）已随旧栈整体删除。本类现仅保留
 * {@link #judgeSummaryConflict}——新栈 {@code MemoryConsolidationTxService} 总结写入时序互斥判定用。
 * <p>
 * <b>fail-safe</b> 不变：任何失败 → 不冲突（绝不误拦截总结写入、不丢事实）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryConflictJudge {

    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;
    private final com.superprogrammer.system.service.SystemSettingService systemSettingService;

    /** 判定统一低温（确定性优先）。 */
    private static final double JUDGE_TEMPERATURE = 0.0;

    /** 计划12 · E-5 · 判定结果（设计 §3.5）。
     *  conflict=true（互斥时序）→ PENDING；false（并存互补 / fail-safe）→ 共存 CLEAN。 */
    public record SummaryConflictResult(boolean conflict, String askText) {}

    /** 计划12 · E-5 · 总结层时序互斥判定 prompt（设计 §3.4 line132 + §3.5）。
     *  同 (user, tag, scope) 下【新总结】与【已有 CLEAN 总结】是否互斥时序（冲突），还是并存互补。
     *  典型：旧「住北京」vs 新「住上海」= 互斥（需用户裁决哪条当前态）；旧「会Java」vs 新「也会Python」= 并存。
     *  日期/年份不同 + 同属性 = 时序互斥；日期相同或互补 = 并存。 */
    private static final String SUMMARY_CONFLICT_PROMPT = """
            你是记忆总结冲突判定器。判断【新总结】与【同标签同 scope 已有总结】是否【时序互斥冲突】。

            冲突定义（设计 §3.5）：
            - 互斥时序 = 描述【同一属性/同一件事】在【不同时间给出互相矛盾】的状态，必须由用户裁决保留哪条。
              例：旧「2024 住北京」vs 新「2026 住上海」（同属性=居住地，时序矛盾）= 冲突；
              旧「职级 P6」vs 新「职级 P7」（同属性=职级，时序变迁）= 冲突；
              旧「未婚」vs 新「已婚」= 冲突。
            - 并存互补 = 描述不同属性，或同属性但不矛盾（补充/扩展），可共存。
              例：旧「会 Java」vs 新「也会 Python」= 并存；旧「住北京」vs 新「2025 在杭州出差」= 并存（不同事件）。

            输出契约（必须严格遵守）：
            - 只输出一个 JSON 对象，不要 markdown 围栏或解释文字。
            - {"conflict":true或false,"askText":"若冲突,给用户的中文裁决提问(列新旧两条供选择);无冲突为空串"}
            - askText 里不要双引号、不要换行；用「」代替内嵌引号。
            - 判定以【语义+时序】为准，不确定倾向【不冲突】（并存，fail-safe 不误拦截总结）。

            新总结: %s
            已有总结（同标签同 scope，按时间倒序）:
            %s
            JSON:""";

    /**
     * 计划12 · E-5 · 判定新总结与已有 CLEAN 总结是否时序互斥（设计 §3.5）。
     * <p>
     * 调用方（{@code MemoryConsolidationTxService}）：同 (user, tag, scope) 已有 CLEAN 总结时调本方法；
     * <ul>
     *   <li>{@code conflict=true} → 新总结写入后置 PENDING_CONFLICT + 已有也置 PENDING_CONFLICT +
     *       插 memory_conflicts(tag_id+summary_id=新)，等用户裁决（四选项 E-4）；</li>
     *   <li>{@code conflict=false} → 新总结写 CLEAN，与已有共存（自然按 summarized_at 排序，防膨胀 worker 后续再压）。</li>
     * </ul>
     * <p>
     * <b>fail-safe</b>：existing 空 / LLM 失败 / 解析失败 → {@code conflict=false}（不误拦截总结写入）。
     *
     * @param existing 同 (user, tag, scope) 已有 CLEAN 总结（summarized_at 倒序）
     * @param newText  新总结的 L1/L2 文本（喂 prompt 做语义+时序判定）
     */
    public SummaryConflictResult judgeSummaryConflict(List<MemorySummary> existing, String newText, Long userId) {
        return judgeSummaryConflict(existing, newText, userId, null);
    }

    /**
     * 同上，显式指定 model（跟随压缩源 turn/entry 的 chat_model，null 回退可配默认）。
     */
    public SummaryConflictResult judgeSummaryConflict(List<MemorySummary> existing, String newText,
                                                     Long userId, String model) {
        if (existing == null || existing.isEmpty() || newText == null || newText.isBlank()) {
            return new SummaryConflictResult(false, null);
        }
        String judgeModel = (model != null && !model.isBlank())
                ? model : systemSettingService.getMemoryJudgeModel();
        try {
            String existingDisplay = existing.stream()
                    .map(s -> "- " + summaryText(s))
                    .collect(java.util.stream.Collectors.joining("\n"));
            String raw = chat(String.format(SUMMARY_CONFLICT_PROMPT, newText, existingDisplay), userId, judgeModel);
            JsonNode root = parseJson(stripFence(raw));
            if (root == null || !root.isObject()) {
                log.warn("judgeSummaryConflict 返回非 JSON 对象, fail-safe 不冲突: {}",
                        raw == null ? "(null)" : truncate(raw));
                return new SummaryConflictResult(false, null);
            }
            boolean conflict = root.path("conflict").asBoolean(false);
            String askText = textOrDefault(root, "askText", null);
            if (askText != null) askText = askText.isBlank() ? null : askText.trim();
            return new SummaryConflictResult(conflict, askText);
        } catch (Exception e) {
            log.warn("judgeSummaryConflict 失败 fail-safe 不冲突: {}", e.getMessage());
            return new SummaryConflictResult(false, null);
        }
    }

    /** 拼总结文本（L1 优先，空则 L2）。 */
    private static String summaryText(MemorySummary s) {
        if (s.getL1Summary() != null && !s.getL1Summary().isBlank()) return s.getL1Summary();
        return s.getL2Detail() == null ? "" : s.getL2Detail();
    }

    // ---- Jackson 解析 / LLM helpers ----

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("JSON 解析失败: {}", truncate(json));
            return null;
        }
    }

    /** 走指定 model（调用方解析后传入；fail-safe 3 重试）。 */
    private String chat(String prompt, Long userId, String model) {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                LlmResponse resp = llmGateway.chat(LlmRequest.builder()
                        .model(model)
                        .messages(List.of(LlmMessage.builder().role("user").content(prompt).build()))
                        .temperature(JUDGE_TEMPERATURE).maxTokens(2048).disableThinking(true).build(), userId);
                String content = resp.getContent();
                if (content != null && !content.isBlank()) return content;
                log.warn("LLM 返回空(第{}/3次) prompt.len={}", attempt, prompt.length());
            } catch (Exception e) {
                last = e;
                log.warn("LLM 调用异常(第{}/3次): {}", attempt, e.getMessage());
            }
        }
        if (last != null) log.warn("LLM 调用 3 次均失败: {}", last.getMessage());
        return null;
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
        // LLM 偶尔在 JSON 前后塞解释文字，尝试截到首个 [ / { 到末尾匹配。
        int arrStart = json.indexOf('[');
        int objStart = json.indexOf('{');
        int start = -1;
        if (arrStart >= 0 && (objStart < 0 || arrStart < objStart)) start = arrStart;
        else if (objStart >= 0) start = objStart;
        if (start > 0) json = json.substring(start);
        return json.trim();
    }

    private static String textOrDefault(JsonNode parent, String field, String def) {
        JsonNode n = parent.get(field);
        if (n == null || n.isNull()) return def;
        String s = n.asText();
        return s == null ? def : s;
    }

    private static String truncate(String s) {
        if (s == null) return "(null)";
        return s.length() <= 160 ? s : s.substring(0, 160) + "...";
    }
}
