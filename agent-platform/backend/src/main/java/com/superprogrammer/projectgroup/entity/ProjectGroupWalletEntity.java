package com.superprogrammer.projectgroup.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 组池钱包（project_group_wallets，V133）。
 * <p>单行/组（UNIQUE group_id），镜像 user_points_balance 行锁模式；
 * CHECK>=0 组池不可透支——结算差额走 BACKSTOP 扣组长。
 * <p>不继承 BaseEntity：单行 update 表无软删/乐观锁（同 V65 钱包先例）。
 */
@Data
@TableName("project_group_wallets")
public class ProjectGroupWalletEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属组（UNIQUE）。 */
    private Long groupId;

    /** 组池余额（>=0）。 */
    private BigDecimal balancePoints;

    private OffsetDateTime updatedAt;
}
