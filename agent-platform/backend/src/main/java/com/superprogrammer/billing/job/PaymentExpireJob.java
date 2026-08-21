package com.superprogrammer.billing.job;

import com.superprogrammer.billing.service.PaymentOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 充值订单过期关单 job（7x#3）：每分钟扫 PENDING 且 expire_at 已过的单，批量条件 UPDATE CLOSED。
 *
 * <p>单批 200（走 idx_payment_pending_expire 部分索引，<1s）；与回调抢态互斥——
 * 用户最后一刻付款 vs 过期关单，条件 UPDATE 谁先谁赢，输家走「已付已关单」对账异常人工补单。
 * job 异常下轮重扫（幂等关单，重扫无副作用）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentExpireJob {

    private static final int BATCH_LIMIT = 200;

    private final PaymentOrderService paymentOrderService;

    @Scheduled(fixedDelayString = "${billing.payment.expire-job-delay-ms:60000}", initialDelay = 30000)
    public void closeExpired() {
        try {
            int closed = paymentOrderService.expireBatch(BATCH_LIMIT);
            if (closed > 0) {
                log.info("充值过期关单批次完成: closed={}", closed);
            }
        } catch (Exception e) {
            // 不抛：下轮重扫（关单幂等）；打 ERROR 供告警捞
            log.error("充值过期关单批次失败(下轮重扫): {}", e.toString());
        }
    }
}
