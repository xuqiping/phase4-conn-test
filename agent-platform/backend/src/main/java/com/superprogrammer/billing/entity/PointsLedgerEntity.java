package com.superprogrammer.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 积分计费·流水（points_ledger，V65）。
 * <p>append-only 对账：每笔落 balance_after，可正向重建。
 * <p>不继承 BaseEntity：append-only 不可变，无软删/乐观锁（同 MediaGenTask 先例）。
 */
@Data
@TableName("points_ledger")
public class PointsLedgerEntity {

    /** type：充值。 */
    public static final String TYPE_RECHARGE = "RECHARGE";
    /** type：消耗。 */
    public static final String TYPE_CONSUME = "CONSUME";
    /** type：退款（消耗后调用链抛/失败，逆向回涨）。 */
    public static final String TYPE_REFUND = "REFUND";
    /** type：管理员发放（MVP 充值入口）。 */
    public static final String TYPE_ADMIN_GRANT = "ADMIN_GRANT";
    /** type：划入项目组池（计划5 V133，ref=GROUP/groupId，负 delta）。 */
    public static final String TYPE_GROUP_ALLOCATE = "GROUP_ALLOCATE";
    /** type：从项目组池回收（正 delta）。 */
    public static final String TYPE_GROUP_RECLAIM = "GROUP_RECLAIM";

    /** ref_type：对话。 */
    public static final String REF_CHAT = "CHAT";
    /** ref_type：向量嵌入。 */
    public static final String REF_EMBED = "EMBED";
    /** ref_type：视频生成。 */
    public static final String REF_VIDEO = "VIDEO";
    /** ref_type：图片生成。 */
    public static final String REF_IMAGE = "IMAGE";
    /** ref_type：支付订单。 */
    public static final String REF_PAYMENT = "PAYMENT";
    /** ref_type：管理员操作。 */
    public static final String REF_ADMIN = "ADMIN";
    /** ref_type：项目组划拨/回收（ref_id=project_groups.id；V151 起不参与 uq_ledger_ref 唯一锚——划拨无天然幂等键，一次操作一笔流水）。 */
    public static final String REF_GROUP = "GROUP";

    @TableId(type = IdType.AUTO)
    private Long id;

    private OffsetDateTime createdAt;

    private Long userId;

    /** RECHARGE/CONSUME/REFUND/ADMIN_GRANT。 */
    private String type;

    /** 正=入账（充值/退款/发放），负=扣减。 */
    private BigDecimal deltaPoints;

    /** 对应金额（消耗=cost_yuan，充值=amount_yuan）。 */
    private BigDecimal moneyYuan;

    /** CHAT/EMBED/VIDEO/IMAGE/PAYMENT/ADMIN。 */
    private String refType;

    /** 关联 id（usage_log id / payment_order id / task id）。 */
    private Long refId;

    /** 本笔后余额（对账基准）。 */
    private BigDecimal balanceAfter;

    private String remark;
}
