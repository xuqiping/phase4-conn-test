package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.chat.dto.MemoryTurnVO;
import com.superprogrammer.chat.entity.MemoryTag;
import com.superprogrammer.chat.entity.MemoryTurn;
import com.superprogrammer.chat.mapper.MemoryTagMapper;
import com.superprogrammer.chat.mapper.MemoryTurnMapper;
import com.superprogrammer.project.entity.Project;
import com.superprogrammer.project.mapper.ProjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 计划12 · F · 流水账列表读取（总体设计 §3.1）。
 * <p>
 * <b>仅本人流水账</b>（向量 7/13 ownership：{@code user_id}=当前用户）。batch 回填 tag label + 项目名防 N+1。
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
    private final ProjectMapper projectMapper;

    /** 列当前用户本人流水账（按时间倒序，最多 {@link #LIST_CAP}）。 */
    public List<MemoryTurnVO> listMyTurns(Long userId) {
        List<MemoryTurn> turns = turnMapper.selectList(new LambdaQueryWrapper<MemoryTurn>()
                .eq(MemoryTurn::getUserId, userId)
                .orderByDesc(MemoryTurn::getCreatedAt)
                .last("LIMIT " + LIST_CAP));

        Map<Long, MemoryTag> tagMap = batchTags(turns);
        Map<Long, Project> projectMap = batchProjects(turns);

        return turns.stream().map(t -> toVO(t, tagMap, projectMap)).toList();
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

    private Map<Long, Project> batchProjects(List<MemoryTurn> turns) {
        List<Long> pids = turns.stream().map(MemoryTurn::getProjectIds)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .distinct()
                .toList();
        Map<Long, Project> map = new HashMap<>();
        if (!pids.isEmpty()) {
            for (Project p : projectMapper.selectBatchIds(pids)) {
                map.put(p.getId(), p);
            }
        }
        return map;
    }

    private static MemoryTurnVO toVO(MemoryTurn t,
                                     Map<Long, MemoryTag> tagMap,
                                     Map<Long, Project> projectMap) {
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
        vo.setProjectIds(t.getProjectIds());
        if (t.getProjectIds() != null) {
            vo.setProjectNames(t.getProjectIds().stream()
                    .map(id -> {
                        Project p = projectMap.get(id);
                        return p == null ? null : p.getName();
                    })
                    .toList());
        }
        vo.setBornPersonal(t.getBornPersonal());
        vo.setCreatedAt(t.getCreatedAt());
        return vo;
    }
}
