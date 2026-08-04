package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.MemoryRosterVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 计划12 · I3 · 项目成员离职判定 + 标注（总体设计 §3.7 line 158 L10 离职开关）。
 * <p>
 * 给定项目，解析其 DEPARTED 成员集 + 「已离开人员·{用户名}·{departed_at}」标注，供：
 * <ul>
 *   <li><b>召回侧</b>（TurnPatcher I3-2）：开关关 → 剔 readableAuthors ∩ DEPARTED（优先级高于人员多选）；开 → 保留 + 标注附召回结果。</li>
 *   <li><b>总结侧</b>（ConsolidationService I3-3）：项目总结候选 ∩ 离职开关过滤后集。</li>
 * </ul>
 * <b>summary 不受影响</b>（恒只读自己，向量 14）。<br>
 * 数据源复用 {@link MemoryRosterService#getRoster}（已 join users + 含 DEPARTED/departed_at/username/name），
 * 不新查表——召回/总结本就需 roster 源数据，一次查询两用。
 *
 * @see MemoryRosterService 花名册源数据
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryDepartedResolver {

    private static final String STATUS_DEPARTED = "DEPARTED";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final MemoryRosterService rosterService;

    /**
     * 解析项目 DEPARTED 成员集 + 标注（召回/总结取数共用）。
     *
     * @param projectId 项目 id（null → 空）
     * @return DEPARTED 集（user_id）+ 标注 Map（user_id →「已离开人员·{显示名}·{date}」）；无 DEPARTED → 空
     */
    public DepartedInfo resolveDeparted(Long projectId) {
        if (projectId == null) {
            return DepartedInfo.empty();
        }
        java.util.List<MemoryRosterVO> roster = rosterService.getRoster(projectId);
        if (roster == null || roster.isEmpty()) {
            return DepartedInfo.empty();
        }
        Set<Long> departedIds = new HashSet<>();
        Map<Long, String> annotations = new HashMap<>();
        for (MemoryRosterVO m : roster) {
            if (STATUS_DEPARTED.equals(m.getStatus()) && m.getUserId() != null) {
                departedIds.add(m.getUserId());
                annotations.put(m.getUserId(), annotate(m));
            }
        }
        log.debug("resolveDeparted projectId={} departed={}", projectId, departedIds.size());
        return new DepartedInfo(departedIds, annotations);
    }

    /** 标注文本「已离开人员·{显示名}·{date}」（显示名：name 优先，空回退 username；date 缺省「未知」）。 */
    private String annotate(MemoryRosterVO m) {
        String display = (m.getName() != null && !m.getName().isBlank()) ? m.getName() : m.getUsername();
        OffsetDateTime at = m.getDepartedAt();
        String date = at != null ? DATE_FMT.format(at) : "未知";
        return "已离开人员·" + display + "·" + date;
    }

    /**
     * DEPARTED 解析结果（不可变）。
     *
     * @param departedIds DEPARTED 成员 user_id 集
     * @param annotations user_id → 标注文本（召回结果附「已离开人员·用户名·时间」）
     */
    public record DepartedInfo(Set<Long> departedIds, Map<Long, String> annotations) {
        public DepartedInfo {
            departedIds = departedIds == null ? Set.of() : Set.copyOf(departedIds);
            annotations = annotations == null ? Map.of() : Map.copyOf(annotations);
        }

        public static DepartedInfo empty() {
            return new DepartedInfo(Collections.emptySet(), Collections.emptyMap());
        }

        public boolean isEmpty() {
            return departedIds.isEmpty();
        }

        /** readableAuthors ∩ DEPARTED（开关关时剔这部分；优先级高于人员多选）。 */
        public Set<Long> intersectDeparted(Set<Long> readableAuthors) {
            if (readableAuthors == null || readableAuthors.isEmpty() || isEmpty()) {
                return Collections.emptySet();
            }
            Set<Long> inter = new HashSet<>(readableAuthors);
            inter.retainAll(departedIds);
            return inter;
        }
    }
}
