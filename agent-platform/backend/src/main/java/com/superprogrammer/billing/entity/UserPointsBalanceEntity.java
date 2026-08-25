package com.superprogrammer.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 积分计费·钱包余额（user_points_balance，V65 建 / V92 改不可负 / V157 加欠款列）。
 * <p>单行/用户（user_id UNIQUE）。balance_points ≥ 0（DB CHECK 兜底）——不可负：
 * 消耗前有预扣/预检，没拦住的缺口走 {@link #debtPoints} 挂账（V157 修正了 V65 时期「可负=欠款」的旧口径，
 * 余额与欠款分离后钱包页可直接展示「余额 X / 欠款 Y」）。
 * <p>不继承 BaseEntity：单行 update 非 append-only，无软删/乐观锁需求（同 MediaGenTask 先例）。
 */
@Data
@TableName("user_points_balance")
public class UserPointsBalanceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 积分余额，≥0（DB CHECK chk_balance_non_negative 兜底）。 */
    private BigDecimal balancePoints;

    /** 欠款积分（V157 DEBT 兜底）：余额扣尽后的未付差额；&gt;0 拦截全部消费入口，充值/发放自动优先冲抵。 */
    private BigDecimal debtPoints;

    private OffsetDateTime updatedAt;
}
