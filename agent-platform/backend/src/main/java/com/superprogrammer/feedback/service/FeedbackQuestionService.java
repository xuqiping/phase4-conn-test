package com.superprogrammer.feedback.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.result.PageResult;
import com.superprogrammer.feedback.dto.AdminQuestionVO;
import com.superprogrammer.feedback.dto.CreateQuestionRequest;
import com.superprogrammer.feedback.dto.FaqVO;
import com.superprogrammer.feedback.dto.QuestionVO;
import com.superprogrammer.feedback.entity.FeedbackNotificationEntity;
import com.superprogrammer.feedback.entity.FeedbackQuestionEntity;
import com.superprogrammer.feedback.mapper.FeedbackQuestionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 提问台（19x#2）。
 *
 * <p><b>回答通知语义</b>：OPEN→ANSWERED 首答发 QUESTION_ANSWERED 通知；
 * ANSWERED→ANSWERED 改答案<strong>不重发</strong>（防骚扰）——由翻转前旧状态判定。
 *
 * <p><b>FAQ 脱敏</b>：公开检索 SQL 不 SELECT username/user_id，VO 字段不存在（非置空）。
 * 取消公开 → FAQ 消失但「我的提问」仍可见答案。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackQuestionService {

    /** 列表每页上限。 */
    static final int LIST_MAX_SIZE = 50;

    private final FeedbackQuestionMapper questionMapper;
    private final UserMapper userMapper;
    private final FeedbackNotificationService notificationService;

    // ==================== 用户侧 ====================

    /** 提问：username 快照 → OPEN 落库。返回新单 id。 */
    public Long submitQuestion(Long userId, CreateQuestionRequest req) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号状态异常");
        }
        FeedbackQuestionEntity e = new FeedbackQuestionEntity();
        e.setUserId(userId);
        e.setUsername(user.getUsername());
        e.setTitle(req.title().trim());
        e.setContent(req.content());
        e.setStatus(FeedbackQuestionEntity.STATUS_OPEN);
        e.setIsPublic(false);
        questionMapper.insert(e);
        log.info("提问提交: id={} userId={} title={}", e.getId(), userId, abbrev(e.getTitle()));
        return e.getId();
    }

    /** 我的提问分页（强制 self；含答案，markdown 原文由前端 renderMarkdown 渲染）。 */
    public PageResult<QuestionVO> myQuestions(Long userId, int page, int size) {
        int capped = Math.min(Math.max(size, 1), LIST_MAX_SIZE);
        Page<FeedbackQuestionEntity> p = questionMapper.selectPage(
                new Page<>(Math.max(page, 1), capped),
                Wrappers.<FeedbackQuestionEntity>lambdaQuery()
                        .eq(FeedbackQuestionEntity::getUserId, userId)
                        .orderByDesc(FeedbackQuestionEntity::getId));
        return PageResult.of(p.getRecords().stream().map(FeedbackQuestionService::toVo).toList(),
                p.getTotal(), p.getCurrent(), p.getSize());
    }

    /** FAQ 公开检索（无权限要求；VO 无 username——脱敏字段不存在层）。kw=标题/内容 LIKE 前缀。 */
    public PageResult<FaqVO> faq(String kw, int page, int size) {
        int capped = Math.min(Math.max(size, 1), LIST_MAX_SIZE);
        long pg = Math.max(page, 1);
        String k = kw == null ? null : kw.trim();
        long total = questionMapper.countFaq(k);
        List<FaqVO> records = total == 0 ? List.of() : questionMapper.pageFaq(k, (pg - 1) * capped, capped);
        return PageResult.of(records, total, pg, capped);
    }

    // ==================== admin ====================

    /** admin 提问分页（筛状态；带 username 快照）。 */
    public PageResult<AdminQuestionVO> adminQuestions(String status, int page, int size) {
        int capped = Math.min(Math.max(size, 1), LIST_MAX_SIZE);
        Page<FeedbackQuestionEntity> p = questionMapper.selectPage(
                new Page<>(Math.max(page, 1), capped),
                Wrappers.<FeedbackQuestionEntity>lambdaQuery()
                        .eq(status != null && !status.isBlank(), FeedbackQuestionEntity::getStatus, status)
                        .orderByDesc(FeedbackQuestionEntity::getId));
        return PageResult.of(p.getRecords().stream().map(FeedbackQuestionService::toAdminVo).toList(),
                p.getTotal(), p.getCurrent(), p.getSize());
    }

    /**
     * 回答（抢态 OPEN/ANSWERED→ANSWERED；CLOSED 409）：
     * 旧状态 OPEN = 首答 → 发通知；旧 ANSWERED = 改答案 → 不发。isPublic 随答案落库。
     */
    @Transactional(rollbackFor = Exception.class)
    public void answerQuestion(Long id, String answer, boolean isPublic, Long answererId) {
        FeedbackQuestionEntity cur = questionMapper.selectById(id);
        if (cur == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "提问不存在");
        }
        if (FeedbackQuestionEntity.STATUS_CLOSED.equals(cur.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "该提问已关闭（终态），不可回答");
        }
        if (questionMapper.answerIfOpen(id, answer, isPublic, answererId) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "该提问状态已变化，请刷新");
        }
        if (FeedbackQuestionEntity.STATUS_OPEN.equals(cur.getStatus())) {
            notificationService.notify(cur.getUserId(),
                    FeedbackNotificationEntity.TYPE_QUESTION_ANSWERED,
                    id, "您的提问「" + abbrev(cur.getTitle()) + "」已有回答");
        }
        log.info("提问回答: id={} {}→ANSWERED isPublic={} answerer={}", id, cur.getStatus(), isPublic, answererId);
    }

    /** 关闭（OPEN/ANSWERED→CLOSED 抢态；已关闭 409）。 */
    public void closeQuestion(Long id) {
        if (questionMapper.closeIfNotClosed(id) == 0) {
            FeedbackQuestionEntity cur = questionMapper.selectById(id);
            if (cur == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "提问不存在");
            }
            throw new BusinessException(ErrorCode.CONFLICT, "该提问已是关闭状态");
        }
        log.info("提问关闭: id={}", id);
    }

    // ==================== 内部 ====================

    static QuestionVO toVo(FeedbackQuestionEntity e) {
        return new QuestionVO(e.getId(), e.getCreatedAt(), e.getTitle(), e.getContent(),
                e.getStatus(), e.getAnswer(), e.getAnsweredAt());
    }

    static AdminQuestionVO toAdminVo(FeedbackQuestionEntity e) {
        return new AdminQuestionVO(e.getId(), e.getCreatedAt(), e.getUserId(), e.getUsername(),
                e.getTitle(), e.getContent(), e.getStatus(), e.getAnswer(), e.getIsPublic(), e.getAnsweredAt());
    }

    private static String abbrev(String s) {
        return s == null ? "" : (s.length() <= 20 ? s : s.substring(0, 20) + "…");
    }
}
