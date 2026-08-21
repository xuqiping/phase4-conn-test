package com.superprogrammer.billing.dto;

import java.util.List;

/**
 * 支付渠道异常三节（对账扩展，7x#3）：
 * <ul>
 *   <li>stalePending：PENDING 超 10min 未关——过期 job 停滞/时钟异常线索；</li>
 *   <li>paidNoLedger：PAID 但无 RECHARGE 流水——入账半截（抢态成功入账失败回滚不应出现，出现=脏数据）；</li>
 *   <li>closedButPaid：CLOSED/FAILED 后渠道仍推来真实付款（audit payment_notify_terminal_order）——人工补单。</li>
 * </ul>
 */
public record PaymentAnomalyVO(List<PaymentAnomalyRowVO> stalePending,
                               List<PaymentAnomalyRowVO> paidNoLedger,
                               List<PaymentAnomalyRowVO> closedButPaid) {
}
