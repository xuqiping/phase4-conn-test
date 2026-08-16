package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.entity.MemoryProjectMember;
import com.superprogrammer.chat.entity.MemoryProjectRule;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import com.superprogrammer.project.entity.Project;
import com.superprogrammer.project.mapper.ProjectMapper;
import com.superprogrammer.system.service.SystemSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 5x #7 · 收录命中确定性快检（确认式回复 MVP）。
 * <p>
 * 只做<b>确定性</b>匹配：附件文件名 vs 本人 gen 开项目的文件名硬规则（filename_patterns），
 * 口径与 {@link MemoryRoutingService#routeFilenameHardRule} 完全一致——trim+小写子串包含、
 * 先建规则优先。纯文本消息零命中（语义路由靠向量/LLM，维持事后异步收录，绝不拖首字延迟）。
 * <p>
 * 运维：规则集按 userId 内存缓存 TTL {@value #CACHE_TTL_MS}ms（省每条消息一次 DB 扫）。
 * 规则改动后最坏滞后一个 TTL 窗口（仅确认文案滞后，不产生错误收录——实际收录以异步路由为准）。
 * 调用方 fail-open：本服务任何异常由调用方吞掉走原全量路径。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InclusionQuickCheckService {

    /** 确定性命中项：哪个项目的哪条 pattern 命中了哪个附件名。 */
    public record Hit(Long ruleId, Long projectId, String projectName,
                      String matchedPattern, String matchedFile) {}

    private static final long CACHE_TTL_MS = 15_000;
    private static final String STATUS_ACTIVE_MEMBER = "ACTIVE";

    private final MemoryProjectMemberMapper memberMapper;
    private final MemoryProjectRuleService ruleService;
    private final MemoryGenToggleService toggleService;
    private final ProjectMapper projectMapper;
    private final SystemSettingService systemSettingService;

    private record RuleCache(List<MemoryProjectRule> rules, long loadedAt) {}

    private final ConcurrentHashMap<Long, RuleCache> ruleCache = new ConcurrentHashMap<>();

    /** 总开关（rag.memory.inclusion-confirm.enabled，热关=回旧行为直接全量回答）。 */
    public boolean enabled() {
        return systemSettingService.getInclusionConfirmEnabled();
    }

    /**
     * 快检入口。fileNames=附件原名集（无附件返空——文本语义命中不可确定性预判，不进 MVP）。
     * 命中列表按规则 createdAt 升序（与路由 5x #8 先建者赢同口径），每项目至多一条。
     */
    public List<Hit> quickCheck(Long userId, List<String> fileNames) {
        if (userId == null || fileNames == null || fileNames.isEmpty()) {
            return List.of();
        }
        List<MemoryProjectRule> rules = cachedRules(userId);
        if (rules.isEmpty()) {
            return List.of();
        }
        List<MemoryProjectRule> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator
                .comparing((MemoryProjectRule r) -> r.getCreatedAt(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(MemoryProjectRule::getId));
        List<Hit> hits = new ArrayList<>();
        Set<Long> seenProjects = new HashSet<>();
        for (MemoryProjectRule rule : sorted) {
            if (rule.getFilenamePatterns() == null || rule.getFilenamePatterns().isEmpty()) {
                continue;
            }
            for (String name : fileNames) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                String hitPattern = firstMatchedPattern(name, rule.getFilenamePatterns());
                if (hitPattern != null && seenProjects.add(rule.getProjectId())) {
                    hits.add(new Hit(rule.getId(), rule.getProjectId(), null, hitPattern, name));
                }
            }
        }
        if (hits.isEmpty()) {
            return List.of();
        }
        fillProjectNames(hits);
        return hits;
    }

    /** 同 routeFilenameHardRule 口径：文件名小写含任一 pattern（trim 后小写）即命中，返命中的原 pattern。 */
    private static String firstMatchedPattern(String fileName, List<String> patterns) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String p : patterns) {
            if (p == null) {
                continue;
            }
            String pt = p.trim().toLowerCase(Locale.ROOT);
            if (!pt.isEmpty() && lower.contains(pt)) {
                return p.trim();
            }
        }
        return null;
    }

    /** 规则集缓存：TTL 内直读；过期重查（本人 ACTIVE 项目 × gen 双开关 × enabled+anchor 就绪候选）。 */
    private List<MemoryProjectRule> cachedRules(Long userId) {
        long now = System.currentTimeMillis();
        RuleCache cache = ruleCache.get(userId);
        if (cache != null && now - cache.loadedAt() < CACHE_TTL_MS) {
            return cache.rules();
        }
        List<Long> projectIds = memberMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MemoryProjectMember>()
                        .select(MemoryProjectMember::getProjectId)
                        .eq(MemoryProjectMember::getUserId, userId)
                        .eq(MemoryProjectMember::getStatus, STATUS_ACTIVE_MEMBER))
                .stream().map(MemoryProjectMember::getProjectId).distinct().toList();
        List<Long> genOn = projectIds.stream()
                .filter(pid -> toggleService.resolveGenEnabled(userId, pid))
                .toList();
        List<MemoryProjectRule> rules = ruleService.findRoutingCandidates(genOn);
        ruleCache.put(userId, new RuleCache(rules, now));
        return rules;
    }

    /** 补项目名（确认文案要展示）；查不到兜底「项目#id」。 */
    private void fillProjectNames(List<Hit> hits) {
        Set<Long> ids = new HashSet<>();
        for (Hit h : hits) {
            ids.add(h.projectId());
        }
        Map<Long, String> names = new HashMap<>();
        for (Project p : projectMapper.selectBatchIds(ids)) {
            names.put(p.getId(), p.getName());
        }
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            hits.set(i, new Hit(h.ruleId(), h.projectId(),
                    names.getOrDefault(h.projectId(), "项目#" + h.projectId()),
                    h.matchedPattern(), h.matchedFile()));
        }
    }
}
