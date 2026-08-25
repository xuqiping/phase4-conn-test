package com.superprogrammer.feedback.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.feedback.dto.FeedbackMessageVO;
import com.superprogrammer.feedback.entity.FeedbackMessageEntity;
import com.superprogrammer.feedback.entity.FeedbackNotificationEntity;
import com.superprogrammer.feedback.entity.FeedbackQuestionEntity;
import com.superprogrammer.feedback.entity.FeedbackSuggestionEntity;
import com.superprogrammer.feedback.mapper.FeedbackMessageMapper;
import com.superprogrammer.feedback.mapper.FeedbackQuestionMapper;
import com.superprogrammer.feedback.mapper.FeedbackSuggestionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 反馈留言（19x 未解决#1：审核后 admin 可继续给用户留言，每次留言用户都收通知）。
 *
 * <p>规则：
 * <ul>
 *   <li>目标存在性按类型校验（建议/提问两表，应用层查）；留言即插线程尾部（append-only）。</li>
 *   <li>每条 admin 留言都发站内通知（SUGGESTION_MESSAGE / QUESTION_MESSAGE）——
 *       用户明确要求「每次重新留言都要接收到」，与审核/首答的「只发一次」语义不同。</li>
 *   <li>读取：属主或 admin（feedback:manage）可见；他人目标 404 不泄露存在性。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackMessageService {

    private final FeedbackMessageMapper messageMapper;
    private final FeedbackSuggestionMapper suggestionMapper;
    private final FeedbackQuestionMapper questionMapper;
    private final FeedbackNotificationService notificationService;

    /**
     * admin 留言：校验目标 → 插线程 → 通知目标属主（同事务）。
     *
     * @return 留言 id
     */
    @Transactional(rollbackFor = Exception.class)
    public Long addAdminMessage(String targetType, Long targetId, String content, Long adminId) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "留言内容不能为空");
        }
        TargetOwner target = resolveTarget(targetType, targetId);

        FeedbackMessageEntity m = new FeedbackMessageEntity();
        m.setTargetType(targetType);
        m.setTargetId(targetId);
        m.setSenderId(adminId);
        m.setSenderRole(FeedbackMessageEntity.ROLE_ADMIN);
        m.setContent(trimmed);
        messageMapper.insert(m);

        boolean isSuggestion = FeedbackMessageEntity.TARGET_SUGGESTION.equals(targetType);
        notificationService.notify(target.ownerId(),
                isSuggestion ? FeedbackNotificationEntity.TYPE_SUGGESTION_MESSAGE
                        : FeedbackNotificationEntity.TYPE_QUESTION_MESSAGE,
                targetId,
                "您的" + (isSuggestion ? "建议" : "提问") + "「" + abbrev(target.title()) + "」有新留言：" + abbrev(trimmed));
        log.info("反馈留言: target={}#{} adminId={} msgId={}", targetType, targetId, adminId, m.getId());
        return m.getId();
    }

    /**
     * 读线程（正序）。属主或 admin 可见；他人目标 404（不泄露存在性）。
     */
    public List<FeedbackMessageVO> listMessages(String targetType, Long targetId, Long userId, boolean admin) {
        TargetOwner target = resolveTarget(targetType, targetId);
        if (!admin && !target.ownerId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "内容不存在");
        }
        return messageMapper.selectList(new LambdaQueryWrapper<FeedbackMessageEntity>()
                        .eq(FeedbackMessageEntity::getTargetType, targetType)
                        .eq(FeedbackMessageEntity::getTargetId, targetId)
                        .orderByAsc(FeedbackMessageEntity::getId))
                .stream()
                .map(m -> new FeedbackMessageVO(m.getId(), m.getSenderRole(), m.getContent(), m.getCreatedAt()))
                .toList();
    }

    // ==================== 内部 ====================

    private record TargetOwner(Long ownerId, String title) {
    }

    /** 目标存在性校验 + 属主/标题取出（通知与归属判定共用）。未知类型 400、不存在 404。 */
    private TargetOwner resolveTarget(String targetType, Long targetId) {
        if (FeedbackMessageEntity.TARGET_SUGGESTION.equals(targetType)) {
            FeedbackSuggestionEntity s = suggestionMapper.selectById(targetId);
            if (s == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "建议不存在");
            }
            return new TargetOwner(s.getUserId(), s.getTitle());
        }
        if (FeedbackMessageEntity.TARGET_QUESTION.equals(targetType)) {
            FeedbackQuestionEntity q = questionMapper.selectById(targetId);
            if (q == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "提问不存在");
            }
            return new TargetOwner(q.getUserId(), q.getTitle());
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "未知留言目标类型");
    }

    private static String abbrev(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= 40 ? t : t.substring(0, 40) + "…";
    }
}
