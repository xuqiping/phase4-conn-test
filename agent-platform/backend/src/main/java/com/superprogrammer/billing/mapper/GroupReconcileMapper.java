package com.superprogrammer.billing.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 组池对账 Mapper（D4 · 20x-3，admin 只读）。
 * <p>恒等式口径（ProjectGroupWalletService 类注释不变量①）：组池余额 ==
 * Σ(ALLOCATE) + Σ(RECLAIM) + Σ(CONSUME) + Σ(REFUND)（RECLAIM/CONSUME delta 存负数）。
 * <p><b>type 白名单（防永久假警报，D4 坑点）</b>：只计上四类资金腿——
 * MEMBER_ALLOCATE/MEMBER_RECLAIM/MEMBER_QUOTA_ADJUST=成员额度非资金腿（不动组池）；
 * BACKSTOP=组长个人兜底（组流水有行但组池不动，delta 非零）；ADMIN_ADJUST=delta=0 留痕。
 * 任一混入等式都会永久性报不平。
 */
@Mapper
public interface GroupReconcileMapper {

    /**
     * 每组一行的原始聚合（组账本四类资金腿 + 组池余额 + 个人账本 GROUP 腿净流出）。
     * <p>行源=project_groups 活行（无钱包行 balance=0，无流水各 sum=0）；
     * 个人账本侧 points_ledger.ref_id 为 BIGINT（=groupId），直接等值 JOIN。
     * 派生 expected/diff/crossDiff 与异常过滤在 service（SQL 保持哑聚合，便于单测钉口径）。
     * <p>7x-1 下钻：groupId 非空 → 只取该组（选中组 totals=该组聚合）；null → 全量。
     */
    @Select("<script>SELECT g.id AS groupId, g.name AS groupName, "
            + "COALESCE(w.balance_points, 0) AS balance, "
            + "COALESCE(gl.alloc_sum, 0) AS allocSum, "
            + "COALESCE(gl.reclaim_sum, 0) AS reclaimSum, "
            + "COALESCE(gl.consume_sum, 0) AS consumeSum, "
            + "COALESCE(gl.refund_sum, 0) AS refundSum, "
            + "COALESCE(pl.personal_net_out, 0) AS personalNetOut "
            + "FROM project_groups g "
            + "LEFT JOIN project_group_wallets w ON w.group_id = g.id "
            + "LEFT JOIN ("
            + "  SELECT group_id, "
            + "  SUM(CASE WHEN type = 'ALLOCATE' THEN delta_points ELSE 0 END) AS alloc_sum, "
            + "  SUM(CASE WHEN type = 'RECLAIM' THEN delta_points ELSE 0 END) AS reclaim_sum, "
            + "  SUM(CASE WHEN type = 'CONSUME' THEN delta_points ELSE 0 END) AS consume_sum, "
            + "  SUM(CASE WHEN type = 'REFUND' THEN delta_points ELSE 0 END) AS refund_sum "
            + "  FROM project_group_ledger "
            + "  WHERE type IN ('ALLOCATE', 'RECLAIM', 'CONSUME', 'REFUND') "
            + "  GROUP BY group_id"
            + ") gl ON gl.group_id = g.id "
            + "LEFT JOIN ("
            + "  SELECT ref_id AS group_id, "
            + "  SUM(CASE WHEN type = 'GROUP_ALLOCATE' THEN -delta_points ELSE 0 END) "
            + "+ SUM(CASE WHEN type = 'GROUP_RECLAIM' THEN -delta_points ELSE 0 END) AS personal_net_out "
            + "  FROM points_ledger "
            + "  WHERE ref_type = 'GROUP' AND type IN ('GROUP_ALLOCATE', 'GROUP_RECLAIM') "
            + "  GROUP BY ref_id"
            + ") pl ON pl.group_id = g.id "
            + "WHERE g.deleted = 0 "
            + "<if test='groupId != null'>AND g.id = #{groupId} </if>"
            + "ORDER BY g.id ASC</script>")
    List<com.superprogrammer.billing.dto.GroupReconcileRawVO> selectGroupRawRows(@Param("groupId") Long groupId);
}
