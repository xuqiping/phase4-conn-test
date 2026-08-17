package com.superprogrammer.projectgroup.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.projectgroup.entity.ProjectGroupEntity;
import com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity;
import com.superprogrammer.projectgroup.entity.ProjectGroupWalletEntity;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMapper;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMemberMapper;
import com.superprogrammer.projectgroup.mapper.ProjectGroupWalletMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 项目组管理服务（计划5 Step2）：建组/改名/删除/成员增删/限额/used 重置。
 * <p>权限模型：组长唯一管理权（requireOwner）——划拨回收在 {@link ProjectGroupWalletService}，
 * 这里管组织结构；Step3 的 Controller 再叠 @RequirePermission("project-group:manage")。
 * <p>删除=软删且组池必须 balance=0（先回收再删；wallet 表无软删列，组软删后孤儿行无害——
 * group_id 由 IDENTITY 发号不复用）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectGroupService {

    private final ProjectGroupMapper groupMapper;
    private final ProjectGroupMemberMapper memberMapper;
    private final ProjectGroupWalletMapper walletMapper;
    private final com.superprogrammer.projectgroup.mapper.ProjectGroupLedgerMapper ledgerMapper;
    private final UserMapper userMapper;

    /**
     * 建组：组行 + 组长成员行（quota NULL=不限）+ 组池 0 行，三写同事务。
     *
     * @return 组 id
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createGroup(Long ownerUserId, String name, String description) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "组名不能为空");
        }
        if (name.length() > 64) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "组名最长 64 字");
        }
        ProjectGroupEntity g = new ProjectGroupEntity();
        g.setName(name);
        g.setOwnerUserId(ownerUserId);
        g.setDescription(description);
        groupMapper.insert(g);

        ProjectGroupMemberEntity ownerRow = new ProjectGroupMemberEntity();
        ownerRow.setGroupId(g.getId());
        ownerRow.setUserId(ownerUserId);
        ownerRow.setQuotaLimitPoints(null);
        memberMapper.insert(ownerRow);

        ProjectGroupWalletEntity w = new ProjectGroupWalletEntity();
        w.setGroupId(g.getId());
        w.setBalancePoints(BigDecimal.ZERO);
        walletMapper.insert(w);

        log.info("建组 groupId={} owner={} name={}", g.getId(), ownerUserId, name);
        return g.getId();
    }

    /** 改名（仅组长）。 */
    @Transactional(rollbackFor = Exception.class)
    public void rename(Long groupId, Long actorUserId, String name) {
        requireOwner(groupId, actorUserId);
        if (name == null || name.isBlank() || name.length() > 64) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "组名不能为空且最长 64 字");
        }
        ProjectGroupEntity g = groupMapper.selectById(groupId);
        g.setName(name);
        groupMapper.updateById(g);
    }

    /**
     * 删除（软删，仅组长）：组池 balance≠0 拒删（先回收）。
     * 成员行随软删（对账视角组已终局）；组流水 append-only 永留。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteGroup(Long groupId, Long actorUserId) {
        requireOwner(groupId, actorUserId);
        ProjectGroupWalletEntity w = walletMapper.selectByGroupId(groupId);
        if (w == null || w.getBalancePoints().compareTo(BigDecimal.ZERO) != 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "组池余额非 0，请先回收全部积分再删除");
        }
        memberMapper.delete(new LambdaUpdateWrapper<ProjectGroupMemberEntity>()
                .eq(ProjectGroupMemberEntity::getGroupId, groupId));   // @TableLogic → UPDATE deleted=1
        groupMapper.deleteById(groupId);
        log.info("删组(软) groupId={} owner={}", groupId, actorUserId);
    }

    /** 加成员（仅组长）：quota null=不限。用户须存在；重复入组 CONFLICT。 */
    @Transactional(rollbackFor = Exception.class)
    public void addMember(Long groupId, Long actorUserId, Long memberUserId, BigDecimal quotaLimitPoints) {
        requireOwner(groupId, actorUserId);
        if (memberUserId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "成员用户不能为空");
        }
        if (quotaLimitPoints != null && quotaLimitPoints.signum() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "成员限额不能为负");
        }
        if (userMapper.selectById(memberUserId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        if (memberMapper.selectByGroupUser(groupId, memberUserId) != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "该用户已是组成员");
        }
        ProjectGroupMemberEntity m = new ProjectGroupMemberEntity();
        m.setGroupId(groupId);
        m.setUserId(memberUserId);
        m.setQuotaLimitPoints(quotaLimitPoints);
        memberMapper.insert(m);
        log.info("加成员 groupId={} member={} quota={}", groupId, memberUserId, quotaLimitPoints);
    }

    /** 移除成员（仅组长）：组长自身不可移除；used>0 照移（历史流水留痕，对账看流水不看行）。 */
    @Transactional(rollbackFor = Exception.class)
    public void removeMember(Long groupId, Long actorUserId, Long memberUserId) {
        requireOwner(groupId, actorUserId);
        ProjectGroupEntity g = groupMapper.selectById(groupId);
        if (g.getOwnerUserId().equals(memberUserId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "组长不可移除，请先转让或删除组");
        }
        ProjectGroupMemberEntity m = requireMember(groupId, memberUserId);
        memberMapper.deleteById(m.getId());
        log.info("移除成员 groupId={} member={}", groupId, memberUserId);
    }

    /** 调整成员限额（仅组长）：null=改为不限；调低不追偿（V133 列注释口径），仅约束后续消耗。 */
    @Transactional(rollbackFor = Exception.class)
    public void updateQuota(Long groupId, Long actorUserId, Long memberUserId, BigDecimal quotaLimitPoints) {
        requireOwner(groupId, actorUserId);
        if (quotaLimitPoints != null && quotaLimitPoints.signum() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "成员限额不能为负");
        }
        ProjectGroupMemberEntity m = requireMember(groupId, memberUserId);
        m.setQuotaLimitPoints(quotaLimitPoints);
        memberMapper.updateById(m);
        log.info("调限额 groupId={} member={} quota={}", groupId, memberUserId, quotaLimitPoints);
    }

    /**
     * 重置成员 used（仅组长）：used→0 + 组流水 ADMIN_ADJUST（delta=0，balance_after=组池现值，
     * remark 记前后值留痕）。quota 不动。重置后若有迟到退款，GREATEST 落 0，
     * Σ(CONSUME−REFUND) 与 used 会偏差——罕见，对账黄灯人工核（V133 模板注）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void resetUsed(Long groupId, Long actorUserId, Long memberUserId) {
        requireOwner(groupId, actorUserId);
        ProjectGroupMemberEntity m = requireMember(groupId, memberUserId);
        BigDecimal before = m.getUsedPoints();
        m.setUsedPoints(BigDecimal.ZERO);
        memberMapper.updateById(m);

        ProjectGroupWalletEntity w = walletMapper.selectByGroupId(groupId);
        com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity l = new com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity();
        l.setGroupId(groupId);
        l.setActorUserId(actorUserId);
        l.setType(com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity.TYPE_ADMIN_ADJUST);
        l.setDeltaPoints(BigDecimal.ZERO);
        l.setBalanceAfter(w != null ? w.getBalancePoints() : BigDecimal.ZERO);
        l.setRefType(com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity.REF_ADMIN);
        l.setRefId(String.valueOf(memberUserId));
        l.setRemark("重置成员已用: " + before + "→0");
        ledgerMapper.insert(l);
        log.info("重置used groupId={} member={} before={}", groupId, memberUserId, before);
    }

    // ==================== 内部 ====================

    private ProjectGroupMemberEntity requireMember(Long groupId, Long userId) {
        ProjectGroupMemberEntity m = memberMapper.selectByGroupUser(groupId, userId);
        if (m == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该用户不是组成员");
        }
        return m;
    }

    private void requireOwner(Long groupId, Long userId) {
        ProjectGroupEntity g = groupMapper.selectById(groupId);
        if (g == null || (g.getDeleted() != null && g.getDeleted() != 0)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目组不存在");
        }
        if (!g.getOwnerUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅组长可管理项目组");
        }
    }
}
