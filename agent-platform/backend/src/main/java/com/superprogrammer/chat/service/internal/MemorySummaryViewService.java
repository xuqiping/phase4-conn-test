package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.MemorySummaryVO;
import com.superprogrammer.chat.entity.MemorySummary;
import com.superprogrammer.chat.entity.MemoryTag;
import com.superprogrammer.chat.mapper.MemorySummaryMapper;
import com.superprogrammer.chat.mapper.MemoryTagMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 计划12 · F · 总结列表读取（总体设计 §3.4）。
 * <p>
 * 总结恒只读自己（{@code user_id}=当前用户，他人总结不可见防污染，向量 14 不受 ACL）。
 * 复用 {@link MemorySummaryMapper#findByUserAndScope}（E-3 已建），batch 回填 tag 信息防 N+1。
 *
 * @see MemorySummaryMapper 总结 mapper
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemorySummaryViewService {

    private final MemorySummaryMapper summaryMapper;
    private final MemoryTagMapper tagMapper;

    /**
     * 列当前用户的总结（按 scope）。
     *
     * @param userId    当前用户（恒只读自己）
     * @param projectId null = 个人 scope；非空 = 项目 scope
     */
    public List<MemorySummaryVO> listMySummaries(Long userId, Long projectId) {
        List<MemorySummary> summaries = summaryMapper.findByUserAndScope(userId, projectId);

        // batch 回填 tag 信息防 N+1
        List<Long> tagIds = summaries.stream().map(MemorySummary::getTagId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, MemoryTag> tagMap = new HashMap<>();
        if (!tagIds.isEmpty()) {
            for (MemoryTag t : tagMapper.selectBatchIds(tagIds)) {
                tagMap.put(t.getId(), t);
            }
        }

        return summaries.stream().map(s -> toVO(s, tagMap)).toList();
    }

    private static MemorySummaryVO toVO(MemorySummary s, Map<Long, MemoryTag> tagMap) {
        MemorySummaryVO vo = new MemorySummaryVO();
        vo.setId(s.getId());
        vo.setProjectId(s.getProjectId());
        vo.setTagId(s.getTagId());
        MemoryTag tag = s.getTagId() == null ? null : tagMap.get(s.getTagId());
        if (tag != null) {
            vo.setSubject(tag.getSubject());
            vo.setTopic(tag.getTopic());
            vo.setTagLabel(tag.getLabel());
        }
        vo.setL1Summary(s.getL1Summary());
        vo.setL2Detail(s.getL2Detail());
        vo.setSourceSummaryId(s.getSourceSummaryId());
        vo.setSourceTurnIds(s.getSourceTurnIds());
        vo.setStatus(s.getStatus());
        vo.setSummarizedAt(s.getSummarizedAt());
        vo.setCreatedAt(s.getCreatedAt());
        vo.setUpdatedAt(s.getUpdatedAt());
        return vo;
    }
}
