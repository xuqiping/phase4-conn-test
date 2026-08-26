package com.superprogrammer.billing.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 积分变动事件（7x-3 · 计划 E1）：钱包写路径唯一咽喉发布，/ws/events 推送侧消费。
 *
 * <p><b>发布语义</b>：发布只是投递（本对象），实际推送由监听器在事务提交后
 * （{@code @TransactionalEventListener(AFTER_COMMIT)}）执行——DB 是真相源，推送是显示层，
 * 监听异常只 WARN 不影响计费返回值。
 *
 * <p><b>scope 口径</b>：
 * <ul>
 *   <li>{@link #SCOPE_PERSONAL}：个人钱包余额变（charge/refund/grant/充值/挂账实付全走
 *       PointsWalletService.adjust 唯一咽喉）——balanceAfter=调整后余额。</li>
 *   <li>{@link #SCOPE_GROUP}：组池余额变（allocate/reclaim/chargeGroup/refundGroup/backstop）
 *       ——发布方按组员集逐个 userId 各发一条（全员徽标/组页秒级），balanceAfter=组池余额。</li>
 *   <li>{@link #SCOPE_MEMBER}：成员 used 变（chargeGroup/refundGroup/backstop）
 *       ——供组页刷新；balanceAfter 恒 null（used 无回读，组页以 delta 或全量重查为准）。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PointsChangedEvent {

    public static final String SCOPE_PERSONAL = "PERSONAL";
    public static final String SCOPE_GROUP = "GROUP";
    public static final String SCOPE_MEMBER = "MEMBER";

    /** 接收方用户（推送按此索引 WS 连接）。 */
    private Long userId;
    /** {@link #SCOPE_PERSONAL}/{@link #SCOPE_GROUP}/{@link #SCOPE_MEMBER}。 */
    private String scope;
    /** GROUP/MEMBER 时的组 id；PERSONAL 恒 null。 */
    private Long groupId;
    /** scope 语义下的余额：PERSONAL=个人余额、GROUP=组池余额、MEMBER=null。 */
    private BigDecimal balanceAfter;
    /** 有符号变动（正=入账，负=扣减）；MEMBER=used 增减。 */
    private BigDecimal delta;
    /** 人读原因（remark 口径，透传前端提示）。 */
    private String reason;
}
