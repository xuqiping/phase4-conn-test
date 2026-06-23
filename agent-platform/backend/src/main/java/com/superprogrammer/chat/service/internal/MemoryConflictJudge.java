package com.superprogrammer.chat.service.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.entity.UserMemory;
import com.superprogrammer.knowledge.service.RagConfig;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmMessage;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.llm.dto.LlmResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 记忆冲突判定器（3 个 LLM 调用：抽取 / batch 判定 / 答复路由）。
 *
 * 准确率调优（2026-06-22）：regex 解析 → Jackson readTree（容错字段顺序/转义引号/值含标点，
 * 杀掉旧 regex 漏匹配导致的 count-mismatch fail-safe 静默漏判）；temperature 0.3→0.0（分类确定性）；
 * prompt 强化（key 归一指引 + few-shot + 纯 JSON 契约）。
 *
 * fail-safe 原则不变：任何失败 → 不冲突/不答（绝不丢事实、不误拦截）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryConflictJudge {

    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;

    private static final Set<String> VALID_CATEGORIES = Set.of("PREFERENCE", "FACT", "FEEDBACK");

    /** 分类/判定/路由统一低温（确定性优先，抽取也走低温以稳定 key 命名）。 */
    private static final double JUDGE_TEMPERATURE = 0.0;

    private static final String EXTRACT_PROMPT = """
            你是记忆抽取器。分析以下对话，提取用户的偏好、个人事实或反馈。

            输出契约（必须严格遵守）：
            - 只输出一个 JSON 数组，不要任何 markdown 围栏、注释或解释文字。
            - 每个元素: {"category":"PREFERENCE|FACT|FEEDBACK","key":"英文短键","value":"值","confidence":0.0-1.0,"block":"中文短块名"}
            - 没有可提取信息时输出 []

            key 命名（重要，决定去重/冲突识别准确率）：
            - 用稳定通用的英文蛇形短键，复用常见命名：favorite_language / name / age / occupation / spouse_name / child_name / child_age / child_gender / home_city / phone / dietary_preference 等。
            - 同一概念只用一个 key（编程语言偏好统一 favorite_language，不要 favorite_programming_language / default_fav_lang 等变体）。
            - key 只含小写字母/数字/下划线，≤40 字符。

            block（事实归属的信息块，用于聚类归块）：
            - 用中文短名，固定优先复用：家庭信息 / 职业 / 教育 / 偏好 / 联系方式 / 健康 / 财务 / 基本信息；都不贴再自造短名。

            示例：
            用户: "我用 Java 写后端，最近在学 Python"
            助手: "好的，记下了。"
            [{"category":"FACT","key":"occupation","value":"后端工程师(Java)","confidence":0.85,"block":"职业"},{"category":"PREFERENCE","key":"learning_language","value":"Python","confidence":0.7,"block":"偏好"}]

            用户消息: %s
            助手回复: %s
            JSON:""";

    private static final String JUDGE_PROMPT = """
            你是记忆冲突判定器。判断【新事实】与【同信息块已有记忆】是否语义冲突。

            冲突定义：新事实与某条已有记忆描述的是用户的【同一属性/同一件事】，但给出了【不同且互相矛盾】的值。
            判定要点：
            - 以【语义】为准，不要因 key 字面不同就判非冲突（favorite_language / favorite_programming_language / 本命语言 是同一属性；child_name / son_name / daughter_name 都指"孩子的名字/存在"）。
            - 同一属性的不同值 = 冲突（旧"喜欢Java" vs 新"喜欢Python"；旧"有女儿小红" vs 新"有儿子小明"——同属"孩子"属性）。
            - 不同属性 = 非冲突（旧"已婚" vs 新"喜欢跑步"）。
            - 互补/补充 = 非冲突（旧"会用Java" vs 新"也会用Python"，除非新值明确否定旧值）。
            - 用户原文含"更正/改/不是/其实/换成/记错了"等修改措辞 → 强烈提示修改旧信息 → 倾向冲突。

            输出契约（必须严格遵守）：
            - 只输出一个 JSON 数组，每条新事实一个元素，顺序与新事实数组一致。
            - 每个元素: {"factIdx":0基下标,"conflict":true或false,"conflictingIds":[与之冲突的已有记忆id数组,无冲突为空],"askText":"若冲突,给用户的中文提问;无冲突为空串"}
            - 无冲突的元素也要返回（conflict=false, conflictingIds=[], askText="")。
            - askText 里不要出现双引号、不要换行；用「」代替内嵌引号。
            - 不要 markdown 围栏或解释文字。

            用户原文: %s
            新事实: %s
            已有记忆: %s
            JSON:""";

    private static final String ROUTER_PROMPT = """
            用户刚收到一个记忆冲突提问，随后回复了一条消息。判断这条回复是不是在回答该冲突，并给出保留决定。

            冲突提问: "%s"
            候选标签：A=【之前/原来】的旧信息（提问里"之前/原来/以前"指代的那条）；B=【现在/更正/改】后的新信息（提问里"现在/更正说/改成"指代的那条）。
            用户回复: "%s"

            输出契约（必须严格遵守）：
            - 只输出一个 JSON 对象，不要 markdown 围栏或解释。
            - {"isAnswer":true或false,"keep":"A"|"B"|"BOTH"|"NONE"|"UNCLEAR"}
            - keep=A 保留旧弃新；keep=B 保留新弃旧；keep=BOTH 两条都留；keep=NONE 都删。
            - 用户回复与冲突提问无关（聊别的、问别的）→ isAnswer=false, keep="UNCLEAR"。
            - 用户回复意图不清（没明确选）→ isAnswer=false, keep="UNCLEAR"。
            JSON:""";

    /** 抽取事实（含 block 候选）。失败/空返 empty。 */
    public List<ExtractedFact> extract(String userMessage, String assistantResponse) {
        String json = chat(String.format(EXTRACT_PROMPT, userMessage, assistantResponse));
        if (json == null || json.isBlank()) return List.of();
        JsonNode root = parseJson(stripFence(json));
        if (root == null || !root.isArray()) {
            log.warn("抽取返回非 JSON 数组: {}", truncate(json));
            return List.of();
        }
        List<ExtractedFact> out = new ArrayList<>();
        for (JsonNode el : root) {
            ExtractedFact f = readFact(el);
            if (f != null) out.add(f);
        }
        return out;
    }

    /** batch 冲突判定（传 userMessage 保留"更正"等修改信号）。失败/解析失败返全 false（fail-safe）。 */
    public List<JudgeResult> judge(List<ExtractedFact> facts, List<UserMemory> blockMembers, String userMessage) {
        if (facts == null || facts.isEmpty()) return List.of();
        try {
            String newJson = objectMapper.writeValueAsString(facts.stream()
                    .map(f -> java.util.Map.of("idx", facts.indexOf(f), "key", f.key(), "value", f.value())).toList());
            String memJson = objectMapper.writeValueAsString(blockMembers == null ? List.of() : blockMembers.stream()
                    .map(m -> java.util.Map.of("id", m.getId(), "key", m.getMemoryKey(), "value", m.getMemoryValue())).toList());
            JsonNode root = parseJson(stripFence(chat(String.format(JUDGE_PROMPT,
                    userMessage == null ? "" : userMessage, newJson, memJson))));
            // 按 factIdx 收集；缺失的事实默认无冲突（fail-safe，但不再静默全丢）。
            java.util.Map<Integer, JudgeResult> byIdx = new java.util.HashMap<>();
            if (root != null && root.isArray()) {
                for (JsonNode el : root) {
                    JudgeResult jr = readJudge(el);
                    if (jr != null) byIdx.put(jr.factIdx(), jr);
                }
            } else {
                log.warn("judge 返回非 JSON 数组, fail-safe 全无冲突: {}", root == null ? "(null)" : truncate(root.toString()));
            }
            List<JudgeResult> out = new ArrayList<>();
            for (int i = 0; i < facts.size(); i++) {
                out.add(byIdx.getOrDefault(i, new JudgeResult(i, false, List.of(), null)));
            }
            return out;
        } catch (Exception e) {
            log.warn("冲突判定失败 fail-safe: {}", e.getMessage());
            return facts.stream().map(f -> new JudgeResult(facts.indexOf(f), false, List.of(), null)).toList();
        }
    }

    /** 答复路由：结构化判定（取代旧 string-contains）。失败返 isAnswer=false。 */
    public RouteResult route(String askText, String userMessage) {
        String raw = chat(String.format(ROUTER_PROMPT, askText == null ? "" : askText, userMessage == null ? "" : userMessage));
        JsonNode root = parseJson(stripFence(raw));
        if (root == null || !root.isObject()) {
            log.warn("route 返回非 JSON 对象, fail-safe: {}", raw == null ? "(null)" : truncate(raw));
            return new RouteResult(false, "UNCLEAR");
        }
        boolean isAnswer = root.path("isAnswer").asBoolean(false);
        String keep = root.path("keep").asText("UNCLEAR");
        if (!List.of("A", "B", "BOTH", "NONE", "UNCLEAR").contains(keep)) keep = "UNCLEAR";
        return new RouteResult(isAnswer, isAnswer ? keep : "UNCLEAR");
    }

    // ---- Jackson 解析 helpers ----

    private ExtractedFact readFact(JsonNode el) {
        if (el == null || !el.isObject()) return null;
        String category = textOrDefault(el, "category", "FACT");
        if (!VALID_CATEGORIES.contains(category)) category = "FACT";
        String key = textOrDefault(el, "key", null);
        if (key == null || key.isBlank()) return null;
        String value = textOrDefault(el, "value", "");
        BigDecimal confidence = parseConfidence(el.get("confidence"));
        String block = textOrDefault(el, "block", null);
        return new ExtractedFact(category, key.trim(), value, confidence, block);
    }

    private JudgeResult readJudge(JsonNode el) {
        if (el == null || !el.isObject()) return null;
        int idx = el.path("factIdx").asInt(0);
        boolean conflict = el.path("conflict").asBoolean(false);
        List<Long> ids = new ArrayList<>();
        JsonNode arr = el.get("conflictingIds");
        if (arr != null && arr.isArray()) {
            for (JsonNode idNode : arr) {
                if (idNode.canConvertToLong()) ids.add(idNode.asLong());
            }
        }
        String askText = textOrDefault(el, "askText", null);
        if (askText != null) askText = askText.isBlank() ? null : askText.trim();
        return new JudgeResult(idx, conflict, ids, askText);
    }

    private static String textOrDefault(JsonNode parent, String field, String def) {
        JsonNode n = parent.get(field);
        if (n == null || n.isNull()) return def;
        String s = n.asText();
        return s == null ? def : s;
    }

    private static BigDecimal parseConfidence(JsonNode n) {
        if (n == null || n.isNull()) return BigDecimal.ONE;
        try {
            double d = n.asDouble(1.0);
            if (d < 0) d = 0;
            if (d > 1) d = 1;
            return new BigDecimal(String.format(java.util.Locale.US, "%.2f", d));
        } catch (Exception e) {
            return BigDecimal.ONE;
        }
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("JSON 解析失败: {}", truncate(json));
            return null;
        }
    }

    private String chat(String prompt) {
        try {
            LlmResponse resp = llmGateway.chat(LlmRequest.builder()
                    .model(RagConfig.MEMORY_JUDGE_MODEL)
                    .messages(List.of(LlmMessage.builder().role("user").content(prompt).build()))
                    .temperature(JUDGE_TEMPERATURE).maxTokens(800).build());
            return resp.getContent();
        } catch (Exception e) {
            log.warn("LLM 调用失败: {}", e.getMessage());
            return null;
        }
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

    private static String truncate(String s) {
        if (s == null) return "(null)";
        return s.length() <= 160 ? s : s.substring(0, 160) + "...";
    }
}
