package com.superprogrammer.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.knowledge.config.RagRecallProperties;
import com.superprogrammer.knowledge.util.HalfVecUtil;
import com.superprogrammer.knowledge.trace.RagTraceContext;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmMessage;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.system.service.SystemSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Query 多路扩展（Phase1）：1 次 LLM chat 生成 K 条释义 + 1 条 HyDE 假想答案 → 各 embed。
 * 返回规范 query + 多个 halfvec（规范 query 第一个，供答案缓存键复用）。
 *
 * <p>治"换说法向量漂移"——单 query embed 一旦漂了全完；多向量覆盖释义空间，任一落进 L0 邻域即召回。
 * HyDE 尤其补"问题表达 vs 文档表达"差异（假想答案语义更靠近知识库文档）。
 *
 * <p>任一环节失败 → 降级仅规范 query（不抛、不致命）。servlet-sync 调用（与既有 RAG embed 同）。
 * B4 不变量精神："每个逻辑 query 一轮扩展"，由本类封装；调用方只调一次 expand。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryExpansionService {

    /** 切块多路召回的块数上限（最坏 8 次 embed，可控）。 */
    private static final int MAX_CHUNKS = 8;

    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;
    private final RagRecallProperties recallProps;
    private final SystemSettingService systemSettingService;

    /**
     * 扩展 query 为多个 halfvec（含规范 query）。
     *
     * @param query       原始 query（规范，其 embed 作 qHalfs[0] + 答案缓存键）
     * @param embedModel  embedding 模型（与 KB 一致，保证与库内向量同空间可比）
     * @return ExpandedQuery；失败/关闭 → 仅含规范 query 单向量
     */
    public ExpandedQuery expand(String query, String embedModel, Long userId) {
        return expand(query, embedModel, userId, true);
    }

    public ExpandedQuery expand(String query, String embedModel, Long userId, boolean allowLlmExpansion) {
        // 规范 query 必 embed（即使扩展全关/全失败）
        String canonicalHalf = embedHalf(query, embedModel, userId);
        if (canonicalHalf == null) {
            // 规范 embed 都失败 → 上层会因无 qHalf 抛错；这里返回空让上层兜底
            return new ExpandedQuery(query, List.of());
        }

        // 运行时全局开关（4 条检索路径同读：/retrieve、/ask、Chat、Agent/工作流 → 调试与真实一致）
        boolean enabled = systemSettingService.getRagRecallExpansionEnabled();
        if (!enabled || !allowLlmExpansion) {
            return new ExpandedQuery(query, List.of(canonicalHalf));
        }

        int threshold = systemSettingService.getRagRecallExpansionThreshold();
        List<String> qHalfs = new ArrayList<>();
        qHalfs.add(canonicalHalf);

        try {
            if (query.length() > threshold) {
                // 长输入：切块多路召回（不调改写 LLM——改写万字没意义且烧钱；切块保证多主题不丢内容）
                for (String chunk : chunkQuery(query, threshold)) {
                    String h = embedHalf(chunk, embedModel, userId);
                    if (h != null && !qHalfs.contains(h)) {
                        qHalfs.add(h);
                    }
                }
            } else {
                // 短 query：LLM 改写 K 释义 + HyDE 假想答案，各 embed
                ExpansionPayload payload = generateParaphrases(query, recallProps.getExpansion().getCount(),
                        recallProps.getHyde().isEnabled(), userId);
                for (String p : payload.paraphrases) {
                    String h = embedHalf(p, embedModel, userId);
                    if (h != null && !qHalfs.contains(h)) {
                        qHalfs.add(h);
                    }
                }
                if (recallProps.getHyde().isEnabled() && payload.hyde != null && !payload.hyde.isBlank()) {
                    String h = embedHalf(payload.hyde, embedModel, userId);
                    if (h != null && !qHalfs.contains(h)) {
                        qHalfs.add(h);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("query 多路扩展失败，降级仅规范 query: {}", e.getMessage());
        }
        return new ExpandedQuery(query, qHalfs);
    }

    /**
     * 切分长 query 为多块（段落 → 句子 → 字数硬切），每块 ≤ chunkSize 字，取前 {@value #MAX_CHUNKS} 块。
     * 保证每块都在 embedding 友好长度内，多主题各有命中机会。
     */
    private List<String> chunkQuery(String query, int chunkSize) {
        List<String> raw = new ArrayList<>();
        for (String para : query.split("\\s*\\n{2,}\\s*")) {
            if (!para.isBlank()) {
                collectSentences(para, chunkSize, raw);
            }
        }
        // 无段落分隔的纯长文兜底（整段没换行）
        if (raw.isEmpty() && !query.isBlank()) {
            collectSentences(query, chunkSize, raw);
        }
        List<String> out = new ArrayList<>();
        for (String c : raw) {
            if (out.size() >= MAX_CHUNKS) {
                break;
            }
            out.add(c);
        }
        return out;
    }

    /** 按句末标点切句，累积到 ≤ chunkSize 成块；单句超长按字数硬切。 */
    private void collectSentences(String text, int chunkSize, List<String> out) {
        StringBuilder cur = new StringBuilder();
        for (String sent : text.split("(?<=[。！？!?\\.])\\s*")) {
            String s = sent.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (s.length() > chunkSize) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                for (int i = 0; i < s.length(); i += chunkSize) {
                    out.add(s.substring(i, Math.min(s.length(), i + chunkSize)));
                }
                continue;
            }
            if (cur.length() + s.length() + 1 > chunkSize && cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) {
                cur.append(' ');
            }
            cur.append(s);
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
    }

    /** embed + dim 校验 + toHalfVec；失败返 null（不抛，由调用方决定降级）。 */
    private String embedHalf(String text, String model, Long userId) {
        try {
            float[] vec = llmGateway.embed(text, model, userId);
            if (vec == null || vec.length != HalfVecUtil.DIM) {
                log.warn("query embedding 维度异常 expected={} actual={}", HalfVecUtil.DIM,
                        vec == null ? -1 : vec.length);
                return null;
            }
            return HalfVecUtil.toHalfVec(vec);
        } catch (BusinessException e) {
            // Phase4 UI 冒烟实证：业务类失败（积分余额不足/价表缺失/模型不可用）吞成 null 会让页面只看到
            // 「query embedding 失败」，真实原因埋在 WARN 里。业务异常上浮给调用方，技术性异常仍降级返 null。
            throw e;
        } catch (Exception e) {
            log.warn("query embed 失败 text='{}': {}", abbrev(text), e.getMessage());
            return null;
        }
    }

    /** 1 次 LLM chat 生成 K 释义 + HyDE。 */
    private ExpansionPayload generateParaphrases(String query, int count, boolean withHyde, Long userId) {
        String user = """
                用户问题：%s
                请生成 %d 条保持原意、更适合知识库检索的中文改写（去掉"我的/你的"等代词、补全领域术语、换同义说法），
                %s
                只输出 JSON：{"paraphrases":["改写1","改写2"], "hyde":"假想答案一句话"}
                """.formatted(query, count, withHyde
                ? "并写一句'假设知识库里已有一段直接回答该问题的文档'，那段文档大概会说什么（用于 HyDE 向量匹配）。"
                : "hyde 留空字符串。");
        LlmRequest req = LlmRequest.builder()
                .messages(List.of(
                        LlmMessage.builder().role("system").content(
                                "你是查询改写助手，只输出合法 JSON，不要 markdown 代码围栏。").build(),
                        LlmMessage.builder().role("user").content(user).build()))
                .temperature(0.3)
                .maxTokens(400)
                .stream(false)
                .build();
        String json;
        try (var ignored = RagTraceContext.openPurpose("QUERY_REWRITE_AND_HYDE")) {
            json = llmGateway.chat(req, userId).getContent();
        }
        return parsePayload(json, count);
    }

    private ExpansionPayload parsePayload(String json, int count) {
        ExpansionPayload p = new ExpansionPayload(List.of(), "");
        if (json == null || json.isBlank()) {
            return p;
        }
        try {
            String stripped = stripFence(json);
            var node = objectMapper.readTree(stripped);
            List<String> paras = new ArrayList<>();
            var arr = node.get("paraphrases");
            if (arr != null && arr.isArray()) {
                int limit = 0;
                for (var n : arr) {
                    if (limit >= count) {
                        break;
                    }
                    String s = n.asText("").trim();
                    if (!s.isEmpty()) {
                        paras.add(s);
                        limit++;
                    }
                }
            }
            var h = node.get("hyde");
            String hyde = h == null ? "" : h.asText("").trim();
            return new ExpansionPayload(paras, hyde);
        } catch (Exception e) {
            log.warn("query 扩展 JSON 解析失败，跳过扩展: {}", e.getMessage());
            return p;
        }
    }

    private static String stripFence(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl > 0) {
                t = t.substring(firstNl + 1);
            }
            int lastFence = t.lastIndexOf("```");
            if (lastFence > 0) {
                t = t.substring(0, lastFence);
            }
        }
        return t.trim();
    }

    private static String abbrev(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 30 ? s.substring(0, 30) + "…" : s;
    }

    /** 扩展产物：规范 query + halfvec 列表（规范第一个）。 */
    public record ExpandedQuery(String canonicalQuery, List<String> qHalfs) {
    }

    private record ExpansionPayload(List<String> paraphrases, String hyde) {
    }
}
