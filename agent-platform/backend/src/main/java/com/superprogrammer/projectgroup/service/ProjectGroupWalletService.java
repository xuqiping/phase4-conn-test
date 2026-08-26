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
 * 组池钱包服务（计划5 Step2 核心账务）：allocate / reclaim / chargeGroup / refundGroup / backstop。
 *
 * <p><b>锁序（plan §坑点 双钱包死锁）</b>：凡个人↔组池双向操作（allocate/reclaim/backstop）固定
 * <b>先个人行后组池行</b>；纯组内操作（chargeGroup/refundGroup）固定先组池后成员行。
 * 两序无交叉环：chargeGroup 只碰 组池→成员，双向操作只碰 个人→组池（backstop 兼计 used 时
 * 组池→成员与 chargeGroup 同向），并发混跑无死锁。
 *
 * <p><b>对账不变量（V133 运维模板）</b>：①末行 ledger.balance_after == wallets.balance_points
 * （BACKSTOP 不动组池，行锁读保证一致）；②成员 Σ(CONSUME−REFUND+BACKSTOP) == used_points
 * （7x-2 修复：BACKSTOP 计入 member.used——used=真实消耗，不论资金来源；组池 balance 不含
 * BACKSTOP，资金出自组长个人。存量差异由 V159 一次性回填）。
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
     * 成员消耗（chat/embed 埋点侧 / 媒体提交预扣）：组池条件扣 + 成员 used 条件加 + CONSUME 流水。
     * <p>幂等键=媒体 taskId 等；空键退化直扣。组池不足/超限额各自抛错，整体回滚（组池分文不动）。
     *
     * @return 扣后组池余额
     */
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal chargeGroup(Long groupId, Long memberUserId, BigDecimal cost,
                                  String refType, String refId, String idemKey) {
        requirePositive(cost, "消耗积分必须大于0");
        if (idemKey != null && !idemKey.isBlank()) {
            return runIdempotent(idemKey, groupId, memberUserId, "group.charge", cost,
                    () -> doChargeGroup(groupId, memberUserId, cost, refType, refId));
        }
        return doChargeGroup(groupId, memberUserId, cost, refType, refId);
    }

    private BigDecimal doChargeGroup(Long groupId, Long memberUserId, BigDecimal cost,
                                     String refType, String refId) {
        enforceKindAllowed(groupId, memberUserId, refType);                          // 17x#2 功能开关（先拦，不动钱包）
        if (walletMapper.deduct(groupId, cost) == 0) {                              // 锁①组池
            throw new BusinessException(ErrorCode.INSUFFICIENT_POINTS, "项目组积分不足");
        }
        // V156 层级额度：管理本人消耗硬卡——可分配（额度−子树已耗−下级预留）须 ≥ 本次消耗，
        // 否则管理能吃掉已预留给下级的预算。锁管理行（锁序：组池→成员行，与既有 addUsed 同向），
        // 与 updateQuota/邀请接受落行的 FOR UPDATE 互斥——边花边分并发打不穿。
        com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity chargeRow =
                memberMapper.selectByGroupUserForUpdate(groupId, memberUserId);     // 锁②成员行
        if (chargeRow != null
                && com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity.ROLE_MANAGER.equals(chargeRow.getRole())
                && chargeRow.getQuotaLimitPoints() != null) {
            BigDecimal available = budgetService.allocatable(groupId, chargeRow, null);
            if (available != null && cost.compareTo(available) > 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "超出你的可分配额度：剩余可分配 " + available + "（下级预留须保留），本次需 " + cost);
            }
        }
        if (memberMapper.addUsed(groupId, memberUserId, cost) == 0) {               // 条件加（quota 守卫）
            throw new BusinessException(ErrorCode.BAD_REQUEST, "超出组长配置的成员限额");
        }
        ProjectGroupWalletEntity w = requireWallet(groupId);                        // 行已被本事务 UPDATE 锁定
        appendLedgerRow(w.getBalancePoints(), groupId, memberUserId,
                ProjectGroupLedgerEntity.TYPE_CONSUME, cost.negate(), refType, refId, null);
        publishGroupChanged(groupId, w.getBalancePoints(), cost.negate(), refType + ":" + refId);
        publishMemberUsed(groupId, memberUserId, cost, refType + ":" + refId);
        log.info("组消耗 groupId={} member={} cost={} ref={}:{}", groupId, memberUserId, cost, refType, refId);
        return w.getBalancePoints();
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

    private BigDecimal doRefundGroup(Long groupId, Long memberUserId, BigDecimal points,
                                     String refType, String refId) {
        if (walletMapper.credit(groupId, points) == 0) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "组池钱包行缺失 groupId=" + groupId);
        }
        memberMapper.subtractUsed(groupId, memberUserId, points);                   // GREATEST 落 0
        ProjectGroupWalletEntity w = requireWallet(groupId);
        appendLedgerRow(w.getBalancePoints(), groupId, memberUserId,
                ProjectGroupLedgerEntity.TYPE_REFUND, points, refType, refId, null);
        publishGroupChanged(groupId, w.getBalancePoints(), points, refType + ":" + refId);
        publishMemberUsed(groupId, memberUserId, points.negate(), refType + ":" + refId);
        log.info("组退款 groupId={} member={} points={} ref={}:{}", groupId, memberUserId, points, refType, refId);
        return w.getBalancePoints();
    }

    /**
     * BACKSTOP（结算兜底）：组池不足的差额扣组长<b>个人</b>，组池不动（保 CHECK>=0）。
     * 两账本各记一行：个人 CONSUME(ref=GROUP) + 组流水 BACKSTOP(delta=-差额, balance_after=组池现值)。
     * <p>7x-2 修复：差额同时计入<b>消费成员</b> used（{@code addUsedUnconditional}，无 quota 守卫）——
     * used=真实消耗，与账单汇合（见类注释不变量②）。成员已退组（返 0 行）只 WARN 不回滚——
     * 组长扣款是既成事实，used 无处可记非致命。存量历史行由 V158 迁移回填。
     *
     * @param consumerUserId 触发消耗的成员（used 记账主体；与扣款人 leaderUserId 区分）
     */
    @Transactional(rollbackFor = Exception.class)
    public void backstop(Long groupId, Long leaderUserId, Long consumerUserId, boolean admin,
                         BigDecimal shortfall, String refType, String refId) {
        requireOwner(groupId, leaderUserId, admin);
        requirePositive(shortfall, "兜底差额必须大于0");
        // B5（Q10=A）：组长余额不足时不走 charge（内层 REQUIRED 事务加入本方法事务，抛 INSUFFICIENT 会
        // 把本事务标 rollback-only，吞不掉），预判后直接扣尽挂账——实付 min(balance, shortfall)+差额进欠款，
        // 语义与「charge 失败转挂账」等价且无事务毒化；并发残余竞态（预判后余额被抽走）由上游计费层吞。
        if (pointsWallet.getBalance(leaderUserId).compareTo(shortfall) >= 0) {
            pointsWallet.charge(leaderUserId, shortfall, PointsLedgerEntity.REF_GROUP, groupId, "组池不足·组长兜底"); // 锁①个人
        } else {
            pointsWallet.chargeToDebt(leaderUserId, shortfall, PointsLedgerEntity.REF_GROUP, groupId,
                    "组池不足·组长兜底");
            log.warn("兜底转挂账 groupId={} leader={} shortfall={}", groupId, leaderUserId, shortfall);
        }
        ProjectGroupWalletEntity w = walletMapper.selectByGroupIdForUpdate(groupId); // 锁②组池（只读锁，取一致 balance_after）
        if (w == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "组池钱包行缺失 groupId=" + groupId);
        }
        // 锁③成员行（锁序：个人→组池→成员，单向无环——与 chargeGroup 的 组池→成员 同向衔接，
        // 不构成 AB-BA）。组长侧结局（全额扣/挂 DEBT）不影响 used 计入
        if (consumerUserId != null
                && memberMapper.addUsedUnconditional(groupId, consumerUserId, shortfall) == 0) {
            log.warn("BACKSTOP 成员 used 记账落空（已退组？）groupId={} consumer={} shortfall={} ref={}:{}",
                    groupId, consumerUserId, shortfall, refType, refId);
        }
        appendLedgerRow(w.getBalancePoints(), groupId, leaderUserId,
                ProjectGroupLedgerEntity.TYPE_BACKSTOP, shortfall.negate(), refType, refId,
                "组池不足·补差兜底，差额由组长个人承担（计入成员已用）");
        // E1：组池余额未动（balance_after=现值），但 BACKSTOP 流水对全员可见——仍广播；
        // 组长个人腿的 PERSONAL 事件已由 PointsWalletService.adjust/chargeToDebt 发布
        publishGroupChanged(groupId, w.getBalancePoints(), shortfall.negate(), refType + ":" + refId);
        if (consumerUserId != null) {
            publishMemberUsed(groupId, consumerUserId, shortfall, refType + ":" + refId);
        }
        log.warn("BACKSTOP groupId={} leader={} consumer={} shortfall={} ref={}:{} —— 组池余额 {}",
                groupId, leaderUserId, consumerUserId, shortfall, refType, refId, w.getBalancePoints());
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
