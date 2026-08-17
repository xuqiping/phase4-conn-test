package com.superprogrammer.chat.service.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.entity.MemoryTag;
import com.superprogrammer.chat.entity.MemoryTurn;
import com.superprogrammer.chat.mapper.MemoryTagMapper;
import com.superprogrammer.chat.mapper.MemoryTurnMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmMessage;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.system.service.SystemSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 5x 四轮 C8（U7）：标签「重新归类」——新建标签后，把旧流水账里漏挂的记忆补进来。
 * <p>
 * 用户在标签库选中标签点「重新归类」→ 按筛选范围（标签创建前 / 时间窗 / 上限）取
 * <b>未挂目标标签</b>的本人 turn → 分批 ≤20 过一次 LLM 判定「是否属于该标签」→
 * 命中行 tag_ids <b>原子增补</b>（{@code array_append} + 防重条件，只增不删）+ usage_count 递增。
 * <p>
 * <b>保守哲学（拍板⑤，与「禁 merge/split」同源）</b>：
 * <ul>
 *   <li>只增补目标标签，<b>不删旧标签</b>——误删不可逆，误增可手动清（后续重新总结不会放大）。 </li>
 *   <li>LLM 判定失败/解析异常 → 该批整体跳过（宁缺勿滥），不影响已命中批。 </li>
 *   <li>总结不在此端点内耦合：turn 挂上后，用户到总结页签点既有「重新总结」（force）生效。 </li>
 * </ul>
 * <b>注入防护</b>：turn 文本 {@code <memory_data>} 包裹按数据对待（照 ingest 范式）。
 * <p>
 * <b>审计/运维</b>：批次结果记 info（范围/命中数，不记内容）；单次扫描上限 {@link #MAX_SCAN}
 * 硬卡（防刷 LLM 计费）；appendTagId 幂等（SQL 条件防重 + 不在集内才动）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryTagReclassifyService {

    /** 单次扫描行数硬上限（运维清单：防刷 LLM 计费）。 */
    public static final int MAX_SCAN = 200;
    /** LLM 判定批大小（与 E-2 backfill 同口径 ≤20/批）。 */
    private static final int BATCH_SIZE = 20;
    /** 单行喂 LLM 的文本截断（l1 缺失回退 raw 片段）。 */
    private static final int TEXT_CAP = 160;
    /** LLM 判定重试次数。 */
    private static final int JUDGE_MAX_ATTEMPTS = 2;

    private final MemoryTagMapper tagMapper;
    private final MemoryTurnMapper turnMapper;
    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;
    private final SystemSettingService systemSettingService;

    /** 重新归类报告（扫描数/命中数/失败批数；不含内容，可直出前端）。 */
    public static class ReclassifyReport {
        public int scanned;         // 筛选后候选行数
        public int judged;          // 实际进入 LLM 判定的行（有文本可判）
        public int hits;            // 增补成功行数
        public int llmFailBatches;  // 判定失败被保守跳过的批数
    }

    public ReclassifyReport reclassify(Long tagId, Long userId, MemoryTagReclassifyParams params) {
        // 本人咽喉：不存在与非本人统一 NOT_FOUND（不泄露存在性差异）
        MemoryTag tag = tagMapper.selectById(tagId);
        if (tag == null || !tag.getUserId().equals(userId)) {
            log.info("重新归类越权/不存在拦截 userId={} tagId={}", userId, tagId);
            throw new BusinessException(ErrorCode.NOT_FOUND, "标签不存在");
        }

        OffsetDateTime start = parseIso(params.start(), "start");
        OffsetDateTime end = parseIso(params.end(), "end");
        // olderThanTag 缺省 true：建标签之前的行最可能漏挂（U7 主场景）
        OffsetDateTime olderThan = Boolean.FALSE.equals(params.olderThanTag()) ? null : tag.getCreatedAt();
        int limit = params.limit() == null ? MAX_SCAN : Math.min(Math.max(params.limit(), 1), MAX_SCAN);

        List<MemoryTurn> candidates = turnMapper.findReclassifyCandidates(userId, tagId, olderThan, start, end, limit);
        ReclassifyReport report = new ReclassifyReport();
        report.scanned = candidates.size();
        if (candidates.isEmpty() || Boolean.TRUE.equals(params.dryRun())) {
            // dryRun=预估：只取数计数，不调 LLM 不落库（前端 modal「预估条数」）
            return report;
        }

        // 逐行取判别文本：l1 优先，raw 回退截断；两者皆空跳过（无法判定）
        List<JudgeRow> rows = new ArrayList<>();
        for (MemoryTurn t : candidates) {
            String text = t.getL1Summary();
            if (text == null || text.isBlank()) {
                text = t.getRawContent();
            }
            if (text != null && !text.isBlank()) {
                rows.add(new JudgeRow(t.getId(),
                        text.length() > TEXT_CAP ? text.substring(0, TEXT_CAP) : text));
            }
        }
        report.judged = rows.size();
        if (rows.isEmpty()) {
            return report;
        }

        String model = systemSettingService.getMemoryJudgeModel();
        for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
            List<JudgeRow> batch = rows.subList(i, Math.min(i + BATCH_SIZE, rows.size()));
            Set<Long> hitIds = judgeBatch(tag, batch, model, userId);
            if (hitIds == null) {
                report.llmFailBatches++;
                continue;
            }
            for (Long id : hitIds) {
                if (turnMapper.appendTagId(id, tagId) > 0) {
                    tagMapper.incrementUsage(tagId);
                    report.hits++;
                }
            }
        }
        log.info("重新归类完成 userId={} tagId={} label={} olderThan={} start={} end={} scanned={} judged={} hits={} failBatches={}",
                userId, tagId, tag.getLabel(), olderThan != null, params.start(), params.end(),
                report.scanned, report.judged, report.hits, report.llmFailBatches);
        return report;
    }

    /** 筛选参数（controller 解析请求体后组装；record 保持 service 无 HTTP 依赖）。 */
    public record MemoryTagReclassifyParams(Boolean olderThanTag, String start, String end, Integer limit,
                                            Boolean dryRun) {
        public static MemoryTagReclassifyParams of(com.superprogrammer.chat.dto.MemoryTagReclassifyRequest req) {
            return new MemoryTagReclassifyParams(req.getOlderThanTag(), req.getStart(), req.getEnd(),
                    req.getLimit(), req.getDryRun());
        }
    }

    private record JudgeRow(Long id, String text) {
    }

    // ============================ 内部 ============================

    /**
     * 一批 ≤20 行过一次 LLM 判定。失败/解析异常 → null（调用方保守跳过该批）。
     * <b>安全</b>：行文本 {@code <memory_data>} 包裹按数据对待（照 ingest 范式防注入）。
     */
    private Set<Long> judgeBatch(MemoryTag tag, List<JudgeRow> batch, String model, Long userId) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            items.append(String.format("{\"id\":%d,\"text\":%s}",
                    batch.get(i).id(), quote(batch.get(i).text())));
            if (i < batch.size() - 1) {
                items.append(',');
            }
        }
        String aliases = tag.getAliases() == null ? "" : String.join("、", tag.getAliases());
        String prompt = """
                你是记忆归类判定器。判断每条记忆是否属于【目标标签】。
                目标标签：主体=%s / 大类=%s / 标签名=%s；别名：%s
                规则：内容与标签主题明确相关才命中；拿不准或仅字面碰巧 → 不命中（宁缺勿滥）。
                <memory_data>
                [%s]
                </memory_data>
                只返回 JSON 数组：命中行的 id 列表，如 [1,5]；全部不命中返回 []。禁止解释文字。""".formatted(
                tag.getSubject(), tag.getTopic(), tag.getLabel(),
                aliases.isBlank() ? "（无）" : aliases, items);

        for (int attempt = 1; attempt <= JUDGE_MAX_ATTEMPTS; attempt++) {
            try {
                String raw = llmGateway.chat(LlmRequest.builder()
                                .model(model)
                                .messages(List.of(LlmMessage.builder().role("user").content(prompt).build()))
                                .temperature(0.0)
                                // 思考与正文共享预算：glm 系忽略 disableThinking 时需兜住思考+JSON（第三轮范式 2560）
                                .maxTokens(2560)
                                .disableThinking(true)
                                .build(), userId).getContent();
                Set<Long> hits = parseHitIds(raw, batch);
                if (hits != null) {
                    return hits;
                }
                log.warn("重新归类 LLM 返回不可解析(第{}/{})次 tagId={} batchSize={}",
                        attempt, JUDGE_MAX_ATTEMPTS, tag.getId(), batch.size());
            } catch (Exception ex) {
                log.warn("重新归类 LLM 异常(第{}/{})次 tagId={}: {}",
                        attempt, JUDGE_MAX_ATTEMPTS, tag.getId(), ex.getMessage());
            }
        }
        return null;
    }

    /** 宽容解析命中 id 数组（剥围栏/截取 [ ]）；非法或含批外 id → null（保守弃整批）。 */
    private Set<Long> parseHitIds(String raw, List<JudgeRow> batch) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        if (s.startsWith("```")) {
            int st = s.indexOf('\n') + 1;
            int en = s.lastIndexOf("```");
            s = (en > st) ? s.substring(st, en).trim() : (st > 0 ? s.substring(st).trim() : s);
        }
        int start = s.indexOf('[');
        int end = s.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(s.substring(start, end + 1));
            if (!root.isArray()) {
                return null;
            }
            Set<Long> valid = new HashSet<>();
            for (JsonNode n : root) {
                long id = n.isNumber() ? n.asLong(0) : n.path("id").asLong(0);
                if (id != 0) {
                    valid.add(id);
                }
            }
            // 只接受批内 id（防 LLM 幻觉 id 写到别行）
            Set<Long> batchIds = new HashSet<>();
            for (JudgeRow r : batch) {
                batchIds.add(r.id());
            }
            valid.retainAll(batchIds);
            return valid;
        } catch (Exception e) {
            return null;
        }
    }

    private static OffsetDateTime parseIso(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " 时间格式非法（需 ISO-8601，如 2026-01-01T00:00:00+08:00）");
        }
    }

    private static String quote(String s) {
        if (s == null) {
            return "\"\"";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
