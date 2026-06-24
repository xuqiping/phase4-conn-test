package com.superprogrammer.workreport.service;

import com.superprogrammer.workreport.entity.PushDelivery;
import com.superprogrammer.workreport.repository.PushDeliveryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PushDeliveryService {

    private final PushDeliveryRepository pushDeliveryRepository;

    @Transactional
    public void record(Long reportId, Long targetId, boolean success, String response, int triedCount) {
        PushDelivery delivery = new PushDelivery();
        delivery.setReportId(reportId);
        delivery.setTargetId(targetId);
        delivery.setStatus(success ? "SUCCESS" : "FAILED");
        delivery.setResponse(response);
        delivery.setTriedCount(triedCount);
        delivery.setCreatedBy(0L);
        delivery.setUpdatedBy(0L);
        pushDeliveryRepository.insert(delivery);
    }

    @Transactional
    public void recordRetry(Long deliveryId, boolean success, String response, int triedCount) {
        PushDelivery delivery = pushDeliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new RuntimeException("推送记录不存在: " + deliveryId));
        delivery.setStatus(success ? "SUCCESS" : "FAILED");
        delivery.setResponse(response);
        delivery.setTriedCount(triedCount);
        delivery.setUpdatedBy(0L);
        pushDeliveryRepository.update(delivery);
    }
}
