package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.chat.entity.MemoryProjectEntry;
import com.superprogrammer.chat.entity.MemoryProjectMember;
import com.superprogrammer.chat.entity.MemoryProjectRule;
import com.superprogrammer.chat.entity.MemoryTag;
import com.superprogrammer.chat.mapper.MemoryProjectEntryMapper;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import com.superprogrammer.chat.mapper.MemoryProjectRuleMapper;
import com.superprogrammer.chat.mapper.MemoryTagMapper;
import com.superprogrammer.system.service.SystemSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 记忆二期 P1 · 项目收录路由器（FR-002/003/004/008）。
 * <p>
 * 挂在 {@code MemoryGenerationService.processTurn} 尾部，独立 fire-and-forget（本类自带
 * executor 提交 + 全程 try/catch，异常只记日志，绝不炸主写入链）。
 * <p>
 * 管线（设计 §4.1③）：
 * <pre>
 *   总开关 → 用户 ACTIVE 项目（gen 双开关 FR-008：owner 收录开关 AND 会员「允许被路由」覆写）
 *   → 候选规则（enabled+anchor 就绪）→ 粗筛（turn L1+tag labels vs 规则 anchor，
 *      向量阈值 ∪ BM25，top-K≤3；不过阈值零 LLM）→ 精判（一次 LLM 批量判 K 项目）
 *   → 置信度分流（≥auto ACTIVE / ≥review PENDING_REVIEW / 否则丢弃）
 *   → 蒸馏文本敏感黑名单二次扫描（命中降 PENDING_REVIEW，宁漏勿错）→ 落 entries
 * </pre>
 * 运维：每步结构化日志（sessionId 关联），粗筛命中数/精判候选数/分流结果全打点。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryRoutingService {

    private static final String STATUS_ACTIVE_MEMBER = "ACTIVE";
    private static final int COARSE_TOP_K = 3;

    private final MemoryProjectMemberMapper memberMapper;
    private final MemoryProjectRuleMapper ruleMapper;
    private final MemoryProjectEntryMapper entryMapper;
    private final MemoryTagMapper tagMapper;
    private final MemoryProjectRuleService ruleService;
    private final MemoryGenToggleService toggleService;
    private final MemoryTagAnchorService anchorService;
    private final MemoryEntryDistiller distiller;
    private final MemoryPrefilter prefilter;
    private final SystemSettingService systemSettingService;
    private final TaskExecutor memoryTaskExecutor;

    /** 路由入参（一轮对话双侧合并后的蒸馏原料；fileId 非空 = P3 文件记忆路由，落 content_type=FILE 条目）。 */
    public record RoutingInput(Long userId, Long sessionId, Long sourceTurnId,
                               String l1, String l2, List<Long> tagIds, String fileId, String chatModel) {
        /** 对话轮入参（兼容旧签名，fileId=null，chatModel=null）。 */
        public RoutingInput(Long userId, Long sessionId, Long sourceTurnId,
                            String l1, String l2, List<Long> tagIds) {
            this(userId, sessionId, sourceTurnId, l1, l2, tagIds, null, null);
        }

        /** 对话轮入参（带对话 model，fileId=null）。 */
        public RoutingInput(Long userId, Long sessionId, Long sourceTurnId,
                            String l1, String l2, List<Long> tagIds, String chatModel) {
            this(userId, sessionId, sourceTurnId, l1, l2, tagIds, null, chatModel);
        }

        /** P3 Step 4（FR-204）文件记忆入参：文本=文件 l1/l2，无 sourceTurn，无对话 model。 */
        public static RoutingInput ofFile(Long userId, String fileId, String l1, String l2, List<Long> tagIds) {
            return new RoutingInput(userId, null, null, l1, l2, tagIds, fileId, null);
        }
    }

    /** fire-and-forget 入口：提交 executor 立即返回；队列满 → 降级日志（不影响主写入）。 */
    public void routeAsync(RoutingInput input) {
        try {
            memoryTaskExecutor.execute(() -> routeSafely(input));
        } catch (TaskRejectedException e) {
            log.warn("记忆路由任务被拒(队列满) userId={} sessionId={}: {}",
                    input.userId(), input.sessionId(), e.getMessage());
        }
    }

    /** 全兜底壳：路由任何异常只记日志（路由失败降级=不收录，宁漏勿错）。 */
    private void routeSafely(RoutingInput input) {
        try {
            route(input);
        } catch (Exception e) {
            log.warn("记忆路由异常降级(不收录) userId={} sessionId={}: {}",
                    input.userId(), input.sessionId(), e.getMessage());
        }
    }

    /** 路由主流程（包可见供单测直调）。 */
    void route(RoutingInput input) {
        if (input == null || input.userId() == null) {
            return;
        }
        // ① 总开关
        if (!systemSettingService.getMemoryRoutingEnabled()) {
            log.debug("路由总开关关 userId={} sessionId={} → 跳过", input.userId(), input.sessionId());
            return;
        }
        // ② 用户 ACTIVE 项目 × gen 双开关（FR-008：owner 收录开关 AND 会员「允许被路由」覆写）
        List<Long> projectIds = memberMapper.selectList(new LambdaQueryWrapper<MemoryProjectMember>()
                        .select(MemoryProjectMember::getProjectId)
                        .eq(MemoryProjectMember::getUserId, input.userId())
                        .eq(MemoryProjectMember::getStatus, STATUS_ACTIVE_MEMBER))
                .stream().map(MemoryProjectMember::getProjectId).distinct().toList();
        if (projectIds.isEmpty()) {
            log.debug("路由跳过 userId={} sessionId={} 无 ACTIVE 项目", input.userId(), input.sessionId());
            return;
        }
        List<Long> genOnProjects = projectIds.stream()
                .filter(pid -> toggleService.resolveGenEnabled(input.userId(), pid))
                .toList();
        if (genOnProjects.isEmpty()) {
            log.info("路由跳过 userId={} sessionId={} 项目双开关全关 projects={}",
                    input.userId(), input.sessionId(), projectIds);
            return;
        }
        // ③ 候选规则（enabled + anchor 就绪）
        List<MemoryProjectRule> candidates = ruleService.findRoutingCandidates(genOnProjects);
        if (candidates.isEmpty()) {
            log.debug("路由跳过 userId={} sessionId={} 无候选规则", input.userId(), input.sessionId());
            return;
        }
        // ④ 粗筛：turn L1 + tag labels 算查询锚点 → 向量阈值 ∪ BM25，top-K≤3
        String queryText = buildQueryText(input);
        if (queryText.isBlank()) {
            return;
        }
        MemoryTagAnchorService.AnchorPayload queryAnchor = anchorService.build(input.userId(), null, null, queryText, null);
        if (queryAnchor == null) {
            log.warn("路由查询锚点构建失败 userId={} sessionId={} → 降级不收录", input.userId(), input.sessionId());
            return;
        }
        List<Long> candidateRuleIds = candidates.stream().map(MemoryProjectRule::getId).toList();
        double coarseThreshold = systemSettingService.getMemoryRoutingCoarseThreshold();
        List<Long> vecHit = ruleMapper.findWithinAnchorThreshold(candidateRuleIds, queryAnchor.halfvec(), coarseThreshold, COARSE_TOP_K);
        List<Long> bm25Hit = ruleMapper.rankByAnchorTsv(candidateRuleIds,
                com.superprogrammer.knowledge.util.TsQueryUtil.toOrQuery(queryAnchor.tokens()), COARSE_TOP_K);
        List<Long> shortlisted = mergeRrf(vecHit, bm25Hit, COARSE_TOP_K);
        log.info("路由粗筛 userId={} sessionId={} candidates={} vecHit={} bm25Hit={} shortlisted={}",
                input.userId(), input.sessionId(), candidates.size(), vecHit.size(), bm25Hit.size(), shortlisted.size());
        if (shortlisted.isEmpty()) {
            return;   // 零 LLM 成本护栏（FR-002）
        }
        Map<Long, MemoryProjectRule> ruleById = candidates.stream()
                .collect(Collectors.toMap(MemoryProjectRule::getId, Function.identity()));
        List<MemoryProjectRule> shortRules = shortlisted.stream().map(ruleById::get).filter(r -> r != null).toList();

        // ⑤ 精判：一次 LLM 批量判 K 项目（FR-003）。model 跟随对话所选，null 回退可配默认。
        String judgeModel = input.chatModel() != null && !input.chatModel().isBlank()
                ? input.chatModel() : systemSettingService.getMemoryJudgeModel();
        List<MemoryEntryDistiller.Judgment> judgments = distiller.judge(input.userId(), shortRules, input.l1(), input.l2(), judgeModel);

        // ⑥ 置信度分流 + 脱敏二次扫描 + 落库（FR-004；fileId 非空 = FR-204 文件条目）
        double autoApprove = systemSettingService.getMemoryRoutingAutoApproveThreshold();
        double review = systemSettingService.getMemoryRoutingReviewThreshold();
        boolean isFile = input.fileId() != null && !input.fileId().isBlank();
        int active = 0, pending = 0, dropped = 0;
        for (MemoryEntryDistiller.Judgment j : judgments) {
            if (!j.hit() || j.confidence() < review) {
                dropped++;
                continue;
            }
            // FR-204 幂等：同项目同文件已有未删 FILE 条目 → 跳过（重试重灌不重复收录）
            if (isFile && entryMapper.countFileEntry(j.projectId(), input.fileId()) > 0) {
                dropped++;
                log.info("路由跳过重复文件条目 userId={} projectId={} fileId={}",
                        input.userId(), j.projectId(), input.fileId());
                continue;
            }
            String status = j.confidence() >= autoApprove
                    ? MemoryProjectEntry.STATUS_ACTIVE : MemoryProjectEntry.STATUS_PENDING_REVIEW;
            // 蒸馏脱敏二次扫描（设计 §9-16）：命中黑名单降 PENDING_REVIEW（宁漏勿错）
            if (MemoryProjectEntry.STATUS_ACTIVE.equals(status)
                    && (prefilter.hitsBlacklist(j.distilledL1()) || prefilter.hitsBlacklist(j.distilledL2()))) {
                status = MemoryProjectEntry.STATUS_PENDING_REVIEW;
                log.info("路由蒸馏命中敏感黑名单 → 降 PENDING_REVIEW userId={} projectId={}", input.userId(), j.projectId());
            }
            MemoryProjectEntry entry = new MemoryProjectEntry();
            entry.setProjectId(j.projectId());
            entry.setAuthorUserId(input.userId());
            entry.setSourceTurnId(input.sourceTurnId());
            entry.setTagIds(input.tagIds() != null ? input.tagIds() : List.of());
            entry.setL1Summary(j.distilledL1());
            entry.setL2Detail(j.distilledL2());
            entry.setConfidence(j.confidence());
            entry.setStatus(status);
            entry.setContentType(isFile ? MemoryProjectEntry.CONTENT_TYPE_FILE : MemoryProjectEntry.CONTENT_TYPE_TEXT);
            entry.setFileId(isFile ? input.fileId() : null);
            entry.setChatModel(judgeModel);
            entry.setCreatedBy(input.userId());
            entry.setUpdatedBy(input.userId());
            entryMapper.insert(entry);
            if (MemoryProjectEntry.STATUS_ACTIVE.equals(status)) {
                active++;
            } else {
                pending++;
            }
        }
        log.info("路由分流 userId={} sessionId={} sourceTurnId={} ACTIVE={} PENDING={} dropped={}",
                input.userId(), input.sessionId(), input.sourceTurnId(), active, pending, dropped);
    }

    /** 查询文本 = turn L1 + tag labels（标签名语义密度高，粗筛对齐规则锚点）。 */
    private String buildQueryText(RoutingInput input) {
        StringBuilder sb = new StringBuilder();
        if (input.l1() != null) {
            sb.append(input.l1());
        }
        if (input.tagIds() != null && !input.tagIds().isEmpty()) {
            List<String> labels = tagMapper.selectBatchIds(input.tagIds()).stream()
                    .map(MemoryTag::getLabel).filter(l -> l != null && !l.isBlank()).toList();
            if (!labels.isEmpty()) {
                sb.append(' ').append(String.join(" ", labels));
            }
        }
        return sb.toString().trim();
    }

    /** RRF 简化合并：向量命中（距离升序）优先，BM25 独有补尾，去重保序，cap K。 */
    static List<Long> mergeRrf(List<Long> vecHit, List<Long> bm25Hit, int k) {
        LinkedHashSet<Long> merged = new LinkedHashSet<>();
        if (vecHit != null) {
            merged.addAll(vecHit);
        }
        if (bm25Hit != null) {
            merged.addAll(bm25Hit);
        }
        return new ArrayList<>(merged).subList(0, Math.min(k, merged.size()));
    }
}
