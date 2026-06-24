package com.superprogrammer.workreport.service;

import com.superprogrammer.workreport.entity.ReminderDelivery;
import com.superprogrammer.workreport.repository.ReminderDeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReminderRetryService {

    private final ReminderDeliveryRepository reminderDeliveryRepository;
    private final ReminderPushService reminderPushService;

    @Scheduled(fixedDelay = 5 * 60 * 1000)
    @Transactional
    public void retryFailedDeliveries() {
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        List<ReminderDelivery> failed = reminderDeliveryRepository.findFailedWithin(since);
        for (ReminderDelivery delivery : failed) {
            try {
                reminderPushService.retryDelivery(delivery);
            } catch (Exception e) {
                log.error("提醒重试异常 deliveryId={}", delivery.getId(), e);
            }
        }
    }
}
