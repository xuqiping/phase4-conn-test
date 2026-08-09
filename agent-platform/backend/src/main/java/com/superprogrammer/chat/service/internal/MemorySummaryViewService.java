package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.MemorySummaryVO;
import com.superprogrammer.chat.entity.MemorySummary;
import com.superprogrammer.chat.entity.MemoryTag;
import com.superprogrammer.chat.mapper.MemorySummaryMapper;
import com.superprogrammer.chat.mapper.MemoryTagMapper;
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
    private final MemoryProjectLinkService linkService;
    /** 第二轮 #4：放行 ACTIVE grant 用户读项目共享总结（只读）。 */
    private final MemoryProjectUserGrantService grantService;
    /** 第二轮 #3：回填总结所属项目名。 */
    private final ProjectMapper projectMapper;

    /**
     * 列当前用户的总结（按 scope）。
     *
     * @param userId    当前用户（恒只读自己）
     * @param projectId null = 个人 scope；非空 = 项目 scope
     */
    public List<MemorySummaryVO> listMySummaries(Long userId, Long projectId) {
        List<MemorySummary> summaries = summaryMapper.findByUserAndScope(userId, projectId);
        return toVOs(summaries);
    }

    /**
     * 列项目共享总结（二期 P4，FR-301）：scope_owner=PROJECT，全员可读。
     * <b>读权咽喉</b>：调用者须是该项目 ACTIVE 成员，<b>或持有该项目的 ACTIVE 召回授权</b>
     * （第二轮 #4：被授权人只读可见被分享的项目总结，不可写）。否则 FORBIDDEN（身份实时判）。
     */
    public List<MemorySummaryVO> listProjectSharedSummaries(Long userId, Long projectId) {
        if (projectId == null || !canReadProjectShared(projectId, userId)) {
            log.info("项目共享总结读取越权拦截 userId={} projectId={}", userId, projectId);
            throw new com.superprogrammer.common.exception.BusinessException(
                    com.superprogrammer.common.exception.ErrorCode.FORBIDDEN, "仅项目成员或被授权人可读项目共享总结");
        }
        return toVOs(summaryMapper.findProjectSharedSummaries(projectId));
    }

    /** 项目共享总结读权：ACTIVE 成员 或 ACTIVE grant 被授权人（第二轮 #4）。 */
    private boolean canReadProjectShared(Long projectId, Long userId) {
        if (linkService.isActiveMember(projectId, userId)) {
            return true;
        }
        return grantService.findActiveGrantedProjectIds(userId).contains(projectId);
    }

    private List<MemorySummaryVO> toVOs(List<MemorySummary> summaries) {

        // batch 回填 tag 信息防 N+1
        List<Long> tagIds = summaries.stream().map(MemorySummary::getTagId).filter(Objects::nonNull).distinct().toList();
        Map<Long, MemoryTag> tagMap = new HashMap<>();
        if (!tagIds.isEmpty()) {
            for (MemoryTag t : tagMapper.selectBatchIds(tagIds)) {
                tagMap.put(t.getId(), t);
            }
        }
        // 第二轮 #3：batch 回填所属项目名防 N+1
        List<Long> projectIds = summaries.stream().map(MemorySummary::getProjectId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> projectNameMap = new HashMap<>();
        if (!projectIds.isEmpty()) {
            for (Project p : projectMapper.selectBatchIds(projectIds)) {
                projectNameMap.put(p.getId(), p.getName());
            }
        }

        return summaries.stream().map(s -> toVO(s, tagMap, projectNameMap)).toList();
    }

    private static MemorySummaryVO toVO(MemorySummary s, Map<Long, MemoryTag> tagMap, Map<Long, String> projectNameMap) {
        MemorySummaryVO vo = new MemorySummaryVO();
        vo.setId(s.getId());
        vo.setProjectId(s.getProjectId());
        if (s.getProjectId() != null) {
            vo.setProjectName(projectNameMap.get(s.getProjectId()));
        }
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
        vo.setScopeOwner(s.getScopeOwner());
        vo.setDirection(s.getDirection());
        vo.setStatus(s.getStatus());
        vo.setSummarizedAt(s.getSummarizedAt());
        vo.setCreatedAt(s.getCreatedAt());
        vo.setUpdatedAt(s.getUpdatedAt());
        return vo;
    }
}
