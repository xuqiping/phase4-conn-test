package com.superprogrammer.feedback.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.superprogrammer.common.result.PageResult;
import com.superprogrammer.feedback.entity.FeedbackNotificationEntity;
import com.superprogrammer.feedback.mapper.FeedbackNotificationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 反馈站内通知（19x 铃铛三件套：count/list/read）。
 * <p>发送时机由调用方（审核/回答抢态成功后同事务 insert）保证「同一审核只发一次」——
 * 抢态条件 UPDATE 天然幂等，无需通知侧再去重。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackNotificationService {

    /** 通知列表每页上限。 */
    static final int LIST_MAX_SIZE = 50;

    private final FeedbackNotificationMapper notificationMapper;

    /** 发通知（message 纯文本；调用方负责只在状态翻转成功时调）。 */
    public void notify(Long userId, String type, Long refId, String message) {
        FeedbackNotificationEntity e = new FeedbackNotificationEntity();
        e.setUserId(userId);
        e.setType(type);
        e.setRefId(refId);
        e.setMessage(message.length() > 500 ? message.substring(0, 500) : message);
        notificationMapper.insert(e);
    }

    /** 铃铛未读数（部分索引）。 */
    public long countUnread(Long userId) {
        return notificationMapper.countUnread(userId);
    }

    /** 我的通知分页（新→旧）。 */
    public PageResult<FeedbackNotificationEntity> myNotifications(Long userId, int page, int size) {
        int capped = Math.min(Math.max(size, 1), LIST_MAX_SIZE);
        Page<FeedbackNotificationEntity> p = notificationMapper.selectPage(
                new Page<>(Math.max(page, 1), capped),
                Wrappers.<FeedbackNotificationEntity>lambdaQuery()
                        .eq(FeedbackNotificationEntity::getUserId, userId)
                        .orderByDesc(FeedbackNotificationEntity::getId));
        return PageResult.of(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize());
    }

    /** 标记已读（幂等；非本人行 0=静默）。 */
    public void markRead(Long userId, Long id) {
        notificationMapper.markRead(id, userId);
    }

    /** 全部已读。返回条数。 */
    public int markAllRead(Long userId) {
        return notificationMapper.markAllRead(userId);
    }
}
