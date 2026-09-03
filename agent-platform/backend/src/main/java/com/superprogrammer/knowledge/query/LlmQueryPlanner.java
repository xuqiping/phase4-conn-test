package com.superprogrammer.knowledge.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.knowledge.config.LlmQueryPlannerProperties;
import com.superprogrammer.llm.dto.LlmMessage;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.llm.dto.LlmResponse;
import com.superprogrammer.llm.LlmGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * LLM QueryPlanner（WP2 Step4，规格 §5.3）：LLM 生成结构化 QueryPlan（分类/子意图/filters/策略集）。
 *
 * <p>开关 {@code rag.queryplanner.llm.enabled} 默认 false（关=零 LLM 调用，行为=规则版基线）。
 * 超时双保险：CompletableFuture.orTimeout 守卫 + LlmRequest.timeoutMs（provider 侧中止）。
 * 失败/超时/解析异常 → 规则版 {@link QueryPlanner} 原样返回（子意图空），不伤主链。
 *
 * <p>防幻觉护栏：filters 以规则版正则提取为**唯一权威**（LLM 不许造锚点——值必须出自 query 原文，
 * 正则已保证）；LLM 仅覆盖 queryType/answerShape/strategies/exhaustive/multiHop 且逐字段校验
 * （queryType 白名单/strategies ⊆ 已知集），非法值保留规则版。子意图 ≤3 条、每条 ≤20 字
 * （供给 Step2 CoverageVerifier 必达判定与补充轮 query）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmQueryPlanner {

    private static final Set<String> KNOWN_TYPES = Set.of("EXACT", "SEMANTIC", "PROCEDURE", "COMPARISON", "LIST");
    private static final Set<String> KNOWN_SHAPES = Set.of("DIRECT", "ORDERED_STEPS", "MULTI_EVIDENCE", "LIST");
    private static final Set<String> KNOWN_STRATEGIES = Set.of("EXACT", "SPARSE", "DENSE", "NEIGHBOR");

    private final QueryPlanner rulePlanner;
    private final LlmGateway llmGateway;
    private final LlmQueryPlannerProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 规划结果：最终 QueryPlan + 子意图（Step2 llmSubIntents 供给面）+ 是否真用了 LLM。 */
    public record PlanOutcome(QueryPlan plan, List<String> subIntents, boolean llmUsed) {
        static PlanOutcome rule(QueryPlan plan) {
            return new PlanOutcome(plan, List.of(), false);
        }
    }

    /**
     * 规划入口（路由）：关→规则版；开→LLM 结构化规划，任何异常回退规则版。
     * 计费归户当前用户（gateway.chat(req, userId)）。
     */
    public PlanOutcome planWithFallback(String query, Long userId) {
        QueryPlan rule = rulePlanner.plan(query);
        if (!props.getLlm().isEnabled()) {
            return PlanOutcome.rule(rule);
        }
        try {
            LlmQueryPlannerProperties.Llm cfg = props.getLlm();
            PlanOutcome llm = CompletableFuture
                    .supplyAsync(() -> callLlm(query, userId, cfg))
                    .orTimeout(cfg.getTimeoutMs(), TimeUnit.MILLISECONDS)
                    .get(cfg.getTimeoutMs() + 500L, TimeUnit.MILLISECONDS);
            if (llm != null) {
                return llm;
            }
            log.info("LLM 规划不可用（空返回），回退规则版 query={}", query);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("LLM 规划被中断，回退规则版 query={}", query);
        } catch (Exception e) {
            log.warn("LLM 规划失败/超时（{}ms），回退规则版 query={} err={}",
                    props.getLlm().getTimeoutMs(), query, e.getMessage());
        }
        return PlanOutcome.rule(rule);
    }

    private PlanOutcome callLlm(String query, Long userId, LlmQueryPlannerProperties.Llm cfg) {
        String system = """
                你是检索规划器。分析用户查询，输出严格 JSON（不要任何其他文字、不要 markdown 代码块）：
                {"queryType":"EXACT|SEMANTIC|PROCEDURE|COMPARISON|LIST","answerShape":"DIRECT|ORDERED_STEPS|MULTI_EVIDENCE|LIST",\
                "strategies":["SPARSE","DENSE","EXACT","NEIGHBOR"],"exhaustive":false,"multiHop":false,\
                "subIntents":["回答该问题必须覆盖的子主题，最多3个，每个不超过20字"]}
                查询：%s""".formatted(query == null ? "" : query);
        LlmRequest req = LlmRequest.builder()
                .model(cfg.getModel())
                .messages(List.of(new LlmMessage("system", system), new LlmMessage("user", query == null ? "" : query)))
                .temperature(0.0)
                .maxTokens(cfg.getMaxTokens())
                .disableThinking(true)   // 内部 JSON 蒸馏必开（思考与正文共享 max_tokens 预算）
                .timeoutMs(cfg.getTimeoutMs())
                .build();
        LlmResponse resp = llmGateway.chat(req, userId);   // 计费归户当前用户
        return parse(resp == null ? null : resp.getContent(), query);
    }

    /** 解析+护栏校验：非法字段保留规则版；全部非法→null（调用方回退规则版）。 */
    PlanOutcome parse(String content, String query) {
        try {
            JsonNode root = objectMapper.readTree(stripFences(content));
            QueryPlan rule = rulePlanner.plan(query);
            String type = textOf(root, "queryType");
            String shape = textOf(root, "answerShape");
            String finalType = KNOWN_TYPES.contains(type) ? type : rule.queryType();
            String finalShape = KNOWN_SHAPES.contains(shape) ? shape : rule.answerShape();
            Set<String> strategies = new LinkedHashSet<>();
            if (root.has("strategies") && root.get("strategies").isArray()) {
                for (JsonNode s : root.get("strategies")) {
                    if (KNOWN_STRATEGIES.contains(s.asText())) {
                        strategies.add(s.asText());
                    }
                }
            }
            List<String> finalStrategies = strategies.isEmpty() ? rule.strategies() : List.copyOf(strategies);
            QueryPlan merged = new QueryPlan(finalType, finalShape,
                    rule.filters(),   // filters 权威=规则正则（值出自 query 原文，LLM 不许造锚点）
                    finalStrategies,
                    boolOf(root, "exhaustive", rule.exhaustive()),
                    boolOf(root, "multiHop", rule.multiHop()),
                    rule.requiresLlmAnalysis());
            List<String> subIntents = new ArrayList<>();
            if (root.has("subIntents") && root.get("subIntents").isArray()) {
                Set<String> seen = new LinkedHashSet<>();
                for (JsonNode n : root.get("subIntents")) {
                    String s = n.asText("") == null ? "" : n.asText("").trim();
                    if (!s.isEmpty() && s.length() <= 20 && seen.add(s) && subIntents.size() < 3) {
                        subIntents.add(s);
                    }
                }
            }
            return new PlanOutcome(merged, List.copyOf(subIntents), true);
        } catch (Exception e) {
            log.warn("LLM 规划 JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    private static String stripFences(String content) {
        if (content == null) {
            return "";
        }
        String s = content.trim();
        if (s.startsWith("```")) {
            int first = s.indexOf('\n');
            int last = s.lastIndexOf("```");
            if (first > 0 && last > first) {
                s = s.substring(first + 1, last).trim();
            }
        }
        return s;
    }

    private static String textOf(JsonNode root, String field) {
        return root.has(field) ? root.get(field).asText("") : "";
    }

    private static boolean boolOf(JsonNode root, String field, boolean dft) {
        return root.has(field) && root.get(field).isBoolean() ? root.get(field).asBoolean() : dft;
    }
}
