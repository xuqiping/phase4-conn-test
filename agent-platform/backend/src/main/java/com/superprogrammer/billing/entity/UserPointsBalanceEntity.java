package com.superprogrammer.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 积分计费·钱包余额（user_points_balance，V65）。
 * <p>单行/用户（user_id UNIQUE）。balance_points 可负=欠款（预检&gt;0 放行 + 后扣实际）。
 * <p>不继承 BaseEntity：单行 update 非 append-only，无软删/乐观锁需求（同 MediaGenTask 先例）。
 */
@Data
@TableName("user_points_balance")
public class UserPointsBalanceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 积分余额，可负：扣到负后下次预检拦。 */
    private BigDecimal balancePoints;

    private OffsetDateTime updatedAt;
}
