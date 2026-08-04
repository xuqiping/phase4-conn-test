package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.superprogrammer.chat.entity.MemoryNotification;
import com.superprogrammer.chat.mapper.MemoryNotificationMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 计划12 · F · 跨用户波及通知读取 + ACK（总体设计 §3.8 波及通知）。
 * <p>
 * 通知由 {@code MemoryConflictResolutionService}（DISCARD/撤回波及）写入；worker 重生完成
 * UPDATE 原行置 resolved_at（不新增行，避免 badge 抖动）。本 service 只读 + ACK。
 * <p>
 * <b>权边界</b>：通知 {@code user_id} = 接收者；仅接收者可读自己通知 + ACK。他人通知 ACK → 403。
 *
 * @see MemoryNotification 波及通知实体（V47）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryNotificationService {

    private final MemoryNotificationMapper notificationMapper;

    /** 列出当前用户未处理通知（resolved_at IS NULL），按 created_at DESC。 */
    public List<MemoryNotification> listUnresolved(Long userId) {
        return notificationMapper.selectList(
                new LambdaQueryWrapper<MemoryNotification>()
                        .eq(MemoryNotification::getUserId, userId)
                        .isNull(MemoryNotification::getResolvedAt)
                        .orderByDesc(MemoryNotification::getCreatedAt));
    }

    /** 未处理通知计数（波及 badge 用，3s 轮询）。 */
    public int countUnresolved(Long userId) {
        Long n = notificationMapper.selectCount(
                new LambdaQueryWrapper<MemoryNotification>()
                        .eq(MemoryNotification::getUserId, userId)
                        .isNull(MemoryNotification::getResolvedAt));
        return n == null ? 0 : n.intValue();
    }

    /**
     * ACK 单条通知（置 resolved_at=now）。
     *
     * @param userId         当前用户（须 = 通知接收者）
     * @param notificationId 通知 id
     */
    public void ack(Long userId, Long notificationId) {
        MemoryNotification row = notificationMapper.selectById(notificationId);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "通知不存在");
        }
        if (!userId.equals(row.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权处理他人通知");
        }
        int updated = notificationMapper.update(null,
                new LambdaUpdateWrapper<MemoryNotification>()
                        .eq(MemoryNotification::getId, notificationId)
                        .isNull(MemoryNotification::getResolvedAt)
                        .set(MemoryNotification::getResolvedAt, OffsetDateTime.now()));
        log.info("notification ack userId={} id={} updated={}", userId, notificationId, updated);
    }
}
