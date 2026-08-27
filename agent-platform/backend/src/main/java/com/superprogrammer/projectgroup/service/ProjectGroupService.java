package com.superprogrammer.projectgroup.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.projectgroup.dto.ProjectGroupDetailVO;
import com.superprogrammer.projectgroup.dto.ProjectGroupMemberVO;
import com.superprogrammer.projectgroup.dto.ProjectGroupMineVO;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 项目组管理服务（计划5 Step2/Step3）：建组/改名/删除/成员增删/限额/used 重置 + mine 列表/详情/候选。
 * <p>权限双层：Controller {@code @RequirePermission("project-group:manage")}（平台码，V134）+
 * 本类 {@code requireOwner}（组长级；admin 越组长代管放行，审计在 Controller @AuditLog）。
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
    private final MemberBudgetService budgetService;
    private final ProjectGroupWalletService walletService;

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
        ownerRow.setRole(ProjectGroupMemberEntity.ROLE_OWNER);   // V139：建组即 OWNER（uk_pgm_owner 每组唯一）
        memberMapper.insert(ownerRow);

        ProjectGroupWalletEntity w = new ProjectGroupWalletEntity();
        w.setGroupId(g.getId());
        w.setBalancePoints(BigDecimal.ZERO);
        walletMapper.insert(w);

        log.info("建组 groupId={} owner={} name={}", g.getId(), ownerUserId, name);
        return g.getId();
    }

    /** 改名（组长/admin）。 */
    @Transactional(rollbackFor = Exception.class)
    public void rename(Long groupId, Long actorUserId, boolean admin, String name) {
        ProjectGroupEntity g = requireOwner(groupId, actorUserId, admin);
        if (name == null || name.isBlank() || name.length() > 64) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "组名不能为空且最长 64 字");
        }
        g.setName(name);
        groupMapper.updateById(g);
    }

    /**
     * 删除（软删，组长/admin）：组池 balance≠0 拒删（先回收）。
     * 成员行随软删（对账视角组已终局）；组流水 append-only 永留。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteGroup(Long groupId, Long actorUserId, boolean admin) {
        requireOwner(groupId, actorUserId, admin);
        ProjectGroupWalletEntity w = walletMapper.selectByGroupId(groupId);
        if (w == null || w.getBalancePoints().compareTo(BigDecimal.ZERO) != 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "组池余额非 0，请先回收全部积分再删除");
        }
        memberMapper.delete(new LambdaQueryWrapper<ProjectGroupMemberEntity>()
                .eq(ProjectGroupMemberEntity::getGroupId, groupId));   // @TableLogic → UPDATE deleted=1
        groupMapper.deleteById(groupId);
        log.info("删组(软) groupId={} actor={}", groupId, actorUserId);
    }

    /** 加成员（组长/admin）：quota null=不限。用户须存在；重复入组 CONFLICT。 */
    @Transactional(rollbackFor = Exception.class)
    public void addMember(Long groupId, Long actorUserId, boolean admin, Long memberUserId, BigDecimal quotaLimitPoints) {
        requireOwner(groupId, actorUserId, admin);
        insertMemberRow(groupId, memberUserId, quotaLimitPoints, actorUserId);
        log.info("加成员 groupId={} member={} quota={} actor={}", groupId, memberUserId, quotaLimitPoints, actorUserId);
    }

    /**
     * 落成员行（V138 抽公共；V139 复活两段式修 17x#1；V156 层级额度：allocatedBy + 管理预算硬卡）。
     * <p>两段式：①先条件 UPDATE 复活软删残留行（移除后再邀请/公共池再批准路径——
     * uk_pgm_group_user 是全量唯一，软删行仍占位，直接 INSERT 必撞 409）；
     * 复活命中即重置 quota/used=0/role=MEMBER/开关/覆盖/allocated_by，记 ADMIN_ADJUST 流水留痕后返回；
     * ②未命中走活行探针 + 新插。并发双接受：复活条件 UPDATE 互斥，恰一方成功。
     * 调用方自行完成授权判定（组长 requireOwner / 邀请接受=被邀请人本人 / 公共池审批=组长）。
     *
     * @param allocatedByUserId 额度分配人（V156）：邀请=邀请人；公共池=审批人；直加=操作人。
     *                          分配人是「被限额管理」且 quota 非空 → 预算硬卡（锁管理行，可分配不足 400）
     */
    public void insertMemberRow(Long groupId, Long memberUserId, BigDecimal quotaLimitPoints, Long allocatedByUserId) {
        if (memberUserId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "成员用户不能为空");
        }
        if (quotaLimitPoints != null && quotaLimitPoints.signum() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "成员限额不能为负");
        }
        if (userMapper.selectById(memberUserId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        // V156 预算硬卡：分配人是被限额管理 → 新成员预留（quota，used=0）须 ≤ 其可分配。
        // 锁管理行 FOR UPDATE 与 updateQuota/管理本人消耗互斥——并发双接受/边分边花打不穿。
        if (allocatedByUserId != null && quotaLimitPoints != null) {
            ProjectGroupMemberEntity mgr = memberMapper.selectByGroupUserForUpdate(groupId, allocatedByUserId);
            if (mgr != null && ProjectGroupMemberEntity.ROLE_MANAGER.equals(mgr.getRole())
                    && mgr.getQuotaLimitPoints() != null) {
                BigDecimal available = budgetService.allocatable(groupId, mgr, null);
                if (available != null && quotaLimitPoints.compareTo(available) > 0) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST,
                            "分配人（管理）可分配额度不足：剩余可分配 " + available + "，本次需要 " + quotaLimitPoints);
                }
            }
        }
        // ① 复活优先（软删残留行）
        if (memberMapper.reviveRow(groupId, memberUserId, quotaLimitPoints, allocatedByUserId) > 0) {
            ProjectGroupWalletEntity w = walletMapper.selectByGroupId(groupId);
            com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity l =
                    new com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity();
            l.setGroupId(groupId);
            l.setActorUserId(memberUserId);
            l.setType(com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity.TYPE_ADMIN_ADJUST);
            l.setDeltaPoints(BigDecimal.ZERO);
            l.setBalanceAfter(w != null ? w.getBalancePoints() : BigDecimal.ZERO);
            l.setRefType(com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity.REF_ADMIN);
            l.setRefId(String.valueOf(memberUserId));
            l.setRemark("成员回归复活：used 清零，限额/角色/功能开关/可见性覆盖重置默认");
            ledgerMapper.insert(l);
            recordMemberQuotaLedger(groupId, allocatedByUserId, memberUserId, null, quotaLimitPoints, "成员回归复活配额");
            log.info("成员复活 groupId={} member={} quota={}", groupId, memberUserId, quotaLimitPoints);
            return;
        }
        // ② 活行探针 + 新插
        if (memberMapper.selectByGroupUser(groupId, memberUserId) != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "该用户已是组成员");
        }
        ProjectGroupMemberEntity m = new ProjectGroupMemberEntity();
        m.setGroupId(groupId);
        m.setUserId(memberUserId);
        m.setQuotaLimitPoints(quotaLimitPoints);
        m.setRole(ProjectGroupMemberEntity.ROLE_MEMBER);
        m.setAllocatedByUserId(allocatedByUserId);
        memberMapper.insert(m);
        recordMemberQuotaLedger(groupId, allocatedByUserId, memberUserId, null, quotaLimitPoints, "成员配额落行");
    }

    /**
     * 移除成员（组长/管理/admin）：组长自身不可移除；MANAGER/OWNER 行不可被运营移除。
     * V161 修复III B3：软删前先退组结算——名下余额先还欠款（组长垫→组池垫），余款退本人个人钱包，
     * 覆盖不了的欠款 DEBT_WRITEOFF 核销留痕（人走账清）。used 照移（历史流水留痕）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void removeMember(Long groupId, Long actorUserId, boolean admin, Long memberUserId) {
        ProjectGroupEntity g = requireRole(groupId, actorUserId, admin, ProjectGroupMemberEntity.ROLE_MANAGER);
        if (g.getOwnerUserId().equals(memberUserId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "组长不可移除，请先删除组");
        }
        ProjectGroupMemberEntity m = requireOperatableMember(groupId, memberUserId);
        walletService.settleOnMemberRemoval(groupId, memberUserId);   // 同事务：锁序 组池→成员→个人，与 chargeGroup 同序
        memberMapper.deleteById(m.getId());
        log.info("移除成员（含退组结算）groupId={} member={} actor={}", groupId, memberUserId, actorUserId);
    }

    /**
     * 调整成员限额（V156 层级额度版，取代 V139 仅 MEMBER 行口径）：
     * <ul>
     *   <li><b>组长/admin</b>：目标 MEMBER 或 MANAGER 行。目标 MANAGER=给管理定预算：
     *       新额度非空时须 ≥ 已占用（子树已耗+下级预留）且管理下无「不限额」下级；
     *       目标 MEMBER：直接定并改挂组长（allocated_by=组长，离开原管理预算）。</li>
     *   <li><b>管理</b>：目标仅 MEMBER 行；自己有额度（非空）时新限额必填且新增预留 ≤ 自己可分配
     *       （管理行 FOR UPDATE 串行化，并发双分配打不穿）；allocated_by=自己。</li>
     * </ul>
     * 修复IV D2（17x-3，决策 2）：新限额 null 一律 400（不限冻结）——组内不再产生新的不限额行；
     * 存量 null（不限）成员行行为不变（消耗/降职/移除链不动）。
     * 调低不追溯已耗（V133 列注释口径），仅约束后续消耗。
     * <p>V161 修复III B2：调高限额 +X 时先抵欠款（豁免，无资金流动）——债清额回减 used，
     * 可用空间即时恢复；DEBT_WRITEOFF 腿留痕拆分（组长垫/组池垫）。old=null（原不限额）
     * 不可能有欠款（溢出仅 quota 有限时产生），天然跳过豁免。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateQuota(Long groupId, Long actorUserId, boolean admin, Long memberUserId, BigDecimal quotaLimitPoints) {
        ProjectGroupEntity g = requireRole(groupId, actorUserId, admin, ProjectGroupMemberEntity.ROLE_MANAGER);
        // 修复IV D2（17x-3，决策 2）：不限额度停用——调额必填数值；存量 null（不限）成员行不受影响
        if (quotaLimitPoints == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不限额度已停用，请填写具体额度（存量不限成员不受影响）");
        }
        if (quotaLimitPoints.signum() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "成员限额不能为负");
        }
        ProjectGroupMemberEntity target = requireMember(groupId, memberUserId);
        String targetRole = target.getRole() == null ? ProjectGroupMemberEntity.ROLE_MEMBER : target.getRole();
        if (ProjectGroupMemberEntity.ROLE_OWNER.equals(targetRole)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "组长行不可调限额（组长预算=组池本身）");
        }
        BigDecimal oldQuotaForExempt = target.getQuotaLimitPoints();
        if (quotaLimitPoints != null && oldQuotaForExempt != null
                && quotaLimitPoints.compareTo(oldQuotaForExempt) > 0) {
            exemptDebtOnQuotaRaise(groupId, actorUserId, memberUserId, oldQuotaForExempt, quotaLimitPoints);
            target = requireMember(groupId, memberUserId);  // used/版本已被豁免更新，重读防乐观锁静默失配
        }
        boolean ownerSide = admin || g.getOwnerUserId().equals(actorUserId);
        if (ownerSide) {
            if (ProjectGroupMemberEntity.ROLE_MANAGER.equals(targetRole)) {
                // 给管理定预算：锁管理行（与管理的分配操作互斥），下限=已占用
                ProjectGroupMemberEntity mgr = memberMapper.selectByGroupUserForUpdate(groupId, memberUserId);
                if (quotaLimitPoints != null) {
                    if (budgetService.hasUnboundedChild(groupId, memberUserId, null)) {
                        throw new BusinessException(ErrorCode.BAD_REQUEST,
                                "该管理下有限额为空的成员，请先把这些成员收编（改挂组长或补限额）再定额度");
                    }
                    BigDecimal occupied = budgetService.occupied(groupId, mgr, null);
                    if (quotaLimitPoints.compareTo(occupied) < 0) {
                        throw new BusinessException(ErrorCode.BAD_REQUEST,
                                "新额度低于该管理当前已占用 " + occupied + "（子树已耗+下级预留），请先下调其成员限额或重置已用");
                    }
                }
                BigDecimal mgrOldQuota = mgr.getQuotaLimitPoints();
                mgr.setQuotaLimitPoints(quotaLimitPoints);
                mgr.setAllocatedByUserId(g.getOwnerUserId());
                memberMapper.updateById(mgr);
                recordMemberQuotaLedger(groupId, actorUserId, memberUserId, mgrOldQuota, quotaLimitPoints, "组长调管理预算");
            } else {
                BigDecimal oldQuota = target.getQuotaLimitPoints();
                target.setQuotaLimitPoints(quotaLimitPoints);
                target.setAllocatedByUserId(g.getOwnerUserId());
                memberMapper.updateById(target);
                recordMemberQuotaLedger(groupId, actorUserId, memberUserId, oldQuota, quotaLimitPoints, "组长调成员限额");
            }
            log.info("调限额(组长侧) groupId={} member={} quota={} actor={}", groupId, memberUserId, quotaLimitPoints, actorUserId);
            return;
        }
        // 管理侧：目标仅 MEMBER 行；锁自己行算可分配
        if (!ProjectGroupMemberEntity.ROLE_MEMBER.equals(targetRole)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅可管理普通成员（MEMBER）");
        }
        ProjectGroupMemberEntity mgr = memberMapper.selectByGroupUserForUpdate(groupId, actorUserId);
        if (mgr == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "非本项目组成员");
        }
        if (mgr.getQuotaLimitPoints() != null && quotaLimitPoints == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "你的额度为 " + mgr.getQuotaLimitPoints() + "（有限），给成员配限额须填具体数值，不能不限");
        }
        if (quotaLimitPoints != null) {
            budgetService.requireWithinBudget(groupId, mgr, target, quotaLimitPoints);
        }
        BigDecimal oldQuota = target.getQuotaLimitPoints();
        target.setQuotaLimitPoints(quotaLimitPoints);
        target.setAllocatedByUserId(actorUserId);
        memberMapper.updateById(target);
        recordMemberQuotaLedger(groupId, actorUserId, memberUserId, oldQuota, quotaLimitPoints, "管理调成员限额");
        log.info("调限额(管理侧) groupId={} member={} quota={} actor={}", groupId, memberUserId, quotaLimitPoints, actorUserId);
    }

    /**
     * 重置成员 used（组长/管理/admin）：used→0 + 组流水 ADMIN_ADJUST（delta=0，balance_after=组池现值，
     * remark 记前后值留痕）。quota 不动。重置后若有迟到退款，GREATEST 落 0，
     * Σ(CONSUME−REFUND) 与 used 会偏差——罕见，对账黄灯人工核（V133 模板注）。
     * <p>V156：目标 MEMBER 行=组长/管理均可；目标 MANAGER 行=仅组长/admin（重置管理已用=释放其可分配）。
     * <p>V161 修复III B2：欠款一并清零（人工修脏数据口，运维考量·运维入口）——双欠款归 0 +
     * DEBT_WRITEOFF 腿留痕（组长垫/组池垫拆分），消费冻结随之解除。
     */
    @Transactional(rollbackFor = Exception.class)
    public void resetUsed(Long groupId, Long actorUserId, boolean admin, Long memberUserId) {
        ProjectGroupEntity g = requireRole(groupId, actorUserId, admin, ProjectGroupMemberEntity.ROLE_MANAGER);
        ProjectGroupMemberEntity m = requireMember(groupId, memberUserId);
        String targetRole = m.getRole() == null ? ProjectGroupMemberEntity.ROLE_MEMBER : m.getRole();
        if (ProjectGroupMemberEntity.ROLE_OWNER.equals(targetRole)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "组长行不可重置");
        }
        boolean ownerSide = admin || g.getOwnerUserId().equals(actorUserId);
        if (ProjectGroupMemberEntity.ROLE_MANAGER.equals(targetRole) && !ownerSide) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "管理行仅组长可重置已用");
        }
        BigDecimal before = m.getUsedPoints();
        BigDecimal dl = m.getDebtLeaderPoints() == null ? BigDecimal.ZERO : m.getDebtLeaderPoints();
        BigDecimal dp = m.getDebtPoolPoints() == null ? BigDecimal.ZERO : m.getDebtPoolPoints();
        BigDecimal debtTotal = dl.add(dp);
        m.setUsedPoints(BigDecimal.ZERO);
        if (debtTotal.signum() > 0) {
            m.setDebtLeaderPoints(BigDecimal.ZERO);
            m.setDebtPoolPoints(BigDecimal.ZERO);
        }
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
        l.setRemark("重置成员已用: " + before + "→0" + (debtTotal.signum() > 0
                ? " ·欠款清零（组长垫 " + dl + "/组池垫 " + dp + "）" : ""));
        ledgerMapper.insert(l);
        if (debtTotal.signum() > 0) {
            com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity wo = new com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity();
            wo.setGroupId(groupId);
            wo.setActorUserId(actorUserId);
            wo.setType(com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity.TYPE_DEBT_WRITEOFF);
            wo.setDeltaPoints(BigDecimal.ZERO);
            wo.setBalanceAfter(l.getBalanceAfter());
            wo.setRefType(com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity.REF_MEMBER);
            wo.setRefId(String.valueOf(memberUserId));
            wo.setRemark("重置清欠款（组长垫 " + dl + " / 组池垫 " + dp + "）");
            ledgerMapper.insert(wo);
            log.warn("重置清欠款 groupId={} member={}（组长垫 {}/组池垫 {}）actor={}",
                    groupId, memberUserId, dl, dp, actorUserId);
        }
        log.info("重置used groupId={} member={} before={} debt={} actor={}",
                groupId, memberUserId, before, debtTotal, actorUserId);
    }

    /**
     * 调高限额豁免欠款（V161 修复III B2，规格 §4.2）：+X 先抵欠款（组长垫→组池垫），豁免=无资金流动
     * ——组长调限额即给额度，不必真掏钱；债清额回减 used（不变量②：debt 减必伴 used 减），
     * 可用空间即时恢复且效果=直接涨 X。DEBT_WRITEOFF 腿（delta=0）留痕拆分。
     * 锁成员行 FOR UPDATE，与消耗/还款/划拨互斥。
     */
    private void exemptDebtOnQuotaRaise(Long groupId, Long actorUserId, Long memberUserId,
                                        BigDecimal oldQuota, BigDecimal newQuota) {
        ProjectGroupMemberEntity m = memberMapper.selectByGroupUserForUpdate(groupId, memberUserId);
        if (m == null) {
            return;
        }
        BigDecimal raise = newQuota.subtract(oldQuota);
        BigDecimal dl = m.getDebtLeaderPoints() == null ? BigDecimal.ZERO : m.getDebtLeaderPoints();
        BigDecimal dp = m.getDebtPoolPoints() == null ? BigDecimal.ZERO : m.getDebtPoolPoints();
        BigDecimal rl = dl.min(raise);
        BigDecimal rp = dp.min(raise.subtract(rl));
        if (rl.add(rp).signum() <= 0) {
            return;
        }
        if (rl.signum() > 0) {
            memberMapper.adjustDebtLeader(groupId, memberUserId, rl.negate());
        }
        if (rp.signum() > 0) {
            memberMapper.adjustDebtPool(groupId, memberUserId, rp.negate());
        }
        memberMapper.subtractUsed(groupId, memberUserId, rl.add(rp));
        ProjectGroupWalletEntity w = walletMapper.selectByGroupId(groupId);
        com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity l = new com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity();
        l.setGroupId(groupId);
        l.setActorUserId(actorUserId);
        l.setType(com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity.TYPE_DEBT_WRITEOFF);
        l.setDeltaPoints(BigDecimal.ZERO);
        l.setBalanceAfter(w != null ? w.getBalancePoints() : BigDecimal.ZERO);
        l.setRefType(com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity.REF_MEMBER);
        l.setRefId(String.valueOf(memberUserId));
        l.setRemark("限额 " + oldQuota + "→" + newQuota + " 调高额抵欠款（组长垫 " + rl + " / 组池垫 " + rp + "，豁免无资金流动）");
        ledgerMapper.insert(l);
        log.warn("调限额豁免欠款 groupId={} member={} raise={}（组长垫 {}/组池垫 {}）actor={}",
                groupId, memberUserId, raise, rl, rp, actorUserId);
    }

    // ==================== Step3：读侧 + 候选 ====================

    /**
     * 我的组列表（前端选择器数据源）：我建的 + 我在的，含各组余额/我的身份/我的限额与已用/成员数。
     */
    public List<ProjectGroupMineVO> listMine(Long userId) {
        List<ProjectGroupEntity> mine = groupMapper.selectList(new LambdaQueryWrapper<ProjectGroupEntity>()
                .eq(ProjectGroupEntity::getOwnerUserId, userId)
                .orderByDesc(ProjectGroupEntity::getId));
        List<ProjectGroupMemberEntity> myRows = memberMapper.selectList(new LambdaQueryWrapper<ProjectGroupMemberEntity>()
                .eq(ProjectGroupMemberEntity::getUserId, userId));
        Set<Long> memberGroupIds = myRows.stream()
                .map(ProjectGroupMemberEntity::getGroupId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> seen = new LinkedHashSet<>();
        List<ProjectGroupEntity> all = new ArrayList<>(mine);
        if (!memberGroupIds.isEmpty()) {
            groupMapper.selectList(new LambdaQueryWrapper<ProjectGroupEntity>()
                            .in(ProjectGroupEntity::getId, memberGroupIds))
                    .forEach(all::add);
        }
        Map<Long, ProjectGroupMemberEntity> myRowByGroup = myRows.stream()
                .collect(Collectors.toMap(ProjectGroupMemberEntity::getGroupId, Function.identity(), (a, b) -> a));

        List<ProjectGroupMineVO> result = new ArrayList<>();
        for (ProjectGroupEntity g : all) {
            if (g.getDeleted() != null && g.getDeleted() != 0) {
                continue;
            }
            if (!seen.add(g.getId())) {
                continue;
            }
            boolean owner = g.getOwnerUserId().equals(userId);
            ProjectGroupMemberEntity myRow = myRowByGroup.get(g.getId());
            ProjectGroupWalletEntity w = walletMapper.selectByGroupId(g.getId());
            Long memberCount = memberMapper.selectCount(new LambdaQueryWrapper<ProjectGroupMemberEntity>()
                    .eq(ProjectGroupMemberEntity::getGroupId, g.getId()));
            // V139：身份取成员行 role（OWNER/MANAGER/MEMBER）；组长恒 OWNER（兜底行缺失场景）
            String myRole = owner ? ProjectGroupMemberEntity.ROLE_OWNER
                    : (myRow != null && myRow.getRole() != null ? myRow.getRole() : ProjectGroupMemberEntity.ROLE_MEMBER);
            // V156：管理视角给「我可分配额度」（选择器徽标/组卡片展示；不限额→null）
            BigDecimal myAllocatable = ProjectGroupMemberEntity.ROLE_MANAGER.equals(myRole) && myRow != null
                    ? budgetService.allocatable(g.getId(), myRow, null) : null;
            result.add(new ProjectGroupMineVO(
                    g.getId(), g.getName(), g.getDescription(), g.getOwnerUserId(),
                    myRole,
                    w != null ? w.getBalancePoints() : BigDecimal.ZERO,
                    myRow != null ? myRow.getQuotaLimitPoints() : null,
                    myRow != null ? myRow.getUsedPoints() : BigDecimal.ZERO,
                    myRow != null && myRow.getSelfPoints() != null ? myRow.getSelfPoints() : BigDecimal.ZERO,
                    myRow != null && myRow.getDebtPoolPoints() != null ? myRow.getDebtPoolPoints() : BigDecimal.ZERO,
                    myRow != null && myRow.getDebtLeaderPoints() != null ? myRow.getDebtLeaderPoints() : BigDecimal.ZERO,
                    myAllocatable,
                    memberCount.intValue(), g.getCreatedAt()));
        }
        return result;
    }

    /**
     * 组详情：组基本信息 + 组池余额/在途占用 + 成员列表（含 username/role/used/quota）。
     * V139 放宽 MANAGER 可读（管理页成员/流水/审批 tab 数据源）；写操作仍按各自闸口。
     * 修复IV D3（17x-4，决策 5）：权限再放宽为「组内在册成员（MEMBER+）或组长/admin」；
     * MEMBER 视角单点裁剪——他人行的额度/欠款/可分配/分配人/功能开关/可见性覆盖置 null，
     * 本人行完整；组池余额与在途占用（组级财务）置 null。username/name/role/joinedAt/remark
     * 保留（组织信息可见）。鉴权与裁剪共用同一次成员行查询（不重复走 requireRole 两查）。
     */
    public ProjectGroupDetailVO getDetail(Long groupId, Long actorUserId, boolean admin) {
        ProjectGroupEntity g = groupMapper.selectById(groupId);
        if (g == null || (g.getDeleted() != null && g.getDeleted() != 0)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目组不存在");
        }
        ProjectGroupMemberEntity viewerRow = admin ? null : memberMapper.selectByGroupUser(groupId, actorUserId);
        boolean ownerView = admin || g.getOwnerUserId().equals(Objects.requireNonNull(actorUserId, "actorUserId"));
        if (!ownerView && viewerRow == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "非本项目组成员");
        }
        String viewerRole = viewerRow == null || viewerRow.getRole() == null
                ? ProjectGroupMemberEntity.ROLE_MEMBER : viewerRow.getRole();
        boolean memberView = !ownerView && ProjectGroupMemberEntity.ROLE_MEMBER.equals(viewerRole);

        ProjectGroupWalletEntity w = walletMapper.selectByGroupId(groupId);
        BigDecimal inflight = walletMapper.sumInflightEstimated(groupId);

        List<ProjectGroupMemberEntity> rows = memberMapper.selectList(new LambdaQueryWrapper<ProjectGroupMemberEntity>()
                .eq(ProjectGroupMemberEntity::getGroupId, groupId)
                .orderByAsc(ProjectGroupMemberEntity::getId));
        List<Long> userIds = rows.stream().map(ProjectGroupMemberEntity::getUserId).toList();
        Map<Long, User> users = userIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));

        final Long viewerId = actorUserId;
        List<ProjectGroupMemberVO> members = rows.stream().map(m -> {
            User u = users.get(m.getUserId());
            String role = m.getRole() == null ? ProjectGroupMemberEntity.ROLE_MEMBER : m.getRole();
            // MEMBER 视角他人行：额度类/配置类全裁（决策 5）
            boolean crop = memberView && !m.getUserId().equals(viewerId);
            // V156：管理行算可分配额度（额度−子树已耗−下级预留；不限额→null）；管理行数少，逐行算可接受
            BigDecimal allocatable = !crop && ProjectGroupMemberEntity.ROLE_MANAGER.equals(role)
                    ? budgetService.allocatable(groupId, m, null) : null;
            return new ProjectGroupMemberVO(
                    m.getUserId(),
                    u != null ? u.getUsername() : null,
                    u != null && u.getName() != null ? u.getName() : (u != null ? u.getUsername() : null),
                    u != null ? u.getRemark() : null,
                    m.getUserId().equals(g.getOwnerUserId()),
                    role,
                    crop ? null : MemberAllowedKinds.parse(m.getAllowedKinds()),
                    crop ? null : ProjectGroupVisibilityService.parseOverrides(m.getMemberVisibilityOverrides()),
                    crop ? null : m.getQuotaLimitPoints(),
                    crop ? null : m.getUsedPoints(),
                    crop ? null : (m.getSelfPoints() != null ? m.getSelfPoints() : BigDecimal.ZERO),
                    crop ? null : (m.getDebtPoolPoints() != null ? m.getDebtPoolPoints() : BigDecimal.ZERO),
                    crop ? null : (m.getDebtLeaderPoints() != null ? m.getDebtLeaderPoints() : BigDecimal.ZERO),
                    crop ? null : m.getAllocatedByUserId(),
                    allocatable,
                    m.getCreatedAt());
        }).toList();

        User owner = users.get(g.getOwnerUserId());
        return new ProjectGroupDetailVO(
                g.getId(), g.getName(), g.getDescription(),
                g.getOwnerUserId(), owner != null ? owner.getUsername() : null,
                memberView ? null : (w != null ? w.getBalancePoints() : BigDecimal.ZERO),
                memberView ? null : inflight,
                members, g.getCreatedAt(),
                g.getMemberOutputVisibility(), g.getModuleVisibilityOverrides(), g.getPublicPool());
    }

    /**
     * 候选用户搜索（复用资产库模式）：排除组长与已有成员；空关键词开箱载 50、输入收窄 20。
     * 修复IV A4（17x-2b）：权限从 requireOwner 放宽到 MANAGER——与发邀请口径一致
     * （原组长/admin 才能拉候选，管理打开邀请弹窗直接 403）；只放宽读候选，写权限不变。
     */
    public List<com.superprogrammer.projectgroup.dto.ProjectGroupCandidateVO> searchCandidates(
            Long groupId, Long actorUserId, boolean admin, String keyword) {
        ProjectGroupEntity g = requireRole(groupId, actorUserId, admin, ProjectGroupMemberEntity.ROLE_MANAGER);
        Set<Long> excluded = new LinkedHashSet<>();
        excluded.add(g.getOwnerUserId());
        memberMapper.selectList(new LambdaQueryWrapper<ProjectGroupMemberEntity>()
                        .eq(ProjectGroupMemberEntity::getGroupId, groupId))
                .stream().map(ProjectGroupMemberEntity::getUserId).forEach(excluded::add);
        String safeKeyword = keyword == null ? "" : keyword.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        int limit = safeKeyword.isEmpty() ? 50 : 20;
        return userMapper.searchActiveCandidates(safeKeyword, new ArrayList<>(excluded), limit)
                .stream().limit(limit)
                .map(u -> new com.superprogrammer.projectgroup.dto.ProjectGroupCandidateVO(
                        u.getId(), u.getUsername(), u.getName(), u.getRemark()))
                .toList();
    }

    /** 成员身份探针（Step4/5 计费链路用）：在组返成员行，不在返 null。 */
    public ProjectGroupMemberEntity findMember(Long groupId, Long userId) {
        if (groupId == null || userId == null) {
            return null;
        }
        ProjectGroupEntity g = groupMapper.selectById(groupId);
        if (g == null || (g.getDeleted() != null && g.getDeleted() != 0)) {
            return null;
        }
        return memberMapper.selectByGroupUser(groupId, userId);
    }

    // ==================== 内部 ====================

    /**
     * 任免组内角色（V139，仅组长/admin）：MEMBER↔MANAGER 互转。
     * OWNER 行不可动（GROUP_OWNER_IMMUTABLE 语义；uk_pgm_owner 兜底多 OWNER）。
     * 同角色重复任免幂等返回。
     */    @Transactional(rollbackFor = Exception.class)
    public void updateMemberRole(Long groupId, Long actorUserId, boolean admin, Long memberUserId, String role) {
        ProjectGroupEntity g = requireOwner(groupId, actorUserId, admin);
        if (!ProjectGroupMemberEntity.ROLE_MANAGER.equals(role) && !ProjectGroupMemberEntity.ROLE_MEMBER.equals(role)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "角色仅支持 MANAGER/MEMBER");
        }
        if (g.getOwnerUserId().equals(memberUserId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "组长角色不可变更");
        }
        // 17x-1：行锁读——降职要动 quota，与 updateQuota/doChargeGroup 的成员行锁同序互斥，防「边降职边消耗/边调限额」竞态
        ProjectGroupMemberEntity m = requireMemberForUpdate(groupId, memberUserId);
        String cur = m.getRole() == null ? ProjectGroupMemberEntity.ROLE_MEMBER : m.getRole();
        if (ProjectGroupMemberEntity.ROLE_OWNER.equals(cur)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "组长角色不可变更");
        }
        if (role.equals(cur)) {
            return;
        }
        // V156：管理降回成员 → 其额度下级统一改挂组长（预算不悬空；降职后该行为 MEMBER 不再参与子树口径）
        if (ProjectGroupMemberEntity.ROLE_MANAGER.equals(cur) && ProjectGroupMemberEntity.ROLE_MEMBER.equals(role)) {
            // 下级快照须在持有本行 FOR UPDATE 事务内读（子级配额变更必先锁本管理行，快照一致）
            List<ProjectGroupMemberEntity> children = memberMapper.selectChildren(groupId, memberUserId);
            int reparented = memberMapper.reparentChildren(groupId, memberUserId, g.getOwnerUserId());
            if (reparented > 0) {
                log.info("管理降职下级改挂组长 groupId={} exManager={} count={}", groupId, memberUserId, reparented);
            }
            // 17x-1 缩额：下级带着自己的 quota 离开后，ex-manager 限额须同步收缩，堵「quota 200、下级分走 100、行还在 200 → 组内总额 300」超发。
            // 任一下级不限额（quota NULL）→ 差额不可算，保守取 used（与 allocatable 对不限额下级按 0 可分配同向）。ex-manager 本身不限额则不动。
            if (m.getQuotaLimitPoints() != null) {
                BigDecimal oldQuota = m.getQuotaLimitPoints();
                BigDecimal used = m.getUsedPoints() == null ? BigDecimal.ZERO : m.getUsedPoints();
                BigDecimal quotaNew;
                boolean hasUnboundedChild = children.stream()
                        .anyMatch(c -> c.getQuotaLimitPoints() == null);
                if (hasUnboundedChild) {
                    quotaNew = used;
                } else {
                    BigDecimal childReserved = children.stream()
                            .map(ProjectGroupMemberEntity::getQuotaLimitPoints)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    quotaNew = oldQuota.subtract(childReserved).max(used);
                }
                if (quotaNew.compareTo(oldQuota) != 0) {
                    m.setQuotaLimitPoints(quotaNew);
                    recordMemberQuotaLedger(groupId, actorUserId, memberUserId, oldQuota, quotaNew, "管理降职缩额");
                    log.info("管理降职缩额 groupId={} exManager={} quota {}->{} used={} 下级数={}",
                            groupId, memberUserId, oldQuota, quotaNew, used, children.size());
                }
            }
        }
        m.setRole(role);
        memberMapper.updateById(m);
        log.info("任免角色 groupId={} member={} {}->{} actor={}", groupId, memberUserId, cur, role, actorUserId);
    }

    /**
     * 设成员功能开关（17x#2，V139，组长/管理/admin，目标仅 MEMBER 行）：
     * kinds=null → 不限；空数组 → 全禁；否则白名单（元素∈CHAT/EMBED/RERANK/IMAGE/VIDEO，非法 400）。
     * 只挡新提交/新调用；在途任务正常结算退款（见规格 §6）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateMemberKinds(Long groupId, Long actorUserId, boolean admin, Long memberUserId, List<String> kinds) {
        requireRole(groupId, actorUserId, admin, ProjectGroupMemberEntity.ROLE_MANAGER);
        ProjectGroupMemberEntity m = requireOperatableMember(groupId, memberUserId);
        MemberAllowedKinds.validate(kinds);
        m.setAllowedKinds(MemberAllowedKinds.toJson(kinds));
        memberMapper.updateById(m);
        log.info("成员功能开关 groupId={} member={} kinds={} actor={}", groupId, memberUserId, kinds, actorUserId);
    }

    private ProjectGroupMemberEntity requireMember(Long groupId, Long userId) {
        ProjectGroupMemberEntity m = memberMapper.selectByGroupUser(groupId, userId);
        if (m == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该用户不是组成员");
        }
        return m;
    }

    /**
     * 行锁版 {@link #requireMember}（17x-1 降职缩额专用）：
     * 只在降职/配额写入路径用，与 selectByGroupUserForUpdate 同串行化点，
     * 防「边降职边消耗/边调限额」读到过期 quota/used。锁序与 doChargeGroup 一致（成员行），无环。
     */
    private ProjectGroupMemberEntity requireMemberForUpdate(Long groupId, Long userId) {
        ProjectGroupMemberEntity m = memberMapper.selectByGroupUserForUpdate(groupId, userId);
        if (m == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该用户不是组成员");
        }
        return m;
    }

    /**
     * 成员配额流水留痕（20x-2「累计被分配」数据源；17x-1 降职缩额留痕）。
     * 非资金腿（不动组池余额，balance_after 只照抄当前池余额做快照），对账等式在 D4 白名单显式排除。
     * <ul>
     *   <li>old==new：无变化不落行</li>
     *   <li>new==null：限额→不限，QUOTA_ADJUST delta=0 记边界</li>
     *   <li>old==null：首次授予/落行，ALLOCATE delta=new</li>
     *   <li>d=new−old：d&gt;0 ALLOCATE（毛额口径），d&lt;0 RECLAIM（净额=ΣALLOCATE−ΣRECLAIM）</li>
     * </ul>
     */
    private void recordMemberQuotaLedger(Long groupId, Long actorUserId, Long memberUserId,
                                         BigDecimal oldQuota, BigDecimal newQuota, String cause) {
        String type;
        BigDecimal delta;
        if (Objects.equals(oldQuota, newQuota)) {
            return;
        }
        if (newQuota == null) {
            type = com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity.TYPE_MEMBER_QUOTA_ADJUST;
            delta = BigDecimal.ZERO;
        } else if (oldQuota == null) {
            type = com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity.TYPE_MEMBER_ALLOCATE;
            delta = newQuota;
        } else {
            BigDecimal d = newQuota.subtract(oldQuota);
            if (d.signum() == 0) {
                return;
            }
            type = d.signum() > 0
                    ? com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity.TYPE_MEMBER_ALLOCATE
                    : com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity.TYPE_MEMBER_RECLAIM;
            delta = d;
        }
        ProjectGroupWalletEntity w = walletMapper.selectByGroupId(groupId);
        com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity l =
                new com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity();
        l.setGroupId(groupId);
        l.setActorUserId(actorUserId);
        l.setType(type);
        l.setDeltaPoints(delta);
        l.setBalanceAfter(w != null ? w.getBalancePoints() : BigDecimal.ZERO);
        l.setRefType(com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity.REF_MEMBER);
        l.setRefId(String.valueOf(memberUserId));
        l.setRemark(cause + (newQuota == null
                ? "：限额 " + oldQuota + "→不限"
                : "：限额 " + (oldQuota == null ? "不限" : oldQuota) + "→" + newQuota));
        ledgerMapper.insert(l);
    }

    /**
     * 运营目标校验（V139）：移除/调限额/重置/开关/覆盖只允许落在 MEMBER 行——
     * MANAGER 不可被动（防管理互踢），OWNER 行走 updateMemberRole 之外的专属拦截。
     */
    private ProjectGroupMemberEntity requireOperatableMember(Long groupId, Long userId) {
        ProjectGroupMemberEntity m = requireMember(groupId, userId);
        String role = m.getRole() == null ? ProjectGroupMemberEntity.ROLE_MEMBER : m.getRole();
        if (!ProjectGroupMemberEntity.ROLE_MEMBER.equals(role)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅可管理普通成员（MEMBER）");
        }
        return m;
    }

    /**
     * 组内角色校验（V139）：admin 恒放行；组长恒满足；否则成员活行 role 须∈allowedRoles。
     * 钱（allocate/reclaim）与组级设置仍走 {@link #requireOwner}，本方法只放宽运营/查询类。
     *
     * @param allowedRoles 额外放行的组内角色（如 ROLE_MANAGER）
     * @return 组实体供调用方复用
     */
    public ProjectGroupEntity requireRole(Long groupId, Long userId, boolean admin, String... allowedRoles) {
        ProjectGroupEntity g = groupMapper.selectById(groupId);
        if (g == null || (g.getDeleted() != null && g.getDeleted() != 0)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目组不存在");
        }
        if (admin || g.getOwnerUserId().equals(Objects.requireNonNull(userId, "actorUserId"))) {
            return g;
        }
        ProjectGroupMemberEntity m = memberMapper.selectByGroupUser(groupId, userId);
        if (m == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "非本项目组成员");
        }
        String role = m.getRole() == null ? ProjectGroupMemberEntity.ROLE_MEMBER : m.getRole();
        for (String r : allowedRoles) {
            if (r.equals(role)) {
                return g;
            }
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "组内角色无权操作（需要组长/管理）");
    }

    /** 组长校验（admin 放行）；返回组实体供调用方复用。V138 起 public：邀请/公共池/可见性服务同口径复用。 */
    public ProjectGroupEntity requireOwner(Long groupId, Long userId, boolean admin) {
        ProjectGroupEntity g = groupMapper.selectById(groupId);
        if (g == null || (g.getDeleted() != null && g.getDeleted() != 0)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目组不存在");
        }
        if (!admin && !g.getOwnerUserId().equals(Objects.requireNonNull(userId, "actorUserId"))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅组长可管理项目组");
        }
        return g;
    }
}
