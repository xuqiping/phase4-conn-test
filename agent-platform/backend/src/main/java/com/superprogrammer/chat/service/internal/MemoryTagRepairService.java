package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.entity.MemoryTag;
import com.superprogrammer.chat.mapper.MemoryTagMapper;
import com.superprogrammer.chat.mapper.MemoryTagRepairMapper;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmMessage;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.system.service.SystemSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * V77 大类重映射 / 孤儿锚点回填（管理员一次性 repair 工具，非用户路径）。
 * <p>
 * 解决存量问题：① embedding 404 期间生成的 NULL 锚点孤儿标签（路径③/路由粗筛永远跳过）；
 * ② 历史细标签（旅游攻略/旅行计划并存等）需归并到 大类。
 * <p>
 * 流程：
 * <ol>
 *   <li><b>补 NULL 锚点</b>：anchor 重生（embed 恢复后可被路径③/路由命中）。</li>
 *   <li><b>LLM 大类重分类</b>：按 user 把 tag 的 (subject,topic,label) 灌 LLM → 每个 tag_id 映射到 base vocab 大类。</li>
 *   <li><b>归并冲突组</b>：同 (user,subject,大类) 下 >1 个 tag → survivor（usage_count 最高）吞并 loser：
 *       6 表 tag_id/tag_ids 重指 + 别名合并 + loser 软删。单标签组 → 仅改 topic 为大类 + 重生锚点。</li>
 * </ol>
 * <b>dry-run</b>：只算报告不落库；{@code dryRun=false} 单事务执行（不可逆，务必先 dry-run 审）。
 * <p>
 * <b>铁律</b>：tag_id 是 6 表关联键，归并靠 repairMapper 跨表重指（非通用用户合并端点；误并不可逆）。
 *
 * @see MemoryTagRepairMapper 6 表重指 + 孤儿重生 SQL
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryTagRepairService {

    private static final int CLASSIFY_MAX_ATTEMPTS = 2;

    private final MemoryTagMapper tagMapper;
    private final MemoryTagRepairMapper repairMapper;
    private final MemoryTagAnchorService anchorService;
    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;
    private final SystemSettingService systemSettingService;

    @Transactional
    public RepairReport repair(boolean dryRun) {
        RepairReport report = new RepairReport();
        report.dryRun = dryRun;

        // Step 1：孤儿锚点重生
        List<MemoryTag> orphans = repairMapper.findNullAnchorTags();
        report.orphanCount = orphans.size();
        for (MemoryTag tag : orphans) {
            if (!dryRun) {
                MemoryTagAnchorService.AnchorPayload anchor = anchorService.build(
                        tag.getUserId(), tag.getSubject(), tag.getTopic(), tag.getLabel(), tag.getAliases());
                repairMapper.updateTopicAndAnchor(tag.getId(), tag.getTopic(),
                        anchor != null ? anchor.halfvec() : null,
                        anchor != null ? anchor.tokens() : null);
            }
            report.orphanRegenerated.add(tag.getId());
        }
        log.info("大类回填 step1 孤儿锚点 dryRun={} count={}", dryRun, orphans.size());

        // Step 2+3：按 user 大类重分类 + 归并
        List<MemoryTag> all = tagMapper.selectList(new LambdaQueryWrapper<MemoryTag>()
                .eq(MemoryTag::getDeleted, 0));
        Map<Long, List<MemoryTag>> byUser = new LinkedHashMap<>();
        for (MemoryTag t : all) {
            byUser.computeIfAbsent(t.getUserId(), k -> new ArrayList<>()).add(t);
        }
        List<String> vocab = systemSettingService.getMemoryTagVocab();

        for (Map.Entry<Long, List<MemoryTag>> e : byUser.entrySet()) {
            Long userId = e.getKey();
            List<MemoryTag> tags = e.getValue();
            Map<Long, String> classified = classifyTags(userId, tags, vocab);
            report.classifiedCount += classified.size();

            // 按 (subject, 大类) 分组
            Map<String, List<MemoryTag>> groups = new LinkedHashMap<>();
            for (MemoryTag t : tags) {
                String cat = classified.getOrDefault(t.getId(), t.getTopic());
                if (cat == null || cat.isBlank()) {
                    cat = "其他";
                }
                groups.computeIfAbsent(t.getSubject() + "" + cat, k -> new ArrayList<>()).add(t);
            }

            for (List<MemoryTag> group : groups.values()) {
                String category = classified.getOrDefault(group.get(0).getId(), group.get(0).getTopic());
                if (group.size() == 1) {
                    // 单标签组：仅改 topic 为大类 + 重生锚点（topic 已是该大类则跳过）
                    MemoryTag t = group.get(0);
                    if (!category.equals(t.getTopic())) {
                        if (!dryRun) {
                            MemoryTagAnchorService.AnchorPayload anchor = anchorService.build(
                                    t.getUserId(), t.getSubject(), category, t.getLabel(), t.getAliases());
                            repairMapper.updateTopicAndAnchor(t.getId(), category,
                                    anchor != null ? anchor.halfvec() : null,
                                    anchor != null ? anchor.tokens() : null);
                        }
                        report.retagged.add(t.getId());
                    }
                    continue;
                }
                // 多标签组：survivor（usage_count 最高，平手 id 最小）吞并 loser
                group.sort(Comparator
                        .comparingInt((MemoryTag t) -> t.getUsageCount() == null ? 0 : -t.getUsageCount())
                        .thenComparingLong(MemoryTag::getId));
                MemoryTag survivor = group.get(0);
                List<MemoryTag> losers = group.subList(1, group.size());

                MergeGroup mg = new MergeGroup();
                mg.userId = userId;
                mg.category = category;
                mg.survivorId = survivor.getId();
                for (MemoryTag loser : losers) {
                    mg.loserIds.add(loser.getId());
                    if (!dryRun) {
                        reassignAll(loser.getId(), survivor.getId());
                    }
                }
                // survivor 改 topic 为大类 + 重生锚点
                if (!dryRun) {
                    MemoryTagAnchorService.AnchorPayload anchor = anchorService.build(
                            survivor.getUserId(), survivor.getSubject(), category, survivor.getLabel(), survivor.getAliases());
                    repairMapper.updateTopicAndAnchor(survivor.getId(), category,
                            anchor != null ? anchor.halfvec() : null,
                            anchor != null ? anchor.tokens() : null);
                }
                report.mergeGroups.add(mg);
                log.info("大类回填 归并 userId={} category={} survivor={} losers={} dryRun={}",
                        userId, category, survivor.getId(), mg.loserIds, dryRun);
            }
        }
        log.info("大类回填完成 dryRun={} orphan={} classified={} retag={} mergeGroup={}",
                dryRun, report.orphanCount, report.classifiedCount, report.retagged.size(), report.mergeGroups.size());
        return report;
    }

    /** 6 表 tag_id/tag_ids 重指 loser→survivor（单 repairMapper 事务内）。 */
    private void reassignAll(Long loser, Long survivor) {
        repairMapper.mergeAliases(survivor, loser);
        repairMapper.reassignSummariesTagId(loser, survivor);
        repairMapper.reassignConflictTagId(loser, survivor);
        repairMapper.reassignTurnsTagIds(loser, survivor);
        repairMapper.reassignEntriesTagIds(loser, survivor);
        repairMapper.reassignEntryCoverageTagId(loser, survivor);
        repairMapper.reassignSummaryCoverageTagId(loser, survivor);
        repairMapper.softDeleteTag(loser);
    }

    /** LLM 把该 user 的 tag 批量映射到大类。失败/解析异常 → 空表（保守：不归并，留原 topic）。 */
    private Map<Long, String> classifyTags(Long userId, List<MemoryTag> tags, List<String> vocab) {
        Map<Long, String> result = new HashMap<>();
        if (tags.isEmpty()) {
            return result;
        }
        StringBuilder tagList = new StringBuilder();
        Set<String> vocabSet = Set.copyOf(vocab);
        for (int i = 0; i < tags.size(); i++) {
            MemoryTag t = tags.get(i);
            tagList.append(String.format("{\"id\":%d,\"subject\":%s,\"topic\":%s,\"label\":%s}",
                    t.getId(), quote(t.getSubject()), quote(t.getTopic()), quote(t.getLabel())));
            if (i < tags.size() - 1) {
                tagList.append(',');
            }
        }
        String prompt = String.format(CLASSIFY_PROMPT, String.join("、", vocab), tagList);
        String model = systemSettingService.getMemoryJudgeModel();

        for (int attempt = 1; attempt <= CLASSIFY_MAX_ATTEMPTS; attempt++) {
            try {
                String raw = llmGateway.chat(LlmRequest.builder()
                                .model(model)
                                .messages(List.of(LlmMessage.builder().role("user").content(prompt).build()))
                                .temperature(0.0)
                                .maxTokens(1000)
                                .build(), userId).getContent();
                JsonNode root = parseJsonArray(raw);
                if (root != null) {
                    for (JsonNode n : root) {
                        long id = n.path("id").asLong(0);
                        String topic = n.path("topic").asText(null);
                        if (id != 0 && topic != null && !topic.isBlank()) {
                            // 仅接受词表内大类（防 LLM 自创细主题）
                            result.put(id, vocabSet.contains(topic) ? topic : "其他");
                        }
                    }
                    return result;
                }
            } catch (Exception ex) {
                log.warn("大类重分类 LLM 异常(第{}/{})次 userId={}: {}", attempt, CLASSIFY_MAX_ATTEMPTS, userId, ex.getMessage());
            }
        }
        log.warn("大类重分类 {} 次均失败 userId={} → 保守不归并（留原 topic）", CLASSIFY_MAX_ATTEMPTS, userId);
        return result;
    }

    private JsonNode parseJsonArray(String raw) {
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
            JsonNode node = objectMapper.readTree(s.substring(start, end + 1));
            return node.isArray() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String quote(String s) {
        if (s == null) {
            return "\"\"";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** 重分类 prompt：每个 tag 选最贴合的大类（词表内）；都不贴 → 「其他」。 */
    private static final String CLASSIFY_PROMPT = """
            你是标签大类归类器。把每个标签归入最贴合的【大类】（同一大类的标签共用同一 topic，便于合并）。
            大类词表：%s
            规则：选词表中最能覆盖该标签内容的大类；若都不贴切，归「其他」。不要自创新大类。
            待归类标签：[%s]
            只返回 JSON 数组，每项 {"id":标签id,"topic":大类}。禁止解释文字。
            """;

    // ---------- 报告 ----------

    public static class RepairReport {
        public boolean dryRun;
        public int orphanCount;
        public int classifiedCount;
        public final List<Long> orphanRegenerated = new ArrayList<>();
        public final List<Long> retagged = new ArrayList<>();
        public final List<MergeGroup> mergeGroups = new ArrayList<>();
    }

    public static class MergeGroup {
        public Long userId;
        public String category;
        public Long survivorId;
        public final List<Long> loserIds = new ArrayList<>();
    }
}
