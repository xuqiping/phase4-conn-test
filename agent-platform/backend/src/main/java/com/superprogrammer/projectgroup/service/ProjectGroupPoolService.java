package com.superprogrammer.projectgroup.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.chat.entity.MemoryNotification;
import com.superprogrammer.chat.mapper.MemoryNotificationMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.projectgroup.dto.ProjectGroupJoinRequestVO;
import com.superprogrammer.projectgroup.dto.ProjectGroupPoolItemVO;
import com.superprogrammer.projectgroup.entity.ProjectGroupEntity;
import com.superprogrammer.projectgroup.entity.ProjectGroupJoinRequestEntity;
import com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity;
import com.superprogrammer.projectgroup.mapper.ProjectGroupJoinRequestMapper;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMapper;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 公共池招募（17x#4，V138）：组长把组推入公共池 → 全平台可见、可申请加入；
 * 组长审批通过落成员行；人够了/不想招了随时撤池（级联 PENDING→REVOKED）。
 * <p>状态机镜像资产公众池（AssetPublicAccessService）+ 记忆授权（MemoryProjectUserGrantService）：
 * 同组同人仅一条 PENDING（部分唯一索引）；REJECTED 30 天防刷；REJECTED 超期/REVOKED 再申 = 同行复活；
 * 状态翻转全走条件 UPDATE，影响行数=0 → 409。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectGroupPoolService {

    /** REJECTED 防刷窗口（对齐 grant 先例）。 */
    static final int REJECT_COOLDOWN_DAYS = 30;

    public static final String NOTIFY_TYPE_GROUP_JOIN_REQUEST = "GROUP_JOIN_REQUEST";
    public static final String NOTIFY_TYPE_GROUP_JOIN_RESULT = "GROUP_JOIN_RESULT";

    private final ProjectGroupMapper groupMapper;
    private final ProjectGroupMemberMapper memberMapper;
    private final ProjectGroupJoinRequestMapper requestMapper;
    private final ProjectGroupService groupService;
    private final UserMapper userMapper;
    private final MemoryNotificationMapper notificationMapper;

    /** 推入公共池（组长/admin）：重复推入幂等（刷新 published_by/at）。 */
    @Transactional(rollbackFor = Exception.class)
    public void publish(Long groupId, Long actorUserId, boolean admin) {
        ProjectGroupEntity g = groupService.requireOwner(groupId, actorUserId, admin);
        g.setPublicPool(true);
        g.setPublicPublishedBy(actorUserId);
        g.setPublicPublishedAt(OffsetDateTime.now());
        groupMapper.updateById(g);
        log.info("组推入公共池 groupId={} actor={}", groupId, actorUserId);
    }

    /** 撤出公共池（组长/admin）：级联 PENDING 申请 → REVOKED（资产 unpublish 先例）。 */
    @Transactional(rollbackFor = Exception.class)
    public void unpublish(Long groupId, Long actorUserId, boolean admin) {
        groupService.requireOwner(groupId, actorUserId, admin);
        int updated = groupMapper.update(null, new LambdaUpdateWrapper<ProjectGroupEntity>()
                .eq(ProjectGroupEntity::getId, groupId)
                .set(ProjectGroupEntity::getPublicPool, false));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "组状态已被并发变更，请刷新重试");
        }
        List<ProjectGroupJoinRequestEntity> pendings = requestMapper.selectList(
                new LambdaQueryWrapper<ProjectGroupJoinRequestEntity>()
                        .eq(ProjectGroupJoinRequestEntity::getGroupId, groupId)
                        .eq(ProjectGroupJoinRequestEntity::getStatus, ProjectGroupJoinRequestEntity.STATUS_PENDING));
        for (ProjectGroupJoinRequestEntity r : pendings) {
            int flipped = requestMapper.update(null, new LambdaUpdateWrapper<ProjectGroupJoinRequestEntity>()
                    .eq(ProjectGroupJoinRequestEntity::getId, r.getId())
                    .eq(ProjectGroupJoinRequestEntity::getStatus, ProjectGroupJoinRequestEntity.STATUS_PENDING)
                    .set(ProjectGroupJoinRequestEntity::getStatus, ProjectGroupJoinRequestEntity.STATUS_REVOKED)
                    .set(ProjectGroupJoinRequestEntity::getDecidedAt, OffsetDateTime.now()));
            if (flipped > 0) {
                insertNotification(r.getUserId(), NOTIFY_TYPE_GROUP_JOIN_RESULT, r.getId(),
                        "项目组「" + groupName(groupId) + "」已撤出公共池，你的入组申请随之失效");
            }
        }
        log.info("组撤出公共池 groupId={} actor={} 级联失效申请={}", groupId, actorUserId, pendings.size());
    }

    /** 公共池列表（全平台可见）：招募中的组 + 成员数 + 我的身份/我的申请状态。 */
    public List<ProjectGroupPoolItemVO> listPublic(Long userId) {
        List<ProjectGroupEntity> groups = groupMapper.selectList(new LambdaQueryWrapper<ProjectGroupEntity>()
                .eq(ProjectGroupEntity::getPublicPool, true)
                .orderByDesc(ProjectGroupEntity::getPublicPublishedAt)
                .last("LIMIT 100"));
        if (groups.isEmpty()) {
            return List.of();
        }
        Map<Long, User> owners = userMapper.selectBatchIds(
                        groups.stream().map(ProjectGroupEntity::getOwnerUserId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));
        List<Long> gids = groups.stream().map(ProjectGroupEntity::getId).toList();
        // 我的成员身份（含组长行——组长建组自动成员行）
        Map<Long, Boolean> myMembership = memberMapper.selectList(new LambdaQueryWrapper<ProjectGroupMemberEntity>()
                        .in(ProjectGroupMemberEntity::getGroupId, gids)
                        .eq(ProjectGroupMemberEntity::getUserId, userId))
                .stream().collect(Collectors.toMap(ProjectGroupMemberEntity::getGroupId, m -> Boolean.TRUE, (a, b) -> a));
        // 我的申请（最新一条/组）
        Map<Long, ProjectGroupJoinRequestEntity> myLatest = requestMapper.selectList(
                        new LambdaQueryWrapper<ProjectGroupJoinRequestEntity>()
                                .in(ProjectGroupJoinRequestEntity::getGroupId, gids)
                                .eq(ProjectGroupJoinRequestEntity::getUserId, userId)
                                .orderByDesc(ProjectGroupJoinRequestEntity::getId))
                .stream().collect(Collectors.toMap(ProjectGroupJoinRequestEntity::getGroupId, Function.identity(), (a, b) -> a));
        return groups.stream().map(g -> {
            User owner = owners.get(g.getOwnerUserId());
            Long memberCount = memberMapper.selectCount(new LambdaQueryWrapper<ProjectGroupMemberEntity>()
                    .eq(ProjectGroupMemberEntity::getGroupId, g.getId()));
            ProjectGroupJoinRequestEntity mine = myLatest.get(g.getId());
            return new ProjectGroupPoolItemVO(
                    g.getId(), g.getName(), g.getDescription(),
                    owner != null ? owner.getUsername() : null,
                    memberCount, g.getPublicPublishedAt(),
                    myMembership.containsKey(g.getId()),
                    mine != null ? mine.getStatus() : null);
        }).toList();
    }

    /** 申请加入（本人）：PENDING + 通知组长。 */
    @Transactional(rollbackFor = Exception.class)
    public void apply(Long groupId, Long userId, String message) {
        ProjectGroupEntity g = groupMapper.selectById(groupId);
        if (g == null || (g.getDeleted() != null && g.getDeleted() != 0)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目组不存在");
        }
        if (!Boolean.TRUE.equals(g.getPublicPool())) {
            throw new BusinessException(ErrorCode.CONFLICT, "该组未在公共池招募");
        }
        if (g.getOwnerUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "你是组长，无需申请");
        }
        if (memberMapper.selectByGroupUser(groupId, userId) != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "你已是该组成员");
        }
        String safeMessage = message == null || message.isBlank() ? null : message.trim();

        ProjectGroupJoinRequestEntity existing = findRow(groupId, userId);
        if (existing != null) {
            switch (existing.getStatus()) {
                case ProjectGroupJoinRequestEntity.STATUS_PENDING ->
                        throw new BusinessException(ErrorCode.CONFLICT, "已有待审批的申请");
                case ProjectGroupJoinRequestEntity.STATUS_APPROVED ->
                        throw new BusinessException(ErrorCode.CONFLICT, "申请已通过，无需重复申请");
                case ProjectGroupJoinRequestEntity.STATUS_REJECTED -> {
                    if (existing.getCreatedAt() != null
                            && existing.getCreatedAt().plusDays(REJECT_COOLDOWN_DAYS).isAfter(OffsetDateTime.now())) {
                        throw new BusinessException(ErrorCode.CONFLICT,
                                "曾被拒绝，" + REJECT_COOLDOWN_DAYS + " 天内不可重复申请");
                    }
                    revive(existing, safeMessage);
                }
                case ProjectGroupJoinRequestEntity.STATUS_REVOKED -> revive(existing, safeMessage);
                default -> throw new BusinessException(ErrorCode.CONFLICT, "申请状态异常: " + existing.getStatus());
            }
        } else {
            ProjectGroupJoinRequestEntity r = new ProjectGroupJoinRequestEntity();
            r.setGroupId(groupId);
            r.setUserId(userId);
            r.setMessage(safeMessage);
            r.setStatus(ProjectGroupJoinRequestEntity.STATUS_PENDING);
            try {
                requestMapper.insert(r);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                throw new BusinessException(ErrorCode.CONFLICT, "已有待审批的申请");
            }
        }
        log.info("公共池入组申请 groupId={} applicant={}", groupId, userId);
        insertNotification(g.getOwnerUserId(), NOTIFY_TYPE_GROUP_JOIN_REQUEST, groupId,
                "「" + userName(userId) + "」申请加入项目组「" + g.getName() + "」，请到 项目组→入组审批 处理");
    }

    /** 组的申请列表（组长/admin 审批视角，PENDING 优先再按 id 倒序）。 */
    public List<ProjectGroupJoinRequestVO> listRequests(Long groupId, Long actorUserId, boolean admin) {
        groupService.requireOwner(groupId, actorUserId, admin);
        List<ProjectGroupJoinRequestEntity> rows = requestMapper.selectList(
                new LambdaQueryWrapper<ProjectGroupJoinRequestEntity>()
                        .eq(ProjectGroupJoinRequestEntity::getGroupId, groupId)
                        .orderByDesc(ProjectGroupJoinRequestEntity::getId));
        return toVOs(rows);
    }

    /** 我的申请（申请人视角，跨组）。 */
    public List<ProjectGroupJoinRequestVO> listMine(Long userId) {
        List<ProjectGroupJoinRequestEntity> rows = requestMapper.selectList(
                new LambdaQueryWrapper<ProjectGroupJoinRequestEntity>()
                        .eq(ProjectGroupJoinRequestEntity::getUserId, userId)
                        .orderByDesc(ProjectGroupJoinRequestEntity::getId));
        return toVOs(rows);
    }

    /** 审批（组长/admin）：approve=true→APPROVED+落成员行（已入组幂等跳过）；false→REJECTED（30 天防刷生效）。 */
    @Transactional(rollbackFor = Exception.class)
    public void decide(Long requestId, Long actorUserId, boolean admin, boolean approve) {
        ProjectGroupJoinRequestEntity r = requestMapper.selectById(requestId);
        if (r == null || (r.getDeleted() != null && r.getDeleted() != 0)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "申请不存在");
        }
        groupService.requireOwner(r.getGroupId(), actorUserId, admin);
        if (!ProjectGroupJoinRequestEntity.STATUS_PENDING.equals(r.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "申请已处理，请刷新查看");
        }
        String target = approve ? ProjectGroupJoinRequestEntity.STATUS_APPROVED : ProjectGroupJoinRequestEntity.STATUS_REJECTED;
        int updated = requestMapper.update(null, new LambdaUpdateWrapper<ProjectGroupJoinRequestEntity>()
                .eq(ProjectGroupJoinRequestEntity::getId, requestId)
                .eq(ProjectGroupJoinRequestEntity::getStatus, ProjectGroupJoinRequestEntity.STATUS_PENDING)
                .set(ProjectGroupJoinRequestEntity::getStatus, target)
                .set(ProjectGroupJoinRequestEntity::getDecidedBy, actorUserId)
                .set(ProjectGroupJoinRequestEntity::getDecidedAt, OffsetDateTime.now()));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "申请状态已被并发变更，请刷新重试");
        }
        if (approve && memberMapper.selectByGroupUser(r.getGroupId(), r.getUserId()) == null) {
            groupService.insertMemberRow(r.getGroupId(), r.getUserId(), null);
        }
        log.info("公共池申请审批 requestId={} groupId={} target={} actor={}", requestId, r.getGroupId(), target, actorUserId);
        insertNotification(r.getUserId(), NOTIFY_TYPE_GROUP_JOIN_RESULT, r.getId(),
                approve
                        ? "你加入项目组「" + groupName(r.getGroupId()) + "」的申请已通过"
                        : "你加入项目组「" + groupName(r.getGroupId()) + "」的申请被拒绝");
    }

    /** 取消我的待审批申请（申请人本人，PENDING 软删）。 */
    @Transactional(rollbackFor = Exception.class)
    public void cancelMine(Long requestId, Long userId) {
        ProjectGroupJoinRequestEntity r = requestMapper.selectById(requestId);
        if (r == null || (r.getDeleted() != null && r.getDeleted() != 0)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "申请不存在");
        }
        if (!r.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅申请人本人可取消");
        }
        if (!ProjectGroupJoinRequestEntity.STATUS_PENDING.equals(r.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "申请已处理，不可取消");
        }
        requestMapper.deleteById(requestId);
        log.info("公共池申请取消 requestId={} applicant={}", requestId, userId);
    }

    // ============================ 内部 ============================

    private ProjectGroupJoinRequestEntity findRow(Long groupId, Long userId) {
        return requestMapper.selectOne(new LambdaQueryWrapper<ProjectGroupJoinRequestEntity>()
                .eq(ProjectGroupJoinRequestEntity::getGroupId, groupId)
                .eq(ProjectGroupJoinRequestEntity::getUserId, userId)
                .orderByDesc(ProjectGroupJoinRequestEntity::getId)
                .last("LIMIT 1"));
    }

    /** 旧行复活 PENDING（重置留言/审批痕/时钟；条件 UPDATE 防并发）。 */
    private void revive(ProjectGroupJoinRequestEntity row, String message) {
        int updated = requestMapper.update(null, new LambdaUpdateWrapper<ProjectGroupJoinRequestEntity>()
                .eq(ProjectGroupJoinRequestEntity::getId, row.getId())
                .eq(ProjectGroupJoinRequestEntity::getStatus, row.getStatus())
                .set(ProjectGroupJoinRequestEntity::getStatus, ProjectGroupJoinRequestEntity.STATUS_PENDING)
                .set(ProjectGroupJoinRequestEntity::getMessage, message)
                .set(ProjectGroupJoinRequestEntity::getDecidedBy, null)
                .set(ProjectGroupJoinRequestEntity::getDecidedAt, null)
                .set(ProjectGroupJoinRequestEntity::getCreatedAt, OffsetDateTime.now()));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "申请状态已被并发变更，请刷新重试");
        }
    }

    private void insertNotification(Long userId, String type, Long refId, String message) {
        MemoryNotification n = new MemoryNotification();
        n.setUserId(userId);
        n.setType(type);
        n.setRefId(refId);
        n.setMessage(message);
        n.setCreatedAt(OffsetDateTime.now());
        notificationMapper.insert(n);
    }

    private String groupName(Long groupId) {
        ProjectGroupEntity g = groupMapper.selectById(groupId);
        return g == null ? "项目组#" + groupId : g.getName();
    }

    private String userName(Long userId) {
        User u = userMapper.selectById(userId);
        if (u == null) {
            return "用户#" + userId;
        }
        return (u.getName() != null && !u.getName().isBlank()) ? u.getName() : u.getUsername();
    }

    private List<ProjectGroupJoinRequestVO> toVOs(List<ProjectGroupJoinRequestEntity> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, ProjectGroupEntity> groups = groupMapper.selectBatchIds(
                        rows.stream().map(ProjectGroupJoinRequestEntity::getGroupId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(ProjectGroupEntity::getId, Function.identity(), (a, b) -> a));
        Map<Long, User> users = userMapper.selectBatchIds(
                        rows.stream().map(ProjectGroupJoinRequestEntity::getUserId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));
        return rows.stream().map(r -> {
            ProjectGroupEntity g = groups.get(r.getGroupId());
            User u = users.get(r.getUserId());
            return new ProjectGroupJoinRequestVO(
                    r.getId(), r.getGroupId(), g != null ? g.getName() : null,
                    r.getUserId(), u != null ? userName(u.getId()) : null,
                    r.getMessage(), r.getStatus(), r.getCreatedAt(), r.getDecidedAt());
        }).toList();
    }
}
