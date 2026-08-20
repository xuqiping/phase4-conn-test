package com.superprogrammer.projectgroup.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.billing.entity.LlmUsageLogEntity;
import com.superprogrammer.billing.mapper.LlmUsageLogMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.result.PageResult;
import com.superprogrammer.media.entity.MediaGenTask;
import com.superprogrammer.media.mapper.MediaGenTaskMapper;
import com.superprogrammer.projectgroup.dto.ProjectGroupDetailVO;
import com.superprogrammer.projectgroup.dto.ProjectGroupLedgerRowVO;
import com.superprogrammer.projectgroup.dto.ProjectGroupOverviewVO;
import com.superprogrammer.projectgroup.dto.ProjectGroupOutputVO;
import com.superprogrammer.projectgroup.entity.ProjectGroupEntity;
import com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity;
import com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity;
import com.superprogrammer.projectgroup.mapper.ProjectGroupLedgerMapper;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMapper;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 项目组推进查询服务（计划5 Step7）：组长总览（组详情+组池流水分页）与组产出列表。
 *
 * <p>可见性（拍板边界：不做成员报表）：
 * <ul>
 *   <li>组长/admin：overview 全量 + outputs 可看全部成员行、可按 memberUserId 筛选。</li>
 *   <li>普通成员：overview 403（管理页组长专属）；outputs 仅自己的行（忽略传入筛选强制 self）。</li>
 *   <li>非成员：两接口均 403。</li>
 * </ul>
 *
 * <p>数据口径：outputs 以 llm_usage_logs.project_group_id 为源（组维度消耗真源，
 * CONSUME/BACKSTOP 均有行）；媒体概要批查 media_gen_tasks 补齐（避免 SQL JOIN 大宽行）。
 * 产物文件不透出——媒体下载端点归属门控保持不变，本人行前端可跳任务详情预览。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectGroupQueryService {

    private static final int MAX_PAGE_SIZE = 50;

    private final ProjectGroupMapper groupMapper;
    private final ProjectGroupMemberMapper memberMapper;
    private final ProjectGroupLedgerMapper ledgerMapper;
    private final LlmUsageLogMapper usageLogMapper;
    private final MediaGenTaskMapper mediaTaskMapper;
    private final UserMapper userMapper;
    private final ProjectGroupService groupService;
    private final ProjectGroupVisibilityService visibilityService;

    /**
     * 组长总览：组详情（requireOwner 复用）+ 组池流水倒序分页（actor 用户名批量补齐）。
     */
    public ProjectGroupOverviewVO overview(Long groupId, Long actorUserId, boolean admin, int page, int size) {
        ProjectGroupDetailVO detail = groupService.getDetail(groupId, actorUserId, admin);

        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Page<ProjectGroupLedgerEntity> p = ledgerMapper.selectPage(
                new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<ProjectGroupLedgerEntity>()
                        .eq(ProjectGroupLedgerEntity::getGroupId, groupId)
                        .orderByDesc(ProjectGroupLedgerEntity::getId));
        List<ProjectGroupLedgerEntity> rows = p.getRecords();

        Map<Long, String> names = usernameMap(rows.stream()
                .map(ProjectGroupLedgerEntity::getActorUserId)
                .filter(Objects::nonNull)
                .toList());

        List<ProjectGroupLedgerRowVO> vos = rows.stream()
                .map(l -> new ProjectGroupLedgerRowVO(
                        l.getId(), l.getCreatedAt(), l.getActorUserId(),
                        l.getActorUserId() != null ? names.get(l.getActorUserId()) : null,
                        l.getType(), l.getDeltaPoints(), l.getBalanceAfter(),
                        l.getRefType(), l.getRefId(), l.getRemark()))
                .toList();
        return new ProjectGroupOverviewVO(detail,
                PageResult.of(vos, p.getTotal(), safePage, safeSize));
    }

    /**
     * 组产出列表：usage_log 组维度行倒序分页，媒体行附任务概要。
     * 成员视角强制 self 过滤（忽略 memberUserId 参数）；组长/admin 可筛任意成员。
     *
     * @param kind    可选：CHAT/EMBED/RERANK/IMAGE/VIDEO
     * @param from/to 可选时间范围（含 from 含 to）
     */
    public PageResult<ProjectGroupOutputVO> outputs(Long groupId, Long actorUserId, boolean admin,
                                                    Long memberUserId, String kind,
                                                    OffsetDateTime from, OffsetDateTime to,
                                                    int page, int size) {
        ProjectGroupEntity g = groupMapper.selectById(groupId);
        if (g == null || (g.getDeleted() != null && g.getDeleted() != 0)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目组不存在");
        }
        boolean owner = admin || g.getOwnerUserId().equals(actorUserId);
        boolean member = memberMapper.selectByGroupUser(groupId, actorUserId) != null;
        if (!owner && !member) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "非本项目组成员");
        }

        // 17x#2 可见性（V138）：组长/admin 全量可筛；成员视角 = 自己的行 ∪ 「组设置全组互见模块」的全员行
        // （V133 旧口径「成员恒仅自己」→ 现由 member_output_visibility/module_visibility_overrides 决定）
        final Long effectiveUser;
        final List<String> memberVisibleAllKinds;
        if (owner) {
            effectiveUser = memberUserId;
            memberVisibleAllKinds = List.of();
        } else {
            List<String> visibleKinds = visibilityService.visibleAllKindsForMember(g);
            memberVisibleAllKinds = visibleKinds;
            effectiveUser = visibleKinds.isEmpty() ? actorUserId : null;
        }

        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        LambdaQueryWrapper<LlmUsageLogEntity> qw = new LambdaQueryWrapper<LlmUsageLogEntity>()
                .eq(LlmUsageLogEntity::getProjectGroupId, groupId)
                .orderByDesc(LlmUsageLogEntity::getId);
        if (effectiveUser != null) {
            qw.eq(LlmUsageLogEntity::getUserId, effectiveUser);
        }
        if (!memberVisibleAllKinds.isEmpty()) {
            qw.and(w -> w.eq(LlmUsageLogEntity::getUserId, actorUserId)
                    .or().in(LlmUsageLogEntity::getKind, memberVisibleAllKinds));
        }
        if (kind != null && !kind.isBlank()) {
            qw.eq(LlmUsageLogEntity::getKind, kind);
        }
        if (from != null) {
            qw.ge(LlmUsageLogEntity::getCreatedAt, from);
        }
        if (to != null) {
            qw.le(LlmUsageLogEntity::getCreatedAt, to);
        }
        Page<LlmUsageLogEntity> p = usageLogMapper.selectPage(new Page<>(safePage, safeSize), qw);
        List<LlmUsageLogEntity> rows = p.getRecords();

        Map<Long, String> names = usernameMap(rows.stream()
                .map(LlmUsageLogEntity::getUserId)
                .filter(Objects::nonNull)
                .toList());
        Map<Long, MediaGenTask> tasks = mediaTaskMap(rows.stream()
                .map(LlmUsageLogEntity::getTaskId)
                .filter(Objects::nonNull)
                .toList());

        List<ProjectGroupOutputVO> vos = rows.stream()
                .map(u -> {
                    MediaGenTask t = u.getTaskId() != null ? tasks.get(u.getTaskId()) : null;
                    // 17x#1（V138）：产物文件引用仅对「按组可见性可见该行」的请求者透出
                    boolean canSeeFiles = t != null
                            && visibilityService.canSeeOutput(g, actorUserId, admin, u.getKind(), u.getUserId());
                    return new ProjectGroupOutputVO(
                            u.getId(), u.getCreatedAt(), u.getUserId(),
                            u.getUserId() != null ? names.get(u.getUserId()) : null,
                            u.getKind(), u.getModel(), u.getPointsConsumed(), u.getStatus(),
                            u.getTaskId(),
                            t != null ? t.getStatus() : null,
                            t != null && t.getRequestConfig() != null
                                    ? truncate(extractPrompt(t.getRequestConfig())) : null,
                            canSeeFiles ? t.getResultFileId() : null,
                            canSeeFiles ? extractImageFileIds(t.getResultMeta()) : null);
                })
                .toList();
        return PageResult.of(vos, p.getTotal(), safePage, safeSize);
    }

    // ==================== 内部 ====================

    /** 批量取用户名（users 已删/缺失返缺项，前端显「#uid」兜底）。 */
    private Map<Long, String> usernameMap(List<Long> ids) {
        Set<Long> distinct = ids.stream().collect(Collectors.toSet());
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(distinct).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));
    }

    /** 批量取媒体任务（usage.taskId → 概要）。 */
    private Map<Long, MediaGenTask> mediaTaskMap(List<Long> taskIds) {
        Set<Long> distinct = taskIds.stream().collect(Collectors.toSet());
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return mediaTaskMapper.selectBatchIds(distinct).stream()
                .collect(Collectors.toMap(MediaGenTask::getId, Function.identity(), (a, b) -> a));
    }

    /** requestConfig JSONB 里的 prompt 字段粗提取（无 Jackson 依赖的容错取法；失败返空串）。 */
    private String extractPrompt(String requestConfigJson) {
        if (requestConfigJson == null) {
            return "";
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(requestConfigJson);
            String prompt = root.path("prompt").asText("");
            return prompt == null ? "" : prompt;
        } catch (Exception e) {
            return "";
        }
    }

    /** resultMeta JSONB 的 imageFileIds[] 提取（图片任务；视频/解析失败 → null）。 */
    private List<String> extractImageFileIds(String resultMetaJson) {
        if (resultMetaJson == null) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode arr =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(resultMetaJson).path("imageFileIds");
            if (!arr.isArray() || arr.isEmpty()) {
                return null;
            }
            List<String> ids = new java.util.ArrayList<>();
            arr.forEach(n -> ids.add(n.asText()));
            return ids;
        } catch (Exception e) {
            return null;
        }
    }

    /** 摘要截断（列表展示口径，防宽行）。 */
    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 60 ? s : s.substring(0, 60) + "…";
    }
}
