package com.superprogrammer.billing.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.websocket.EventsWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 积分变动推送监听（7x-3 · 计划 E3）：PointsChangedEvent → /ws/events JSON 下行。
 *
 * <p><b>AFTER_COMMIT</b>：事务提交后才推——回滚的扣款不发（前端不会看到未落账的余额），
 * 也天然规避「事件先到、前端查到旧值」竞态。
 *
 * <p><b>@Async</b>：推送在监听线程执行，发布点（计费事务内）只是投递——推送慢/异常
 * 绝不拖垮扣费主链。异常 WARN + 丢弃计数（仿 UsageCollector 风格，stats 暴露给运维日志）；
 * 单事件丢弃无碍——DB 是真相源，用户下次变动/刷新即校正。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointsChangedPushListener {

    private final EventsWebSocketHandler handler;
    private final ObjectMapper objectMapper;

    private final AtomicLong pushedCount = new AtomicLong();
    private final AtomicLong droppedCount = new AtomicLong();

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEvent(PointsChangedEvent event) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("type", "points.changed");
            out.put("scope", event.getScope());
            out.put("groupId", event.getGroupId());
            out.put("balanceAfter", event.getBalanceAfter());
            out.put("delta", event.getDelta());
            out.put("reason", event.getReason());
            out.put("ts", System.currentTimeMillis());
            handler.push(event.getUserId(), objectMapper.writeValueAsString(out));
            pushedCount.incrementAndGet();
        } catch (Exception e) {
            droppedCount.incrementAndGet();
            log.warn("积分变动推送失败(丢弃) userId={} scope={} delta={}: {}",
                    event.getUserId(), event.getScope(), event.getDelta(), e.toString());
        }
    }

    /** 运维观测：监听侧累计推送/丢弃（含用户离线跳过——handler 内不区分，此处不抛不丢）。 */
    public Map<String, Long> stats() {
        return Map.of("pushed", pushedCount.get(), "dropped", droppedCount.get());
    }
}
