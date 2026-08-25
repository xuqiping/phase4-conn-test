package com.superprogrammer.projectgroup.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.chat.entity.MemoryNotification;
import com.superprogrammer.chat.mapper.MemoryNotificationMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.projectgroup.dto.ProjectGroupInviteVO;
import com.superprogrammer.projectgroup.entity.ProjectGroupEntity;
import com.superprogrammer.projectgroup.entity.ProjectGroupInviteEntity;
import com.superprogrammer.projectgroup.mapper.ProjectGroupInviteMapper;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMapper;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 组邀请状态机（17x#3，V138）：加成员从「组长直写」改「邀请 → 被邀请人同意」。
 * <ul>
 *   <li>邀请（组长/admin）：已是成员 → 409；PENDING 存在 → 409（部分唯一索引兜底）；
 *       DECLINED/CANCELED/ACCEPTED 旧行 → 同行复活 PENDING（条件 UPDATE）；否则插新行。
 *       通知被邀请人 GROUP_INVITE。</li>
 *   <li>接受（被邀请人本人）：PENDING→ACCEPTED 条件翻转 + 落成员行（quota 取邀请快照）；
 *       并发已入组（如公共池审批先到）→ 成员行已存在则跳过插入。通知组长 GROUP_INVITE_RESULT。</li>
 *   <li>拒绝（被邀请人本人）：PENDING→DECLINED。通知组长。</li>
 *   <li>取消（组长/admin）：PENDING→CANCELED。</li>
 * </ul>
 * 状态翻转全走条件 UPDATE（WHERE status='PENDING'），影响行数=0 → 409（并发打不穿，grant 先例）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectGroupInviteService {

    public static final String NOTIFY_TYPE_GROUP_INVITE = "GROUP_INVITE";
    public static final String NOTIFY_TYPE_GROUP_INVITE_RESULT = "GROUP_INVITE_RESULT";

    private final ProjectGroupInviteMapper inviteMapper;
    private final ProjectGroupMapper groupMapper;
    private final ProjectGroupMemberMapper memberMapper;
    private final ProjectGroupService groupService;
    private final UserMapper userMapper;
    private final MemoryNotificationMapper notificationMapper;
    private final MemberBudgetService budgetService;

    /** 发起邀请（组长/管理/admin，V139 放宽 MANAGER）。 */
    @Transactional(rollbackFor = Exception.class)
    public void invite(Long groupId, Long actorUserId, boolean admin, Long inviteeUserId, BigDecimal quotaLimitPoints) {
        ProjectGroupEntity g = groupService.requireRole(groupId, actorUserId, admin,
                com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity.ROLE_MANAGER);
        if (inviteeUserId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "被邀请人不能为空");
        }
        if (quotaLimitPoints != null && quotaLimitPoints.signum() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "成员限额不能为负");
        }
        if (g.getOwnerUserId().equals(inviteeUserId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "组长本就在组内，无需邀请");
        }
        if (userMapper.selectById(inviteeUserId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        if (memberMapper.selectByGroupUser(groupId, inviteeUserId) != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "该用户已是组成员");
        }
        // V156 层级额度预检（体验层，真防线在 accept→insertMemberRow 锁管理行硬卡）：
        // 邀请人是被限额管理 → 邀请限额必填且 ≤ 当前可分配；管理自己不限额则任意。
        boolean ownerSide = admin || g.getOwnerUserId().equals(actorUserId);
        if (!ownerSide) {
            com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity mgr =
                    memberMapper.selectByGroupUser(groupId, actorUserId);
            if (mgr != null && mgr.getQuotaLimitPoints() != null) {
                if (quotaLimitPoints == null) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST,
                            "你的额度为 " + mgr.getQuotaLimitPoints() + "（有限），邀请成员须填限额，不能不限");
                }
                java.math.BigDecimal available = budgetService.allocatable(groupId, mgr, null);
                if (available != null && quotaLimitPoints.compareTo(available) > 0) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST,
                            "超出你的可分配额度：剩余可分配 " + available + "，本次邀请需 " + quotaLimitPoints);
                }
            }
        }

        ProjectGroupInviteEntity existing = findRow(groupId, inviteeUserId);
        if (existing != null) {
            if (ProjectGroupInviteEntity.STATUS_PENDING.equals(existing.getStatus())) {
                throw new BusinessException(ErrorCode.CONFLICT, "已有待同意的邀请，勿重复发送");
            }
            revive(existing, actorUserId, quotaLimitPoints);
        } else {
            ProjectGroupInviteEntity inv = new ProjectGroupInviteEntity();
            inv.setGroupId(groupId);
            inv.setInviterUserId(actorUserId);
            inv.setInviteeUserId(inviteeUserId);
            inv.setQuotaLimitPoints(quotaLimitPoints);
            inv.setStatus(ProjectGroupInviteEntity.STATUS_PENDING);
            try {
                inviteMapper.insert(inv);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                throw new BusinessException(ErrorCode.CONFLICT, "已有待同意的邀请，勿重复发送");
            }
            existing = inv;
        }
        log.info("组邀请 groupId={} invitee={} inviter={} quota={}", groupId, inviteeUserId, actorUserId, quotaLimitPoints);
        insertNotification(inviteeUserId, NOTIFY_TYPE_GROUP_INVITE, existing.getId(),
                "「" + userName(actorUserId) + "」邀请你加入项目组「" + g.getName() + "」，请到 项目组→我的邀请 处理");
    }

    /** 我的待处理邀请（被邀请人视角）。 */
    public List<ProjectGroupInviteVO> listMinePending(Long userId) {
        List<ProjectGroupInviteEntity> rows = inviteMapper.selectList(new LambdaQueryWrapper<ProjectGroupInviteEntity>()
                .eq(ProjectGroupInviteEntity::getInviteeUserId, userId)
                .eq(ProjectGroupInviteEntity::getStatus, ProjectGroupInviteEntity.STATUS_PENDING)
                .orderByDesc(ProjectGroupInviteEntity::getId));
        return toVOs(rows);
    }

    /** 组邀请列表（组长/管理/admin 管理视角，全状态倒序）。 */
    public List<ProjectGroupInviteVO> listByGroup(Long groupId, Long actorUserId, boolean admin) {
        groupService.requireRole(groupId, actorUserId, admin,
                com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity.ROLE_MANAGER);
        List<ProjectGroupInviteEntity> rows = inviteMapper.selectList(new LambdaQueryWrapper<ProjectGroupInviteEntity>()
                .eq(ProjectGroupInviteEntity::getGroupId, groupId)
                .orderByDesc(ProjectGroupInviteEntity::getId));
        return toVOs(rows);
    }

    /** 接受邀请（被邀请人本人）：PENDING→ACCEPTED + 落成员行（已入组则幂等跳过）。
     *  V156：落行带 allocated_by=邀请人；邀请人是被限额管理 → insertMemberRow 内预算硬卡
     *  （同事务回滚翻转，邀请留 PENDING 可候管理额度宽裕后重试）。
     *  17x-1 次生洞收口：邀请 PENDING 期间邀请人被降职/移除（或本就是组外 admin 代发）→
     *  allocated_by 不得挂在已是 MEMBER/不存在的行下，统一改挂组长（组长侧无预算上限，硬卡天然跳过）。 */
    @Transactional(rollbackFor = Exception.class)
    public void accept(Long inviteId, Long userId) {
        ProjectGroupInviteEntity inv = requirePendingOf(inviteId, userId);
        transition(inv, ProjectGroupInviteEntity.STATUS_ACCEPTED);
        if (memberMapper.selectByGroupUser(inv.getGroupId(), userId) == null) {
            Long allocatedBy = resolveAllocatedBy(inv);
            groupService.insertMemberRow(inv.getGroupId(), userId, inv.getQuotaLimitPoints(), allocatedBy);
        }
        log.info("组邀请接受 inviteId={} groupId={} invitee={}", inviteId, inv.getGroupId(), userId);
        notifyInviter(inv, "「" + userName(userId) + "」已接受邀请，加入项目组「" + groupName(inv.getGroupId()) + "」");
    }

    /** 落行归属解析：邀请人仍是在任 MANAGER → 归其预算；否则（降职/移除/admin 代发）改挂组长。 */
    private Long resolveAllocatedBy(ProjectGroupInviteEntity inv) {
        Long inviterId = inv.getInviterUserId();
        ProjectGroupEntity g = groupMapper.selectById(inv.getGroupId());
        if (g != null && inviterId != null && inviterId.equals(g.getOwnerUserId())) {
            return inviterId;
        }
        com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity allocRow =
                inviterId == null ? null : memberMapper.selectByGroupUser(inv.getGroupId(), inviterId);
        if (allocRow != null && com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity.ROLE_MANAGER
                .equals(allocRow.getRole())) {
            return inviterId;
        }
        if (g == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目组不存在");
        }
        log.info("邀请人已非在任管理，落行改挂组长 groupId={} inviter={} owner={}",
                inv.getGroupId(), inviterId, g.getOwnerUserId());
        return g.getOwnerUserId();
    }

    /** 拒绝邀请（被邀请人本人）：PENDING→DECLINED。 */
    @Transactional(rollbackFor = Exception.class)
    public void decline(Long inviteId, Long userId) {
        ProjectGroupInviteEntity inv = requirePendingOf(inviteId, userId);
        transition(inv, ProjectGroupInviteEntity.STATUS_DECLINED);
        log.info("组邀请拒绝 inviteId={} groupId={} invitee={}", inviteId, inv.getGroupId(), userId);
        notifyInviter(inv, "「" + userName(userId) + "」拒绝了项目组「" + groupName(inv.getGroupId()) + "」的邀请");
    }

    /** 取消邀请（组长/管理/admin）：PENDING→CANCELED。 */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long inviteId, Long actorUserId, boolean admin) {
        ProjectGroupInviteEntity inv = inviteMapper.selectById(inviteId);
        if (inv == null || (inv.getDeleted() != null && inv.getDeleted() != 0)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "邀请不存在");
        }
        groupService.requireRole(inv.getGroupId(), actorUserId, admin,
                com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity.ROLE_MANAGER);
        transition(inv, ProjectGroupInviteEntity.STATUS_CANCELED);
        log.info("组邀请取消 inviteId={} groupId={} actor={}", inviteId, inv.getGroupId(), actorUserId);
    }

    // ============================ 内部 ============================

    private ProjectGroupInviteEntity findRow(Long groupId, Long inviteeUserId) {
        return inviteMapper.selectOne(new LambdaQueryWrapper<ProjectGroupInviteEntity>()
                .eq(ProjectGroupInviteEntity::getGroupId, groupId)
                .eq(ProjectGroupInviteEntity::getInviteeUserId, inviteeUserId)
                .orderByDesc(ProjectGroupInviteEntity::getId)
                .last("LIMIT 1"));
    }

    /** 旧行复活 PENDING（重置邀请人/限额/时钟；条件 UPDATE 防并发）。 */
    private void revive(ProjectGroupInviteEntity row, Long actorUserId, BigDecimal quotaLimitPoints) {
        int updated = inviteMapper.update(null, new LambdaUpdateWrapper<ProjectGroupInviteEntity>()
                .eq(ProjectGroupInviteEntity::getId, row.getId())
                .eq(ProjectGroupInviteEntity::getStatus, row.getStatus())
                .set(ProjectGroupInviteEntity::getStatus, ProjectGroupInviteEntity.STATUS_PENDING)
                .set(ProjectGroupInviteEntity::getInviterUserId, actorUserId)
                .set(ProjectGroupInviteEntity::getQuotaLimitPoints, quotaLimitPoints)
                .set(ProjectGroupInviteEntity::getDecidedAt, null));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "邀请状态已被并发变更，请刷新重试");
        }
        row.setStatus(ProjectGroupInviteEntity.STATUS_PENDING);
    }

    private ProjectGroupInviteEntity requirePendingOf(Long inviteId, Long userId) {
        ProjectGroupInviteEntity inv = inviteMapper.selectById(inviteId);
        if (inv == null || (inv.getDeleted() != null && inv.getDeleted() != 0)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "邀请不存在");
        }
        if (!inv.getInviteeUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅被邀请人本人可处理该邀请");
        }
        if (!ProjectGroupInviteEntity.STATUS_PENDING.equals(inv.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "邀请已处理，请刷新查看");
        }
        return inv;
    }

    /** PENDING→target 条件翻转（并发安全）；decided_at 留痕。 */
    private void transition(ProjectGroupInviteEntity inv, String target) {
        int updated = inviteMapper.update(null, new LambdaUpdateWrapper<ProjectGroupInviteEntity>()
                .eq(ProjectGroupInviteEntity::getId, inv.getId())
                .eq(ProjectGroupInviteEntity::getStatus, ProjectGroupInviteEntity.STATUS_PENDING)
                .set(ProjectGroupInviteEntity::getStatus, target)
                .set(ProjectGroupInviteEntity::getDecidedAt, OffsetDateTime.now()));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "邀请状态已被并发变更，请刷新重试");
        }
        inv.setStatus(target);
    }

    private void notifyInviter(ProjectGroupInviteEntity inv, String message) {
        insertNotification(inv.getInviterUserId(), NOTIFY_TYPE_GROUP_INVITE_RESULT, inv.getId(), message);
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

    private List<ProjectGroupInviteVO> toVOs(List<ProjectGroupInviteEntity> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, ProjectGroupEntity> groups = groupMapper.selectBatchIds(
                        rows.stream().map(ProjectGroupInviteEntity::getGroupId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(ProjectGroupEntity::getId, Function.identity(), (a, b) -> a));
        Map<Long, User> users = userMapper.selectBatchIds(
                        rows.stream().flatMap(r -> java.util.stream.Stream.of(r.getInviterUserId(), r.getInviteeUserId()))
                                .collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));
        return rows.stream().map(r -> {
            ProjectGroupEntity g = groups.get(r.getGroupId());
            User inviter = users.get(r.getInviterUserId());
            User invitee = users.get(r.getInviteeUserId());
            return new ProjectGroupInviteVO(
                    r.getId(), r.getGroupId(), g != null ? g.getName() : null,
                    r.getInviterUserId(), inviter != null ? displayName(inviter) : null,
                    r.getInviteeUserId(), invitee != null ? displayName(invitee) : null,
                    r.getQuotaLimitPoints(), r.getStatus(), r.getCreatedAt(), r.getDecidedAt());
        }).toList();
    }

    private String displayName(User u) {
        return (u.getName() != null && !u.getName().isBlank()) ? u.getName() : u.getUsername();
    }
}
