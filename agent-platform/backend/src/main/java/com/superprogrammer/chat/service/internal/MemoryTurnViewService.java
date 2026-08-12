package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.chat.dto.MemoryTurnVO;
import com.superprogrammer.chat.dto.TurnProjectIndexRow;
import com.superprogrammer.chat.entity.MemoryTag;
import com.superprogrammer.chat.entity.MemoryTurn;
import com.superprogrammer.chat.mapper.MemoryProjectEntryMapper;
import com.superprogrammer.chat.mapper.MemoryTagMapper;
import com.superprogrammer.chat.mapper.MemoryTurnMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 计划12 · F · 流水账列表读取（总体设计 §3.1）。
 * <p>
 * <b>仅本人流水账</b>（向量 7/13 ownership：{@code user_id}=当前用户）。batch 回填 tag label 防 N+1。
 * 二期 P1（V67）：turns 纯个人域——挂载项目名回填随一期项目挂载下线（条目「收录项目」列由
 * memory_project_entries 查询承载，不是 turns 回填）。
 *
 * @see MemoryTurnMapper 数据出口
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryTurnViewService {

    private static final int LIST_CAP = 500;

    private final MemoryTurnMapper turnMapper;
    private final MemoryTagMapper tagMapper;
    private final MemoryProjectEntryMapper entryMapper;

    /** 列当前用户本人流水账（按时间倒序，最多 {@link #LIST_CAP}）。 */
    public List<MemoryTurnVO> listMyTurns(Long userId) {
        List<MemoryTurn> turns = turnMapper.selectList(new LambdaQueryWrapper<MemoryTurn>()
                .eq(MemoryTurn::getUserId, userId)
                .orderByDesc(MemoryTurn::getCreatedAt)
                .last("LIMIT " + LIST_CAP));

        Map<Long, MemoryTag> tagMap = batchTags(turns);
        Map<Long, List<MemoryTurnVO.IndexedProject>> indexMap = batchProjectIndex(turns);

        return turns.stream().map(t -> toVO(t, tagMap, indexMap)).toList();
    }

    private Map<Long, MemoryTag> batchTags(List<MemoryTurn> turns) {
        List<Long> tagIds = turns.stream().map(MemoryTurn::getTagIds)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .distinct()
                .toList();
        Map<Long, MemoryTag> map = new HashMap<>();
        if (!tagIds.isEmpty()) {
            for (MemoryTag t : tagMapper.selectBatchIds(tagIds)) {
                map.put(t.getId(), t);
            }
        }
        return map;
    }

    /** 二期 P2：批量回填每条 turn 被收录的项目（一次查询防 N+1；同项目多 tag 去 projectId）。 */
    private Map<Long, List<MemoryTurnVO.IndexedProject>> batchProjectIndex(List<MemoryTurn> turns) {
        List<Long> turnIds = turns.stream().map(MemoryTurn::getId).filter(Objects::nonNull).toList();
        if (turnIds.isEmpty()) {
            return Map.of();
        }
        // turnId -> (projectId 去重保序 -> IndexedProject)
        Map<Long, LinkedHashMap<Long, MemoryTurnVO.IndexedProject>> byTurn = new HashMap<>();
        for (TurnProjectIndexRow r : entryMapper.findProjectIndexByTurnIds(turnIds)) {
            if (r.getProjectId() == null) {
                continue;
            }
            byTurn.computeIfAbsent(r.getTurnId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(r.getProjectId(), pid -> {
                        MemoryTurnVO.IndexedProject p = new MemoryTurnVO.IndexedProject();
                        p.setProjectId(pid);
                        p.setName(r.getProjectName());
                        return p;
                    });
        }
        Map<Long, List<MemoryTurnVO.IndexedProject>> result = new HashMap<>();
        byTurn.forEach((tid, m) -> result.put(tid, new ArrayList<>(m.values())));
        return result;
    }

    private static MemoryTurnVO toVO(MemoryTurn t, Map<Long, MemoryTag> tagMap,
                                     Map<Long, List<MemoryTurnVO.IndexedProject>> indexMap) {
        MemoryTurnVO vo = new MemoryTurnVO();
        vo.setId(t.getId());
        vo.setSessionId(t.getSessionId());
        vo.setDirection(t.getDirection());
        vo.setTagIds(t.getTagIds());
        if (t.getTagIds() != null) {
            vo.setTagLabels(t.getTagIds().stream()
                    .map(id -> {
                        MemoryTag tag = tagMap.get(id);
                        return tag == null ? null : tag.getLabel();
                    })
                    .toList());
        }
        vo.setL1Summary(t.getL1Summary());
        vo.setL2Detail(t.getL2Detail());
        vo.setRawContent(t.getRawContent());
        vo.setGenDone(t.getGenDone());
        vo.setCreatedAt(t.getCreatedAt());
        vo.setIndexedProjects(indexMap.get(t.getId()));
        return vo;
    }
}
