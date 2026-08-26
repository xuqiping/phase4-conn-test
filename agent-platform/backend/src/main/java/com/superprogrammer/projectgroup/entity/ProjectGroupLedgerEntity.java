package com.superprogrammer.projectgroup.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 组池流水（project_group_ledger，V133）。
 * <p>append-only 对账：每笔落 balance_after，末行=钱包余额。
 * actor_user_id 区分谁动的钱（消耗=成员 / 划拨回收调整=组长）。
 * <p>不继承 BaseEntity：append-only 不可变，无软删/乐观锁（同 points_ledger 先例）。
 */
@Data
@TableName("project_group_ledger")
public class ProjectGroupLedgerEntity {

    /** type：组长划入。 */
    public static final String TYPE_ALLOCATE = "ALLOCATE";
    /** type：组长回收。 */
    public static final String TYPE_RECLAIM = "RECLAIM";
    /** type：成员消耗。 */
    public static final String TYPE_CONSUME = "CONSUME";
    /** type：退款（失败任务回滚）。 */
    public static final String TYPE_REFUND = "REFUND";
    /** type：限额重置留痕（delta=0 记前后值）。 */
    public static final String TYPE_ADMIN_ADJUST = "ADMIN_ADJUST";
    /** type：组池不足组长兜底差额。 */
    public static final String TYPE_BACKSTOP = "BACKSTOP";
    /** type：成员额度授予（配额落行/调增；delta=授予额，20x-2 毛额口径）。非资金腿，不进组池对账等式。 */
    public static final String TYPE_MEMBER_ALLOCATE = "MEMBER_ALLOCATE";
    /** type：成员额度收回（调减/降职缩额；delta=收回额，净额=毛额−Σ此）。非资金腿。 */
    public static final String TYPE_MEMBER_RECLAIM = "MEMBER_RECLAIM";
    /** type：成员限额边界留痕（限额↔不限互转；delta=0 记前后值，不进毛额/净额聚合）。 */
    public static final String TYPE_MEMBER_QUOTA_ADJUST = "MEMBER_QUOTA_ADJUST";
    /** type：个人划拨入组（V161 修复III：还款后余款进名下；delta=划拨总额，组池仅回购池垫部分才变）。 */
    public static final String TYPE_SELF_ALLOCATE = "SELF_ALLOCATE";
    /** type：消耗扣名下余额（瀑布第②腿；delta=扣减额，不动组池）。 */
    public static final String TYPE_SELF_CONSUME = "SELF_CONSUME";
    /** type：名下退款/退组退回个人钱包（delta=退回额，不动组池）。 */
    public static final String TYPE_SELF_REFUND = "SELF_REFUND";
    /** type：还款（先组长垫后退组池垫；delta=回组池部分，组长腿 delta=0 备注写金额）。 */
    public static final String TYPE_SELF_REPAY = "SELF_REPAY";
    /** type：欠款核销/调限额豁免（债清无资金流动；delta=0 备注写核销额）。 */
    public static final String TYPE_DEBT_WRITEOFF = "DEBT_WRITEOFF";

    /** ref_type：对话。 */
    public static final String REF_CHAT = "CHAT";
    /** ref_type：向量嵌入。 */
    public static final String REF_EMBED = "EMBED";
    /** ref_type：视频生成。 */
    public static final String REF_VIDEO = "VIDEO";
    /** ref_type：图片生成。 */
    public static final String REF_IMAGE = "IMAGE";
    /** ref_type：媒体任务（图/视频统一）。 */
    public static final String REF_MEDIA = "MEDIA";
    /** ref_type：组操作（划拨/回收/调整）。 */
    public static final String REF_GROUP = "GROUP";
    /** ref_type：管理员操作。 */
    public static final String REF_ADMIN = "ADMIN";
    /** ref_type：成员额度操作（MEMBER_* 类流水，ref_id=成员 userId）。 */
    public static final String REF_MEMBER = "MEMBER";

    @TableId(type = IdType.AUTO)
    private Long id;

    private OffsetDateTime createdAt;

    private Long groupId;

    /** 动钱人：消耗=成员；划拨/回收/调整/兜底=组长。 */
    private Long actorUserId;

    /** ALLOCATE/RECLAIM/CONSUME/REFUND/ADMIN_ADJUST/BACKSTOP。 */
    private String type;

    /** 正=入组池（划拨/退款），负=出（消耗/回收）。 */
    private BigDecimal deltaPoints;

    /** 本笔后组池余额（对账基准）。 */
    private BigDecimal balanceAfter;

    /** CHAT/EMBED/VIDEO/IMAGE/MEDIA/GROUP/ADMIN。 */
    private String refType;

    /** 关联 id（usage_log id / media task id / groupId）。 */
    private String refId;

    private String remark;
}
