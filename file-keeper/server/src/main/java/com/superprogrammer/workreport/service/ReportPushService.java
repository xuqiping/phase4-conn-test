package com.superprogrammer.workreport.service;

/**
 * 报告推送统一入口接口。
 * Step 4 先提供 no-op 实现，Step 5 提供真实异步推送实现。
 */
public interface ReportPushService {

    void pushReport(Long reportId);

    void pushDelivery(Long deliveryId);
}
