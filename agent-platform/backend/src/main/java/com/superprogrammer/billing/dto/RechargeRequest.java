package com.superprogrammer.billing.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * admin 充值/发放请求（Chunk H Step 15）。MVP：admin 直接填到账积分，不走阶梯折算
 * （阶梯折算属 Phase2 支付回调链路）。
 * <p>权限 {@code points:recharge}（仅 admin 默认有）。
 * <p>安全体系 S1 · SEC-FR-122：范围注解挡负数/零/超上限（上限 1 亿积分，防误填天文数字）。
 */
@Data
public class RechargeRequest {

    /** 充值目标用户 id。 */
    @NotNull(message = "userId 不能为空")
    private Long userId;

    /** 到账积分（正数，≤1 亿）。 */
    @NotNull(message = "points 不能为空")
    @DecimalMin(value = "0.01", message = "points 必须大于 0")
    @DecimalMax(value = "100000000", message = "points 超出单次充值上限(1 亿)")
    private BigDecimal points;

    /** 备注（落 ledger.remark，可空）。 */
    private String remark;

    /**
     * 幂等键（可空，安全体系 S1 · SEC-FR-121）：admin 重复提交/网络重试同键只充一次，
     * 返回首次结果。空则退化为普通充值。
     */
    private String idempotencyKey;
}
