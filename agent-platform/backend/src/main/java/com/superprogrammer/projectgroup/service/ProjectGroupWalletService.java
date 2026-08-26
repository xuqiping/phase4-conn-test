package com.superprogrammer.projectgroup.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.billing.entity.IdempotencyKeyEntity;
import com.superprogrammer.billing.entity.PointsLedgerEntity;
import com.superprogrammer.billing.mapper.IdempotencyKeyMapper;
import com.superprogrammer.billing.service.PointsWalletService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.projectgroup.entity.ProjectGroupEntity;
import com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity;
import com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity;
import com.superprogrammer.projectgroup.entity.ProjectGroupWalletEntity;
import com.superprogrammer.projectgroup.mapper.ProjectGroupLedgerMapper;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMapper;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMemberMapper;
import com.superprogrammer.projectgroup.mapper.ProjectGroupWalletMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.function.Supplier;

/**
 * 组池钱包服务（计划5 Step2 核心账务 + V161 修复III 瀑布/欠款）：allocate / reclaim /
 * chargeGroup / refundGroup / selfTransfer（Chunk B）。
 *
 * <p><b>锁序（plan §坑点 双钱包死锁）</b>：凡个人↔组池双向操作（allocate/reclaim/划拨）固定
 * <b>先个人行后组池行</b>；纯组内操作（chargeGroup/refundGroup）固定先组池后成员行。
 * 组长兜底腿在瀑布内（组池/名下之后），窄 AB-BA 窗口与 PG 死锁自愈见 doChargeGroup 注释。
 *
 * <p><b>对账不变量（V133 运维模板 + V161 扩容）</b>：①末行 ledger.balance_after ==
 * wallets.balance_points（行锁读保证一致）；②成员 Σ(CONSUME+SELF_CONSUME+BACKSTOP
 * −REFUND−SELF_REFUND) == used_points + debt_pool + debt_leader（V161：used 无条件累加真实
 * 消耗，超限额溢出按垫付方拆欠款——组长垫/组池垫各回各家，名下垫是自己的钱不记欠款；
 * 调限额豁免/重置/核销走 DEBT_WRITEOFF 调整腿）。
 *
 * <p><b>幂等</b>：chargeGroup/refundGroup 复用 idempotency_key（scope=group.charge/group.refund，
 * result_ref=组流水 id），媒体链路幂等键=taskId（Step5）。同键同额静默重放，同键异额 CONFLICT。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectGroupWalletService {

    private final ProjectGroupMapper groupMapper;
    private final ProjectGroupMemberMapper memberMapper;
    private final ProjectGroupWalletMapper walletMapper;
    private final ProjectGroupLedgerMapper ledgerMapper;
    private final PointsWalletService pointsWallet;
    private final IdempotencyKeyMapper idemMapper;
    private final MemberBudgetService budgetService;
    /** 计划 E1（7x-3）：组池/成员积分变动事件发布（只投递，推送在监听侧 AFTER_COMMIT）。 */
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    /** 组长划拨：个人 -points（GROUP_ALLOCATE 流水）→ 组池 +points（ALLOCATE 流水）。admin 越组长代管放行（审计在 Controller @AuditLog）。 */
    @Transactional(rollbackFor = Exception.class)
    public void allocate(Long groupId, Long actorUserId, boolean admin, BigDecimal points, String remark) {
        requireOwner(groupId, actorUserId, admin);
        requirePositive(points, "划拨积分必须大于0");
        pointsWallet.debitForGroupAllocate(actorUserId, points, groupId, remark);   // 锁①个人
        if (walletMapper.credit(groupId, points) == 0) {                            // 锁②组池
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "组池钱包行缺失 groupId=" + groupId);
        }
        appendLedger(groupId, actorUserId, ProjectGroupLedgerEntity.TYPE_ALLOCATE,
                points, ProjectGroupLedgerEntity.REF_GROUP, String.valueOf(groupId), remark);
        publishGroupChanged(groupId, requireWallet(groupId).getBalancePoints(), points, remark);
        log.info("组划拨 groupId={} owner={} points={}", groupId, actorUserId, points);
    }

    /**
     * 组长回收：组池 -points（RECLAIM 流水）→ 个人 +points（GROUP_RECLAIM 流水）。
     * <p>上限 = 组池余额 − Σ在途 estimated_cost（媒体 PENDING/RUNNING 预扣）。上限判定在锁前读，
     * 与并发提交媒体任务存在窄竞态——兜底是条件 deduct（余额够才扣），最坏结果是组池被在途占用的钱
     * 提前回笼到个人，结算时差额走 BACKSTOP，不会丢钱/负账（组长自担，可接受）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void reclaim(Long groupId, Long actorUserId, boolean admin, BigDecimal points, String remark) {
        requireOwner(groupId, actorUserId, admin);
        requirePositive(points, "回收积分必须大于0");
        ProjectGroupWalletEntity w = requireWallet(groupId);
        BigDecimal inflight = walletMapper.sumInflightEstimated(groupId);
        if (points.compareTo(w.getBalancePoints().subtract(inflight)) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "回收金额超上限：组池 " + w.getBalancePoints() + "，在途任务占用 " + inflight);
        }
        pointsWallet.creditForGroupReclaim(actorUserId, points, groupId, remark);   // 锁①个人
        if (walletMapper.deduct(groupId, points) == 0) {                            // 锁②组池（条件防透支）
            throw new BusinessException(ErrorCode.BAD_REQUEST, "组池余额不足，回收失败");
        }
        appendLedger(groupId, actorUserId, ProjectGroupLedgerEntity.TYPE_RECLAIM,
                points.negate(), ProjectGroupLedgerEntity.REF_GROUP, String.valueOf(groupId), remark);
        publishGroupChanged(groupId, requireWallet(groupId).getBalancePoints(), points.negate(), remark);
        log.info("组回收 groupId={} owner={} points={}", groupId, actorUserId, points);

    }

    /**
     * 成员个人划拨至组内名下（V161 修复III B1，规格 §4.1）：本人个人钱包扣款 → 先还欠款
     * （组长垫→组池垫，真金白银各回各家）→ 余款进名下余额 self_points。还款额同步回减 used
     * （不变量②：debt 减少必伴随 used 减少；可用空间随之恢复，debt 合计归零即解除消费冻结）。
     *
     * <p><b>锁序</b>：本人个人（debit）→ 组长个人（还垫腿，金额=预读计划值）→ 组池 → 成员行。
     * 「组长个人→组池」与 allocate/reclaim 同向，和瀑布组长腿（池/名下后才锁组长）之间的窄 AB-BA
     * 窗口与 allocate 同类——PG 死锁检测器自愈 + 幂等键兜底（doChargeGroup 注释同口径留档）。
     * 锁后复验 debt_leader ≥ 计划值，不等（并发退款/豁免先动了债）整单 CONFLICT 回滚重试。
     *
     * @return 划拨后组池余额
     */
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal selfTransfer(Long groupId, Long userId, BigDecimal points, String idemKey) {
        requirePositive(points, "划拨积分必须大于0");
        if (idemKey != null && !idemKey.isBlank()) {
            return runIdempotent(idemKey, groupId, userId, "group.selftransfer", points,
                    () -> doSelfTransfer(groupId, userId, points));
        }
        return doSelfTransfer(groupId, userId, points);
    }

    private BigDecimal doSelfTransfer(Long groupId, Long userId, BigDecimal points) {
        ProjectGroupMemberEntity probe = memberMapper.selectByGroupUser(groupId, userId);
        if (probe == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "非项目组成员，无法划拨至组内名下");
        }
        ProjectGroupEntity g = groupMapper.selectById(groupId);
        if (g == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目组不存在");
        }
        BigDecimal rlPlan = nz(probe.getDebtLeaderPoints()).min(points);   // 预读：还组长垫计划值
        pointsWallet.debitForGroupAllocate(userId, points, groupId,
                "划拨至组内名下（先还欠款，余款进名下）");                        // 锁①本人个人
        if (rlPlan.signum() > 0) {
            pointsWallet.creditForGroupReclaim(g.getOwnerUserId(), rlPlan, groupId,
                    "成员还款·退组长垫付 member=" + userId);                     // 锁②组长个人
        }
        walletMapper.selectByGroupIdForUpdate(groupId);                       // 锁③组池
        ProjectGroupMemberEntity m = memberMapper.selectByGroupUserForUpdate(groupId, userId);  // 锁④成员
        if (m == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "成员已退组，划拨失败");
        }
        if (nz(m.getDebtLeaderPoints()).compareTo(rlPlan) < 0) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "欠款刚被并发变动（退款/豁免），请刷新后重试");
        }
        BigDecimal rl = rlPlan;
        BigDecimal rp = nz(m.getDebtPoolPoints()).min(points.subtract(rl));
        BigDecimal toSelf = points.subtract(rl).subtract(rp);
        if (rp.signum() > 0) {
            walletMapper.credit(groupId, rp);                                 // 组池垫还款回池（资金腿）
            memberMapper.adjustDebtPool(groupId, userId, rp.negate());
        }
        if (rl.signum() > 0) {
            memberMapper.adjustDebtLeader(groupId, userId, rl.negate());
        }
        if (rl.add(rp).signum() > 0) {
            memberMapper.subtractUsed(groupId, userId, rl.add(rp));           // 不变量②：债清额回减已用
        }
        if (toSelf.signum() > 0) {
            memberMapper.creditSelf(groupId, userId, toSelf);
        }
        BigDecimal balanceAfter = requireWallet(groupId).getBalancePoints();
        // 腿顺序：SELF_REPAY 先落、SELF_ALLOCATE **最后**落——runIdempotent 以「组内最新一条流水」回填
        // result_ref 并按其 delta==points 校验重放，SELF_ALLOCATE(delta=+points 总额) 必须是末行。
        if (rl.signum() > 0 || rp.signum() > 0) {
            appendLedgerRow(balanceAfter, groupId, userId, ProjectGroupLedgerEntity.TYPE_SELF_REPAY,
                    rp, ProjectGroupLedgerEntity.REF_MEMBER, String.valueOf(userId),
                    "还款（组长垫 " + plain(rl) + " 退组长个人 / 组池垫 " + plain(rp) + " 回组池）");
        }
        appendLedgerRow(balanceAfter, groupId, userId, ProjectGroupLedgerEntity.TYPE_SELF_ALLOCATE,
                points, ProjectGroupLedgerEntity.REF_MEMBER, String.valueOf(userId),
                "个人划拨入组（还组长垫 " + plain(rl) + " / 还组池垫 " + plain(rp)
                        + " / 入名下 " + plain(toSelf) + "）");
        publishGroupChanged(groupId, balanceAfter, rp, "selfTransfer:" + userId);
        publishMemberUsed(groupId, userId, rl.add(rp).negate(), "selfTransfer:" + userId);
        log.info("个人划拨入组 groupId={} member={} points={}（还组长垫 {}/还组池垫 {}/入名下 {}）",
                groupId, userId, points, plain(rl), plain(rp), plain(toSelf));
        return balanceAfter;
    }

    /** 千分位无关的紧凑数值文本（流水备注用，stripTrailingZeros 防科学计数法）。 */
    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    /**
     * 退组结算（V161 修复III B3，规格 §4.3）：名下余额 self_points 先还欠款（组长垫→组池垫，真金白银
     * 各回各家），余款原路退本人个人钱包；名下覆盖不了的欠款余额 DEBT_WRITEOFF 核销留痕（人走账清，
     * 组长垫损失自负——组长的钱垫给谁由组长的邀请决定）。
     *
     * <p><b>锁序</b>：组池 → 成员行 →（组长个人、本人个人）——与 doChargeGroup 完全同序
     * （瀑布组长腿=池/名下之后锁组长个人），不引入新锁窗口。
     *
     * <p>由 ProjectGroupService.removeMember 在同一事务内、软删成员行**之前**调用；
     * 成员行随后软删，used 不回减（不变量②只约束在册行；历史腿留审计）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void settleOnMemberRemoval(Long groupId, Long memberUserId) {
        walletMapper.selectByGroupIdForUpdate(groupId);                                        // 锁①组池
        ProjectGroupMemberEntity m = memberMapper.selectByGroupUserForUpdate(groupId, memberUserId);  // 锁②成员
        if (m == null) {
            return;  // 已不在册（并发退组/从未加入）——无事可结
        }
        ProjectGroupEntity g = groupMapper.selectById(groupId);
        BigDecimal self = nz(m.getSelfPoints());
        BigDecimal rl = self.min(nz(m.getDebtLeaderPoints()));
        BigDecimal rp = self.subtract(rl).min(nz(m.getDebtPoolPoints()));
        BigDecimal refund = self.subtract(rl).subtract(rp);
        BigDecimal woLeader = nz(m.getDebtLeaderPoints()).subtract(rl);
        BigDecimal woPool = nz(m.getDebtPoolPoints()).subtract(rp);
        if (rp.signum() > 0) {
            walletMapper.credit(groupId, rp);                                                 // 名下还组池垫→回池（资金腿）
        }
        if (rl.signum() > 0 && g != null) {
            pointsWallet.creditForGroupReclaim(g.getOwnerUserId(), rl, groupId,
                    "退组结算·名下还组长垫 member=" + memberUserId);                             // 锁③组长个人
        }
        if (refund.signum() > 0) {
            pointsWallet.creditForGroupReclaim(memberUserId, refund, groupId,
                    "退组结算·名下余额退本人");                                                 // 锁④本人个人
        }
        BigDecimal balanceAfter = requireWallet(groupId).getBalancePoints();
        if (rl.signum() > 0 || rp.signum() > 0) {
            appendLedgerRow(balanceAfter, groupId, memberUserId, ProjectGroupLedgerEntity.TYPE_SELF_REPAY,
                    rp, ProjectGroupLedgerEntity.REF_MEMBER, String.valueOf(memberUserId),
                    "退组还款（组长垫 " + plain(rl) + " 退组长个人 / 组池垫 " + plain(rp) + " 回组池）");
        }
        if (refund.signum() > 0) {
            appendLedgerRow(balanceAfter, groupId, memberUserId, ProjectGroupLedgerEntity.TYPE_SELF_REFUND,
                    refund, ProjectGroupLedgerEntity.REF_MEMBER, String.valueOf(memberUserId),
                    "退组·名下余额 " + plain(refund) + " 退本人个人钱包");
        }
        if (woLeader.add(woPool).signum() > 0) {
            appendLedgerRow(balanceAfter, groupId, memberUserId, ProjectGroupLedgerEntity.TYPE_DEBT_WRITEOFF,
                    BigDecimal.ZERO, ProjectGroupLedgerEntity.REF_MEMBER, String.valueOf(memberUserId),
                    "退组核销欠款（组长垫 " + plain(woLeader) + " / 组池垫 " + plain(woPool) + "）");
        }
        if (woLeader.add(woPool).signum() > 0) {
            log.warn("退组核销欠款 groupId={} member={}（组长垫 {} / 组池垫 {}）——组长垫损失自负",
                    groupId, memberUserId, plain(woLeader), plain(woPool));
        }
        if (rp.signum() > 0) {
            publishGroupChanged(groupId, balanceAfter, rp, "settle:" + memberUserId);
        }
        log.info("退组结算 groupId={} member={}（还组长垫 {}/还组池垫 {}/退本人 {}/核销 组长垫{}+组池垫{}）",
                groupId, memberUserId, plain(rl), plain(rp), plain(refund), plain(woLeader), plain(woPool));
    }

    /**
     * 成员消耗（chat/embed 埋点侧 / 媒体提交预扣）：组池条件扣 + 成员 used 条件加 + CONSUME 流水。
     * <p>幂等键=媒体 taskId 等；空键退化直扣。组池不足/超限额各自抛错，整体回滚（组池分文不动）。
     *
     * @return 扣后组池余额
     */
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal chargeGroup(Long groupId, Long memberUserId, BigDecimal cost,
                                  String refType, String refId, String idemKey, boolean allowDebt) {
        requirePositive(cost, "消耗积分必须大于0");
        if (idemKey != null && !idemKey.isBlank()) {
            return runIdempotent(idemKey, groupId, memberUserId, "group.charge", cost,
                    () -> doChargeGroup(groupId, memberUserId, cost, refType, refId, allowDebt));
        }
        return doChargeGroup(groupId, memberUserId, cost, refType, refId, allowDebt);
    }

    /**
     * V161 修复III 瀑布（规格 §3.2）：组池 → 名下余额 → 组长兜底，三腿各自独立条件扣；
     * 成员 used 无条件累加（真实消耗），超限额溢出按垫付方拆 debt_leader/debt_pool（尾部倒推）。
     *
     * <p><b>缺陷1根除</b>：旧实现「组池先扣 → addUsed 撞 quota 守卫 → 整单回滚 → 上层全额转
     * 组长兜底」——组池富余也分文未动、组长白付。现 quota 只在 allowDebt=false（HOLD 预扣，
     * 预扣不进欠款）时前置拒绝；结算补差（allowDebt=true）一路扣到底，业务性不足不抛不回滚。
     *
     * <p><b>锁序</b>：组池 → 成员行（与 updateQuota/退款同向）。组长个人腿在两腿之后才锁个人
     * （金额依赖前两腿结果）——与「个人→组池」类操作（allocate/reclaim）理论上有窄 AB-BA 窗口
     * （成员补差兜底 与 组长划拨/回收 恰好并发交叠），PG 死锁检测器会中止一方（报错可重试，
     * 媒体/聊天链路幂等键兜住不双扣）。窗口极窄且旧路径同样存在，不为它牺牲三腿结构。
     *
     * @param allowDebt false=HOLD 预扣（欠款冻结+限额硬卡，超即拒）；true=结算补差（真实消耗，
     *                  扣到底，溢出转欠款）
     */
    private BigDecimal doChargeGroup(Long groupId, Long memberUserId, BigDecimal cost,
                                     String refType, String refId, boolean allowDebt) {
        enforceKindAllowed(groupId, memberUserId, refType);                          // 17x#2 功能开关（先拦，不动钱包）
        com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity row =
                memberMapper.selectByGroupUserForUpdate(groupId, memberUserId);      // 锁成员行
        if (row == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "非项目组成员，不可使用组池计费");
        }
        java.math.BigDecimal quota = row.getQuotaLimitPoints();
        java.math.BigDecimal usedBefore = nz(row.getUsedPoints());
        java.math.BigDecimal debtTotal = nz(row.getDebtPoolPoints()).add(nz(row.getDebtLeaderPoints()));
        if (!allowDebt) {                                                            // HOLD 预扣闸（规格 §3.2）
            if (debtTotal.signum() > 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "你有未抵扣欠款 " + debtTotal.stripTrailingZeros().toPlainString()
                                + "，暂停组内消费（划拨或组长调限额抵清后恢复）");
            }
            if (quota != null && usedBefore.add(cost).compareTo(quota) > 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "超出组长配置的成员限额");
            }
        }
        // V156 层级额度：管理本人消耗硬卡保留（超可分配=动下级预留，属配置越权非真实消耗，两路径同卡）
        if (com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity.ROLE_MANAGER.equals(row.getRole())
                && quota != null) {
            BigDecimal available = budgetService.allocatable(groupId, row, null);
            if (available != null && cost.compareTo(available) > 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "超出你的可分配额度：剩余可分配 " + available + "（下级预留须保留），本次需 " + cost);
            }
        }
        // ---- 资金瀑布：①组池 ②名下 ③组长兜底（各腿独立，业务性不足转下腿）----
        ProjectGroupWalletEntity w = walletMapper.selectByGroupIdForUpdate(groupId);  // 锁组池
        if (w == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "组池钱包行缺失 groupId=" + groupId);
        }
        BigDecimal poolPart = w.getBalancePoints().min(cost);
        if (poolPart.signum() > 0 && walletMapper.deduct(groupId, poolPart) == 0) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "组池条件扣失败（并发防护触发）groupId=" + groupId);
        }
        BigDecimal rest = cost.subtract(poolPart);
        BigDecimal selfPart = BigDecimal.ZERO;
        if (rest.signum() > 0 && nz(row.getSelfPoints()).signum() > 0) {
            selfPart = nz(row.getSelfPoints()).min(rest);
            if (memberMapper.deductSelf(groupId, memberUserId, selfPart) == 0) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "名下余额条件扣失败（并发防护触发）");
            }
            rest = rest.subtract(selfPart);
        }
        BigDecimal shortfall = rest;
        if (shortfall.signum() > 0) {
            settleShortfallByLeader(groupId, memberUserId, shortfall, refType, refId);
        }
        // ---- 成员记账：used 无条件累加；溢出按瀑布尾部资金来源拆欠款（组长垫=最后一段）----
        memberMapper.addUsedUnconditional(groupId, memberUserId, cost);
        BigDecimal overflow = quota == null ? BigDecimal.ZERO
                : usedBefore.add(cost).subtract(quota).max(BigDecimal.ZERO);
        if (overflow.signum() > 0) {
            BigDecimal leaderTail = overflow.min(shortfall);
            BigDecimal rem = overflow.subtract(leaderTail);
            BigDecimal selfTail = rem.min(selfPart);   // 名下垫的超帽段=自己的钱，不记欠款
            BigDecimal poolTail = rem.subtract(selfTail);
            if (leaderTail.signum() > 0) {
                memberMapper.adjustDebtLeader(groupId, memberUserId, leaderTail);
            }
            if (poolTail.signum() > 0) {
                memberMapper.adjustDebtPool(groupId, memberUserId, poolTail);
            }
            log.warn("成员超限额欠款 groupId={} member={} overflow={}（组长垫 {} / 组池垫 {} / 名下垫 {}）ref={}:{}",
                    groupId, memberUserId, overflow, leaderTail, poolTail, selfTail, refType, refId);
        }
        // ---- 流水腿（>0 才落）----
        BigDecimal balanceAfter = requireWallet(groupId).getBalancePoints();
        if (poolPart.signum() > 0) {
            appendLedgerRow(balanceAfter, groupId, memberUserId,
                    ProjectGroupLedgerEntity.TYPE_CONSUME, poolPart.negate(), refType, refId, null);
        }
        if (selfPart.signum() > 0) {
            appendLedgerRow(balanceAfter, groupId, memberUserId,
                    ProjectGroupLedgerEntity.TYPE_SELF_CONSUME, selfPart.negate(), refType, refId,
                    "扣成员名下余额（不动组池）");
        }
        publishGroupChanged(groupId, balanceAfter, cost.negate(), refType + ":" + refId);
        publishMemberUsed(groupId, memberUserId, cost, refType + ":" + refId);
        log.info("组消耗瀑布 groupId={} member={} cost={}（池 {}/名下 {}/兜底 {}）used {}→{} ref={}:{}",
                groupId, memberUserId, cost, poolPart, selfPart, shortfall,
                usedBefore, usedBefore.add(cost), refType, refId);
        return balanceAfter;
    }

    /** null 安全转零（V161 欠款/名下字段旧行可能为 null 前置态，防御读）。 */
    private static java.math.BigDecimal nz(java.math.BigDecimal v) {
        return v == null ? java.math.BigDecimal.ZERO : v;
    }

    /**
     * 瀑布第③腿：组池+名下皆尽后的缺口由组长个人承担（扣尽挂账，B5/Q10=A 口径）+ BACKSTOP 流水腿。
     * 旧 public backstop()（整额兜底+addUsedUnconditional）随瀑布重构删除——结算不再「限额不足→回滚→组长全额垫」。
     */
    private void settleShortfallByLeader(Long groupId, Long consumerUserId, BigDecimal shortfall,
                                         String refType, String refId) {
        ProjectGroupEntity g = groupMapper.selectById(groupId);
        if (g == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目组已删除，无法兜底 groupId=" + groupId);
        }
        if (pointsWallet.getBalance(g.getOwnerUserId()).compareTo(shortfall) >= 0) {
            pointsWallet.charge(g.getOwnerUserId(), shortfall, PointsLedgerEntity.REF_GROUP, groupId,
                    "组池+名下皆尽·组长兜底");                                       // 锁组长个人
        } else {
            pointsWallet.chargeToDebt(g.getOwnerUserId(), shortfall, PointsLedgerEntity.REF_GROUP, groupId,
                    "组池+名下皆尽·组长兜底");
            log.warn("兜底转挂账 groupId={} leader={} shortfall={}", groupId, g.getOwnerUserId(), shortfall);
        }
        appendLedgerRow(requireWallet(groupId).getBalancePoints(), groupId, g.getOwnerUserId(),
                ProjectGroupLedgerEntity.TYPE_BACKSTOP, shortfall.negate(), refType, refId,
                "组池+名下皆尽·差额由组长个人承担（计入成员已用，超限额部分转成员欠款·组长垫）");
        log.warn("BACKSTOP groupId={} leader={} consumer={} shortfall={} ref={}:{}",
                groupId, g.getOwnerUserId(), consumerUserId, shortfall, refType, refId);
    }

    /**
     * 成员退款（媒体失败/实耗<预扣）：组池 +points + used 回减 + REFUND 流水。对称 chargeGroup。
     */
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal refundGroup(Long groupId, Long memberUserId, BigDecimal points,
                                  String refType, String refId, String idemKey) {
        requirePositive(points, "退款积分必须大于0");
        if (idemKey != null && !idemKey.isBlank()) {
            return runIdempotent(idemKey, groupId, memberUserId, "group.refund", points,
                    () -> doRefundGroup(groupId, memberUserId, points, refType, refId));
        }
        return doRefundGroup(groupId, memberUserId, points, refType, refId);
    }

    /**
     * 成员退款（V161 修复III 按腿反冲）：按该任务原始瀑布腿比例拆退款——
     * 组池腿→组池、名下腿→名下、兜底腿→组长个人钱包；成员记账先还欠款（组长垫→组池垫）再回减 used。
     * 无腿记录的老任务回落原行为（全额退组池+回减 used）。幂等由外层 refundGroup idemKey 兜。
     */
    private BigDecimal doRefundGroup(Long groupId, Long memberUserId, BigDecimal points,
                                     String refType, String refId) {
        ProjectGroupWalletEntity w = walletMapper.selectByGroupIdForUpdate(groupId);  // 锁组池
        if (w == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "组池钱包行缺失 groupId=" + groupId);
        }
        // 原始腿收集（同 ref 任务的三腿；排除 REFUND 系防自污染）
        java.util.List<ProjectGroupLedgerEntity> legs = ledgerMapper.selectList(
                new LambdaQueryWrapper<ProjectGroupLedgerEntity>()
                        .eq(ProjectGroupLedgerEntity::getGroupId, groupId)
                        .eq(ProjectGroupLedgerEntity::getRefId, refId)
                        .eq(ProjectGroupLedgerEntity::getRefType, refType)
                        .in(ProjectGroupLedgerEntity::getType, java.util.List.of(
                                ProjectGroupLedgerEntity.TYPE_CONSUME,
                                ProjectGroupLedgerEntity.TYPE_SELF_CONSUME,
                                ProjectGroupLedgerEntity.TYPE_BACKSTOP)));
        BigDecimal base = legs.stream().map(l -> nz(l.getDeltaPoints()).abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal poolLeg = points, selfLeg = BigDecimal.ZERO, leadLeg = BigDecimal.ZERO;
        if (!legs.isEmpty() && base.signum() > 0) {
            selfLeg = points.multiply(legSum(legs, ProjectGroupLedgerEntity.TYPE_SELF_CONSUME))
                    .divide(base, 2, java.math.RoundingMode.HALF_UP);
            leadLeg = points.multiply(legSum(legs, ProjectGroupLedgerEntity.TYPE_BACKSTOP))
                    .divide(base, 2, java.math.RoundingMode.HALF_UP);
            poolLeg = points.subtract(selfLeg).subtract(leadLeg).max(BigDecimal.ZERO);  // 舍入差兜底入池
        }
        if (poolLeg.signum() > 0 && walletMapper.credit(groupId, poolLeg) == 0) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "组池钱包行缺失 groupId=" + groupId);
        }
        // 成员记账（锁成员行；组池→成员既有锁序）：先还欠款（组长垫→组池垫），余量回减 used
        com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity row =
                memberMapper.selectByGroupUserForUpdate(groupId, memberUserId);
        if (row != null) {
            BigDecimal dLeader = nz(row.getDebtLeaderPoints()).min(points);
            BigDecimal dPool = nz(row.getDebtPoolPoints()).min(points.subtract(dLeader));
            if (dLeader.signum() > 0) {
                memberMapper.adjustDebtLeader(groupId, memberUserId, dLeader.negate());
            }
            if (dPool.signum() > 0) {
                memberMapper.adjustDebtPool(groupId, memberUserId, dPool.negate());
            }
            memberMapper.subtractUsed(groupId, memberUserId, points.subtract(dLeader).subtract(dPool));
            if (selfLeg.signum() > 0) {
                memberMapper.creditSelf(groupId, memberUserId, selfLeg);
            }
        } else {
            log.warn("退款成员行缺失（已退组？）组池腿照退 groupId={} member={} ref={}:{}",
                    groupId, memberUserId, refType, refId);
        }
        // 兜底腿退组长个人（钱出组长钱包，退还本人）
        if (leadLeg.signum() > 0) {
            ProjectGroupEntity g = groupMapper.selectById(groupId);
            if (g != null) {
                pointsWallet.refund(g.getOwnerUserId(), leadLeg, PointsLedgerEntity.REF_GROUP, groupId,
                        "组池退款·退组长兜底垫付 ref=" + refType + ":" + refId);
            }
        }
        BigDecimal balanceAfter = requireWallet(groupId).getBalancePoints();
        if (poolLeg.signum() > 0) {
            appendLedgerRow(balanceAfter, groupId, memberUserId,
                    ProjectGroupLedgerEntity.TYPE_REFUND, poolLeg, refType, refId, null);
        }
        if (selfLeg.signum() > 0) {
            appendLedgerRow(balanceAfter, groupId, memberUserId,
                    ProjectGroupLedgerEntity.TYPE_SELF_REFUND, selfLeg, refType, refId, "退成员名下余额（不动组池）");
        }
        if (leadLeg.signum() > 0) {
            appendLedgerRow(balanceAfter, groupId, memberUserId,
                    ProjectGroupLedgerEntity.TYPE_REFUND, BigDecimal.ZERO, refType, refId,
                    "退组长兜底垫付 " + leadLeg.stripTrailingZeros().toPlainString() + "（不动组池）");
        }
        publishGroupChanged(groupId, balanceAfter, points, refType + ":" + refId);
        publishMemberUsed(groupId, memberUserId, points.negate(), refType + ":" + refId);
        log.info("组退款(按腿) groupId={} member={} points={}（池 {}/名下 {}/退兜底 {}）ref={}:{}",
                groupId, memberUserId, points, poolLeg, selfLeg, leadLeg, refType, refId);
        return balanceAfter;
    }

    /** 同 ref 同 type 腿绝对值合计（退款按腿分摊比例用）。 */
    private BigDecimal legSum(java.util.List<ProjectGroupLedgerEntity> legs, String type) {
        return legs.stream().filter(l -> type.equals(l.getType()))
                .map(l -> nz(l.getDeltaPoints()).abs()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 组池余额（不抛版，预估预览用）；组池行缺失 → ZERO。 */
    public BigDecimal getGroupBalance(Long groupId) {
        ProjectGroupWalletEntity w = walletMapper.selectByGroupId(groupId);
        return w != null && w.getBalancePoints() != null ? w.getBalancePoints() : BigDecimal.ZERO;
    }

    /**
     * 组池预检（计划5 Step4 网关入口）：非成员 FORBIDDEN；组池≤0 INSUFFICIENT_POINTS。
     * 镜像个人 {@code PointsWalletService.requireAffordable} 语义（>0 放行，非精确估价）。
     *
     * @return 组池余额（L7 闸门/前端提示复用）
     */
    public BigDecimal requireAffordableGroup(Long groupId, Long userId) {
        return requireAffordableGroup(groupId, userId, null);
    }

    /**
     * 组池预检 + 成员功能开关（17x#2，V139 重载）：kind 非空且被成员 allowed_kinds 白名单排除 → 400。
     * 入口体验层拦截（真防线在 {@link #doChargeGroup} 同事务校验）。
     * <p>17x 安全审计补漏（V156）：原预检不查成员限额——成员超限额后结算硬卡被计费铁律吞掉
     * （usage 记 FAILED 但模型已调用=免费用），此处补齐 成员 used≥quota 与 管理可分配≤0 两道预检。
     */
    public BigDecimal requireAffordableGroup(Long groupId, Long userId, String kind) {
        if (userId == null) {
            // 系统调用（uid=null）带 gid 属配置错误——组计费必须归属到人（used 记账）
            throw new BusinessException(ErrorCode.BAD_REQUEST, "项目组计费必须归属到成员");
        }
        ProjectGroupEntity g = groupMapper.selectById(groupId);
        if (g == null || (g.getDeleted() != null && g.getDeleted() != 0)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目组不存在");
        }
        com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity m =
                memberMapper.selectByGroupUser(groupId, userId);
        if (m == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "非项目组成员，不可使用组池计费");
        }
        if (!MemberAllowedKinds.isAllowed(m.getAllowedKinds(), kind)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "组长已限制你在本组使用该类模型（" + kind + "）");
        }
        // V161 修复III：欠款冻结预检（与 doChargeGroup HOLD 闸同口径——欠款未抵扣即暂停组内消费）
        java.math.BigDecimal debtTotal = nz(m.getDebtPoolPoints()).add(nz(m.getDebtLeaderPoints()));
        if (debtTotal.signum() > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "你有未抵扣欠款 " + debtTotal.stripTrailingZeros().toPlainString()
                            + "，暂停组内消费（划拨或组长调限额抵清后恢复）");
        }
        // 审计补漏①：成员限额预检（与 addUsed 硬卡同口径；预估口径 used≥quota 即拦）
        if (m.getQuotaLimitPoints() != null && m.getUsedPoints() != null
                && m.getUsedPoints().compareTo(m.getQuotaLimitPoints()) >= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "超出组长配置的成员限额");
        }
        // 审计补漏②：管理可分配预检（与 doChargeGroup 预算硬卡同口径；>0 放行，非精确估价）
        if (com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity.ROLE_MANAGER.equals(m.getRole())
                && m.getQuotaLimitPoints() != null) {
            BigDecimal available = budgetService.allocatable(groupId, m, null);
            if (available != null && available.signum() <= 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "你的可分配额度已用尽（下级预留须保留），请找组长调整");
            }
        }
        ProjectGroupWalletEntity w = walletMapper.selectByGroupId(groupId);
        if (w == null || w.getBalancePoints().signum() <= 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_POINTS, "项目组积分不足");
        }
        return w.getBalancePoints();
    }

    /**
     * 成员功能开关硬卡（17x#2，V139）：消耗结算同事务校验——白名单排除即拒，整体回滚。
     * 非 5 模块 refType（GROUP/MEDIA 等结算类）不约束；非成员明确 403（原 quota 守卫文案误导）。
     */
    private void enforceKindAllowed(Long groupId, Long memberUserId, String refType) {
        if (refType == null || !ProjectGroupVisibilityService.OUTPUT_KINDS.contains(refType)) {
            return;
        }
        com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity m =
                memberMapper.selectByGroupUser(groupId, memberUserId);
        if (m == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "非项目组成员，不可使用组池计费");
        }
        if (!MemberAllowedKinds.isAllowed(m.getAllowedKinds(), refType)) {
            log.warn("组成员功能开关拦截 groupId={} member={} kind={}", groupId, memberUserId, refType);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "组长已限制你在本组使用该类模型（" + refType + "）");
        }
    }

    // ==================== 内部 ====================

    /** 组流水落库（balance_after 取当前组池——本事务已持行锁，读即一致）。 */
    /**
     * 计划 E1（7x-3）：组池余额变事件——按组员集（含 OWNER）逐个 userId 广播，
     * 每人一条独立事件（推送按 userId 索引连接）。失败只 WARN，不影响钱包主链。
     */
    private void publishGroupChanged(Long groupId, BigDecimal balanceAfter, BigDecimal delta, String reason) {
        try {
            java.util.List<Long> members = memberMapper.selectMemberUserIds(groupId);
            for (Long uid : members) {
                eventPublisher.publishEvent(com.superprogrammer.billing.event.PointsChangedEvent.builder()
                        .userId(uid)
                        .scope(com.superprogrammer.billing.event.PointsChangedEvent.SCOPE_GROUP)
                        .groupId(groupId)
                        .balanceAfter(balanceAfter)
                        .delta(delta)
                        .reason(reason)
                        .build());
            }
        } catch (Exception e) {
            log.warn("组池变动事件发布失败(不影响计费) groupId={} delta={}: {}", groupId, delta, e.toString());
        }
    }

    /** 计划 E1：成员 used 变事件（组页刷新用；balanceAfter 恒 null——used 无回读）。 */
    private void publishMemberUsed(Long groupId, Long memberUserId, BigDecimal delta, String reason) {
        try {
            eventPublisher.publishEvent(com.superprogrammer.billing.event.PointsChangedEvent.builder()
                    .userId(memberUserId)
                    .scope(com.superprogrammer.billing.event.PointsChangedEvent.SCOPE_MEMBER)
                    .groupId(groupId)
                    .balanceAfter(null)
                    .delta(delta)
                    .reason(reason)
                    .build());
        } catch (Exception e) {
            log.warn("成员 used 事件发布失败(不影响计费) groupId={} userId={}: {}", groupId, memberUserId, e.toString());
        }
    }

    private void appendLedger(Long groupId, Long actorUserId, String type, BigDecimal delta,
                              String refType, String refId, String remark) {
        ProjectGroupWalletEntity w = requireWallet(groupId);
        appendLedgerRow(w.getBalancePoints(), groupId, actorUserId, type, delta, refType, refId, remark);
    }

    private void appendLedgerRow(BigDecimal balanceAfter, Long groupId, Long actorUserId, String type,
                                 BigDecimal delta, String refType, String refId, String remark) {
        ProjectGroupLedgerEntity l = new ProjectGroupLedgerEntity();
        l.setGroupId(groupId);
        l.setActorUserId(actorUserId);
        l.setType(type);
        l.setDeltaPoints(delta);
        l.setBalanceAfter(balanceAfter);
        l.setRefType(refType);
        l.setRefId(refId);
        l.setRemark(remark);
        ledgerMapper.insert(l);
    }

    /**
     * 幂等骨架（镜像 PointsWalletService.runIdempotent，result_ref=组流水 id）：
     * 占位→撞键校验身份/金额→静默重放或 CONFLICT；占位成功执行并回填最新组流水 id。
     */
    private BigDecimal runIdempotent(String idemKey, Long groupId, Long actorId, String scope,
                                     BigDecimal expectPoints, Supplier<BigDecimal> action) {
        if (idemMapper.tryOccupy(idemKey, actorId, scope) == 0) {
            IdempotencyKeyEntity existing = idemMapper.selectByKey(idemKey);
            if (existing == null || !existing.getUserId().equals(actorId) || !existing.getScope().equals(scope)) {
                log.error("组账幂等键跨身份撞键: key={} actor={} scope={}", idemKey, actorId, scope);
                throw new BusinessException(ErrorCode.CONFLICT, "幂等键冲突，请更换幂等键");
            }
            if (existing.getResultRef() != null) {
                ProjectGroupLedgerEntity first = ledgerMapper.selectById(Long.valueOf(existing.getResultRef()));
                if (first != null) {
                    if (first.getDeltaPoints().abs().compareTo(expectPoints) != 0) {
                        log.error("组账幂等同键异额: key={} first={} expect={}", idemKey, first.getDeltaPoints(), expectPoints);
                        throw new BusinessException(ErrorCode.CONFLICT, "幂等键冲突，请更换幂等键");
                    }
                    log.info("组账幂等重放: key={} ledgerId={}", idemKey, first.getId());
                    return first.getBalanceAfter();
                }
            }
            throw new BusinessException(ErrorCode.CONFLICT, "请求处理中，请稍后重试");
        }
        BigDecimal balanceAfter = action.get();
        // 回填 result_ref：action 刚插的组流水即本组最新一条（同事务可见）
        ProjectGroupLedgerEntity last = ledgerMapper.selectOne(new LambdaQueryWrapper<ProjectGroupLedgerEntity>()
                .eq(ProjectGroupLedgerEntity::getGroupId, groupId)
                .orderByDesc(ProjectGroupLedgerEntity::getId)
                .last("LIMIT 1"));
        if (last != null) {
            idemMapper.updateResultRef(idemKey, String.valueOf(last.getId()));
        }
        return balanceAfter;
    }

    private ProjectGroupWalletEntity requireWallet(Long groupId) {
        ProjectGroupWalletEntity w = walletMapper.selectByGroupId(groupId);
        if (w == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "组池钱包行缺失 groupId=" + groupId);
        }
        return w;
    }

    private void requireOwner(Long groupId, Long userId, boolean admin) {
        ProjectGroupEntity g = groupMapper.selectById(groupId);
        if (g == null || (g.getDeleted() != null && g.getDeleted() != 0)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目组不存在");
        }
        if (!admin && !g.getOwnerUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅组长可操作项目组资金");
        }
    }

    private void requirePositive(BigDecimal points, String msg) {
        if (points == null || points.signum() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, msg);
        }
    }
}
