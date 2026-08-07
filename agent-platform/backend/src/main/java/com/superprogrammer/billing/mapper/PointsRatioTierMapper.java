package com.superprogrammer.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.billing.entity.PointsRatioTierEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

/**
 * 阶梯比例 Mapper。
 * <p>{@link #findTier} 命中 min&lt;=¥&lt;(max||∞) 的当前生效档。
 */
@Mapper
public interface PointsRatioTierMapper extends BaseMapper<PointsRatioTierEntity> {

    /**
     * 按 ¥ 命中阶梯档（充值与消耗共用）。
     * <p>CRUD 校验区间不重叠不漏，故当前生效集中至多一档含 yuan。
     * effective_from &lt;= now 取当前版；ORDER BY effective_from DESC 兜底重定义。
     */
    @Select("SELECT * FROM points_ratio_tier "
            + "WHERE effective_from <= NOW() "
            + "AND min_amount <= #{yuan} "
            + "AND (max_amount IS NULL OR max_amount > #{yuan}) "
            + "ORDER BY effective_from DESC "
            + "LIMIT 1")
    PointsRatioTierEntity findTier(@Param("yuan") BigDecimal yuan);
}
