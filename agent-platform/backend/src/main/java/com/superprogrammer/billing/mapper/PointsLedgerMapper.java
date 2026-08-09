package com.superprogrammer.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.billing.dto.ReconcileDiffVO;
import com.superprogrammer.billing.entity.PointsLedgerEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 积分流水 Mapper（append-only，写走 BaseMapper.insert；查询在 service 用 LambdaQueryWrapper）。
 */
@Mapper
public interface PointsLedgerMapper extends BaseMapper<PointsLedgerEntity> {

    /**
     * 安全体系 S1 · SEC-FR-123 对账：余额 vs Σ流水 的全体差异行。
     * <p>FULL OUTER JOIN 同时抓两边孤儿：有余额无流水 / 有流水无余额。
     * 只返回不平行（diff ≠ 0）；空结果 = 全平。
     */
    @Select("SELECT COALESCE(b.user_id, l.user_id) AS user_id, "
            + "COALESCE(b.balance_points, 0) AS balance_points, "
            + "COALESCE(l.sum_delta, 0) AS ledger_sum, "
            + "COALESCE(b.balance_points, 0) - COALESCE(l.sum_delta, 0) AS diff_points "
            + "FROM user_points_balance b "
            + "FULL OUTER JOIN (SELECT user_id, SUM(delta_points) AS sum_delta "
            + "                 FROM points_ledger GROUP BY user_id) l ON l.user_id = b.user_id "
            + "WHERE COALESCE(b.balance_points, 0) <> COALESCE(l.sum_delta, 0)")
    List<ReconcileDiffVO> findBalanceDiffs();
}

