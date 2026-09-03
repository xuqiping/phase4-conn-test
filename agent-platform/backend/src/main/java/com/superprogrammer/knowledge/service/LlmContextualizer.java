package com.superprogrammer.knowledge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.knowledge.config.RagContextualProperties;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.llm.dto.LlmResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * LLM 定位表生成（WP3 Step1，规格 §6 C4）：每文档 1 次 LLM 调用，为全部 chunk 产出
 * 「定位语」（该块在文档中的位置与主题，≤50 字），供索引 embed 文本升级为
 * 规则前缀+定位语+原文（Step2 接线），提升向量对上下文的区分度。
 *
 * <p>与规则版 {@link Contextualizer} 分工：本类只产定位表（path→定位语），纯规则前缀仍由
 * Contextualizer 拼——两版可独立降级/独立测试。key=chunk path（如 /L0-0/L2-1，落库前即确定，
 * 不依赖 DB 回填 id）。
 *
 * <p>容错（坑点预判）：LLM 输出 JSON 烂尾/缺项 → **逐 chunk 独立降级**（缺席=纯规则前缀），
 * 非整文档失败；幻觉 path（不在输入清单）丢弃；定位语 &gt;50 字截断；命中治理词
 * （所有者/授权/权限/保密等）整条丢弃——词级替换有残留风险，整条丢=信息零泄漏，该 chunk 降级。
 *
 * <p>计费归户文档上传者（docOwner）；温度 0.3 与 L1 摘要同口径（DocumentParserService.chatJson）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmContextualizer {

    /** 定位语不得携带治理信息（Contextualizer 既有约束「不含权限治理信息」的输出侧过滤）。 */
    private static final List<String> GOVERNANCE_WORDS = List.of(
            "所有者", "归属", "授权", "权限", "可见性", "保密", "机密", "密级",
            "owner", "permission", "authorized", "confidential");
    private static final int LOCATOR_MAX_CHARS = 50;

    private final LlmGateway llmGateway;
    private final RagContextualProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** chunk 清单条目：path（节点 path，落库前确定）/ 标题 / 首行（≤60 字，辅助 LLM 定位）。 */
    public record ChunkBrief(String path, String title, String firstLine) {}

    /**
     * 生成定位表。
     *
     * @return path→定位语；开关关/异常/清单空={}(调用方整体降级纯规则)；输出缺席的 path=该 chunk 降级
     */
    public Map<String, String> generateLocators(KnowledgeDocument doc, String l1Summary,
                                                List<ChunkBrief> chunks, Long docOwner) {
        if (!props.getLlm().isEnabled() || chunks == null || chunks.isEmpty()) {
            return Map.of();
        }
        List<ChunkBrief> capped = chunks.size() > props.getLlm().getMaxChunks()
                ? chunks.subList(0, props.getLlm().getMaxChunks()) : chunks;
        try {
            LlmRequest req = LlmRequest.builder()
                    .model(props.getLlm().getModel())
                    .messages(List.of(
                            new com.superprogrammer.llm.dto.LlmMessage("system", SYSTEM_PROMPT),
                            new com.superprogrammer.llm.dto.LlmMessage("user",
                                    buildUser(doc, l1Summary, capped))))
                    .temperature(0.3)
                    .maxTokens(props.getLlm().getMaxTokens())
                    .stream(false)
                    .build();
            LlmResponse resp = llmGateway.chat(req, docOwner);   // 计费归户文档上传者
            return parse(resp == null ? null : resp.getContent(), capped);
        } catch (Exception e) {
            log.warn("LLM 定位表生成失败（整文档降级纯规则前缀）docId={}: {}",
                    doc == null ? null : doc.getId(), e.getMessage());
            return Map.of();
        }
    }

    /**
     * 解析+护栏：幻觉 path 丢弃/超长截断/治理词整条丢/缺项降级。
     * 烂尾容错（坑点预判）：数组整体解析失败（maxTokens 截断常见烂尾）→ 逐对象打捞
     * （花括号配对扫描已完整的 {...} 项）——**已完整的项保留，烂尾项降级**，非整文档失败。
     */
    Map<String, String> parse(String content, List<ChunkBrief> chunks) {
        String stripped = stripFences(content);
        List<JsonNode> items = new java.util.ArrayList<>();
        try {
            JsonNode arr = objectMapper.readTree(stripped);
            if (arr != null && arr.isArray()) {
                arr.forEach(items::add);
            } else {
                items.addAll(salvageObjects(stripped));
            }
        } catch (Exception e) {
            items.addAll(salvageObjects(stripped));
        }
        Set<String> known = chunks.stream().map(ChunkBrief::path).collect(Collectors.toSet());
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode item : items) {
            String path = item.path("path").asText("").trim();
            String locator = item.path("locator").asText("").trim();
            if (path.isEmpty() || !known.contains(path) || locator.isEmpty()) {
                continue;   // 幻觉 path / 空 locator 丢弃
            }
            if (locator.length() > LOCATOR_MAX_CHARS) {
                locator = locator.substring(0, LOCATOR_MAX_CHARS);
            }
            if (containsGovernanceWord(locator)) {
                continue;   // 治理词→整条丢（该 chunk 降级纯规则）
            }
            out.put(path, locator);
        }
        return out;
    }

    /** 花括号配对打捞：字符串感知（引号内 {} 不算边界），抽 raw 中已完整闭合的对象项。 */
    private List<JsonNode> salvageObjects(String raw) {
        List<JsonNode> items = new java.util.ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return items;
        }
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (ch == '}' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    try {
                        items.add(objectMapper.readTree(raw.substring(start, i + 1)));
                    } catch (Exception ignored) {
                        // 单项残缺丢弃，继续打捞后续项
                    }
                    start = -1;
                }
            }
        }
        if (items.size() > 1) {
            log.info("LLM 定位表烂尾打捞：完整 {} 项，烂尾部分降级纯规则前缀", items.size());
        }
        return items;
    }

    private static String buildUser(KnowledgeDocument doc, String l1Summary, List<ChunkBrief> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("文档标题：").append(doc == null || doc.getTitle() == null || doc.getTitle().isBlank()
                ? "未标注" : doc.getTitle().trim()).append('\n');
        sb.append("文档摘要：").append(l1Summary == null || l1Summary.isBlank() ? "无" : l1Summary.trim()).append('\n');
        sb.append("分块清单：\n");
        for (ChunkBrief c : chunks) {
            String firstLine = c.firstLine() == null ? "" : c.firstLine().trim();
            if (firstLine.length() > 60) {
                firstLine = firstLine.substring(0, 60);
            }
            sb.append(c.path()).append(" | ").append(c.title() == null ? "" : c.title()).append(" | ").append(firstLine).append('\n');
        }
        return sb.toString();
    }

    private static boolean containsGovernanceWord(String locator) {
        String lower = locator.toLowerCase();
        return GOVERNANCE_WORDS.stream().anyMatch(lower::contains);
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

    private static final String SYSTEM_PROMPT = """
            你是知识库索引定位器。为文档的每个分块生成一句定位语，帮助检索把块放回上下文。
            只输出 JSON 数组，不要任何其他文字、不要 markdown 代码块。每项格式：
            {"path":"分块路径","locator":"定位语"}
            要求：
            - locator ≤50 字，说明该块在文档中的位置与主题（如：第3章 报销标准下 V2.1 版金额表）
            - 必须覆盖输入清单中的每一个 path；漏掉的块将退回无定位语
            - 不得包含文档所有者、授权、权限、保密等治理信息""";
}
