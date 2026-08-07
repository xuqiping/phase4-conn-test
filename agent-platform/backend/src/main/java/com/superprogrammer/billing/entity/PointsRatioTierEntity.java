package com.superprogrammer.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 积分计费·阶梯比例（points_ratio_tier，V66）。
 * <p>¥→积分：min&lt;=¥&lt;(max||∞) 命中，ratio=每¥换多少积分。充值与消耗共用一套。
 * <p>不继承 BaseEntity：配置行 append（同 MediaGenTask 先例）。
 */
@Data
@TableName("points_ratio_tier")
public class PointsRatioTierEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 区间下限（含）。 */
    private BigDecimal minAmount;

    /** 区间上限（不含）；null=∞。 */
    private BigDecimal maxAmount;

    /** 每 ¥ 换多少积分。 */
    private BigDecimal ratio;

    private OffsetDateTime effectiveFrom;
}
