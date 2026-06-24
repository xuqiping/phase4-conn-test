package com.superprogrammer.workreport.service;

import com.superprogrammer.workreport.entity.PushDelivery;
import com.superprogrammer.workreport.repository.PushDeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PushRetryService {

    private final PushDeliveryRepository pushDeliveryRepository;
    private final ReportPushService reportPushService;

    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void retryFailedDeliveries() {
        log.info("开始扫描失败推送记录...");
        List<PushDelivery> failed = pushDeliveryRepository.findFailedWithin(
                LocalDateTime.now().minusHours(24)
        );

        for (PushDelivery delivery : failed) {
            if (delivery.getTriedCount() != null && delivery.getTriedCount() >= 3) {
                continue;
            }
            try {
                reportPushService.pushDelivery(delivery.getId());
            } catch (Exception e) {
                log.error("重试推送失败: deliveryId={}", delivery.getId(), e);
            }
        }
    }
}
