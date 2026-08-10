package com.superprogrammer.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 幂等键去重表（idempotency_keys，V80 · 安全体系 S1 SEC-FR-121）。
 * <p>关键写接口（积分扣/退/充）先占位：INSERT ... ON CONFLICT DO NOTHING——
 * 占位成功=首次执行；撞键=重复提交，回查 result_ref（首次流水 id）返回相同结果。
 * 占位与业务写同事务，失败整体回滚不留死键。
 */
@Data
@TableName("idempotency_keys")
public class IdempotencyKeyEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 幂等键（调用方生成，全局唯一）。 */
    private String idemKey;

    private Long userId;

    /** 作用域：billing.charge / billing.refund / billing.grant。 */
    private String scope;

    /** 首次生效的业务引用（points_ledger.id），撞键回查用。 */
    private String resultRef;

    private OffsetDateTime createdAt;
}
