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
 * 两序无交叉环：chargeGroup 只碰 组池→成员，双向操作只碰 个人→组池，并发混跑无死锁。
 *
 * <p><b>对账不变量（V133 运维模板）</b>：①末行 ledger.balance_after == wallets.balance_points
 * （BACKSTOP 不动组池，行锁读保证一致）；②成员 Σ(CONSUME−REFUND) == used_points
 * （BACKSTOP 不计 member.used——差额由组长个人承担，非成员配额内消耗）。
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
        if (memberMapper.addUsed(groupId, memberUserId, cost) == 0) {               // 锁②成员行（quota 守卫）
            throw new BusinessException(ErrorCode.BAD_REQUEST, "超出组长配置的成员限额");
        }
        ProjectGroupWalletEntity w = requireWallet(groupId);                        // 行已被本事务 UPDATE 锁定
        appendLedgerRow(w.getBalancePoints(), groupId, memberUserId,
                ProjectGroupLedgerEntity.TYPE_CONSUME, cost.negate(), refType, refId, null);
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
        log.info("组退款 groupId={} member={} points={} ref={}:{}", groupId, memberUserId, points, refType, refId);
        return w.getBalancePoints();
    }

    /**
     * BACKSTOP（结算兜底）：组池不足的差额扣组长<b>个人</b>，组池不动（保 CHECK>=0）。
     * 两账本各记一行：个人 CONSUME(ref=GROUP) + 组流水 BACKSTOP(delta=-差额, balance_after=组池现值)。
     * 对账口径：BACKSTOP 不进组池余额重建、不计成员 used（见类注释不变量②）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void backstop(Long groupId, Long leaderUserId, boolean admin, BigDecimal shortfall,
                         String refType, String refId) {
        requireOwner(groupId, leaderUserId, admin);
        requirePositive(shortfall, "兜底差额必须大于0");
        pointsWallet.charge(leaderUserId, shortfall, PointsLedgerEntity.REF_GROUP, groupId, "组池不足·组长兜底"); // 锁①个人
        ProjectGroupWalletEntity w = walletMapper.selectByGroupIdForUpdate(groupId); // 锁②组池（只读锁，取一致 balance_after）
        if (w == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "组池钱包行缺失 groupId=" + groupId);
        }
        appendLedgerRow(w.getBalancePoints(), groupId, leaderUserId,
                ProjectGroupLedgerEntity.TYPE_BACKSTOP, shortfall.negate(), refType, refId, "组池不足·组长兜底");
        log.warn("BACKSTOP groupId={} leader={} shortfall={} ref={}:{} —— 组池余额 {}",
                groupId, leaderUserId, shortfall, refType, refId, w.getBalancePoints());
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
