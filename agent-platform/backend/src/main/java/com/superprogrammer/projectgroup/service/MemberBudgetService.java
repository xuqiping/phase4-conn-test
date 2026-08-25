package com.superprogrammer.projectgroup.service;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 成员层级额度预算计算（17x 未解决#1，V156）。
 *
 * <p><b>模型</b>：组池是钱，额度（quota）是预算帽子。组长给管理配额度 Q，管理再给成员配额度——
 * 形成 组长→管理→成员 两级预算树（成员行 allocated_by_user_id 记预算归属上级）。
 *
 * <p><b>可分配公式</b>（用户拍板口径，例：管理 5000，分给 A 1000，A 用 500 → 管理总额度剩 4500，可分配 4000）：
 * <pre>
 *   子树已耗 S   = 管理自己 used + Σ 下级 used
 *   下级预留 Res = Σ 下级 GREATEST(quota − used, 0)
 *   剩余总额度 R = Q − S
 *   可分配 A     = R − Res
 * </pre>
 *
 * <p><b>不变量维护</b>：下级消耗=预留转已用 1:1（A 不变）；退款/重置对称回滚（A 不变或增大）。
 * 只有两类操作扣 A：管理本人消耗（chargeGroup 硬卡）与管理给成员配额度（updateQuota/邀请接受落行）。
 * 这两类操作全部先持管理行 {@code SELECT ... FOR UPDATE} 再算 A——并发双分配/边花边分串行化。
 *
 * <p><b>防御口径</b>：被限额管理下不应存在「限额为空」的下级（无限预留无法入账）——
 * 出现时 allocatable 按 0 兜底（只进不出），组长收编（改挂/补限额）后恢复。
 */
@Service
@RequiredArgsConstructor
public class MemberBudgetService {

    private final ProjectGroupMemberMapper memberMapper;

    /**
     * 管理可分配额度。quota NULL（不限）→ 返回 null（无预算概念，分配不卡）。
     *
     * @param mgrRow            管理成员行（调用方负责持锁语境：扣减类操作须 FOR UPDATE 读入）
     * @param excludeChildUserId 计算时排除的下级（正被收编/改派的目标行；无排除传 null）
     * @return 可分配额度（可能为 0；quota NULL 返 null）
     */
    public BigDecimal allocatable(Long groupId, ProjectGroupMemberEntity mgrRow, Long excludeChildUserId) {
        if (mgrRow.getQuotaLimitPoints() == null) {
            return null;
        }
        if (memberMapper.countChildUnbounded(groupId, mgrRow.getUserId(), excludeChildUserId) > 0) {
            return BigDecimal.ZERO;
        }
        return mgrRow.getQuotaLimitPoints()
                .subtract(subtreeUsed(groupId, mgrRow))
                .subtract(memberMapper.sumChildReserved(groupId, mgrRow.getUserId(), excludeChildUserId));
    }

    /** 子树已耗 = 管理自己 used + Σ 下级 used。 */
    public BigDecimal subtreeUsed(Long groupId, ProjectGroupMemberEntity mgrRow) {
        BigDecimal own = mgrRow.getUsedPoints() == null ? BigDecimal.ZERO : mgrRow.getUsedPoints();
        return own.add(memberMapper.sumChildUsed(groupId, mgrRow.getUserId()));
    }

    /** 管理预算已被占用量（组长给管理定/改额度时下限校验用）= 子树已耗 + Σ 下级预留。 */
    public BigDecimal occupied(Long groupId, ProjectGroupMemberEntity mgrRow, Long excludeChildUserId) {
        return subtreeUsed(groupId, mgrRow)
                .add(memberMapper.sumChildReserved(groupId, mgrRow.getUserId(), excludeChildUserId));
    }

    /** 管理下是否存在「限额为空」的下级（异常态，组长给管理定额度前须收编）。 */
    public boolean hasUnboundedChild(Long groupId, Long managerUserId, Long excludeChildUserId) {
        return memberMapper.countChildUnbounded(groupId, managerUserId, excludeChildUserId) > 0;
    }

    /**
     * 管理给成员配额度硬卡（调用方已持管理行 FOR UPDATE）：新预留 ≤ 可分配。
     * 可分配按「排除目标行」口径计算（allocatable 的 exclude 参数），目标旧预留天然不在其中——
     * 故调整既有下级时无需回填旧预留，直接比新预留即可。
     * 管理自己不限额（quota NULL）直接放行。
     *
     * @param target 目标成员行（改派前状态：旧 quota/used）
     * @param newQuota 新限额（被限额管理不许配 NULL——调用方先拦）
     */
    public void requireWithinBudget(Long groupId, ProjectGroupMemberEntity mgrRow,
                                    ProjectGroupMemberEntity target, BigDecimal newQuota) {
        if (mgrRow.getQuotaLimitPoints() == null) {
            return;
        }
        BigDecimal available = allocatable(groupId, mgrRow, target.getUserId());
        BigDecimal newReserved = reservedOf(newQuota, target.getUsedPoints());
        if (newReserved.compareTo(available) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "超出你的可分配额度：剩余可分配 " + available + "，本次需要预留 " + newReserved);
        }
    }

    /** 下级预留 = GREATEST(quota − used, 0)。 */
    public BigDecimal reservedOf(BigDecimal quota, BigDecimal used) {
        if (quota == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal r = quota.subtract(used == null ? BigDecimal.ZERO : used);
        return r.signum() < 0 ? BigDecimal.ZERO : r;
    }
}
