package com.superprogrammer.feedback.service;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.feedback.dto.FeedbackMessageVO;
import com.superprogrammer.feedback.entity.FeedbackMessageEntity;
import com.superprogrammer.feedback.entity.FeedbackNotificationEntity;
import com.superprogrammer.feedback.entity.FeedbackQuestionEntity;
import com.superprogrammer.feedback.entity.FeedbackSuggestionEntity;
import com.superprogrammer.feedback.mapper.FeedbackMessageMapper;
import com.superprogrammer.feedback.mapper.FeedbackQuestionMapper;
import com.superprogrammer.feedback.mapper.FeedbackSuggestionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 反馈留言单测（19x 未解决#1）：留言落库+每次通知、目标校验、属主可读/他人 404。 */
@ExtendWith(MockitoExtension.class)
class FeedbackMessageServiceTest {

    @Mock private FeedbackMessageMapper messageMapper;
    @Mock private FeedbackSuggestionMapper suggestionMapper;
    @Mock private FeedbackQuestionMapper questionMapper;
    @Mock private FeedbackNotificationService notificationService;

    private FeedbackMessageService service;

    @BeforeEach
    void setUp() {
        service = new FeedbackMessageService(messageMapper, suggestionMapper, questionMapper, notificationService);
    }

    private FeedbackSuggestionEntity suggestion(long id, long userId) {
        FeedbackSuggestionEntity s = new FeedbackSuggestionEntity();
        s.setId(id);
        s.setUserId(userId);
        s.setTitle("加个导出功能");
        return s;
    }

    @Test
    void admin留言_落库并通知建议属主() {
        when(suggestionMapper.selectById(5L)).thenReturn(suggestion(5L, 7L));
        when(messageMapper.insert(any())).thenAnswer(inv -> {
            ((FeedbackMessageEntity) inv.getArgument(0)).setId(100L);
            return 1;
        });

        Long msgId = service.addAdminMessage(FeedbackMessageEntity.TARGET_SUGGESTION, 5L, " 已排期，下版本上线 ", 1L);

        assertEquals(100L, msgId);
        verify(messageMapper).insert(org.mockito.ArgumentMatchers.argThat(m ->
                FeedbackMessageEntity.ROLE_ADMIN.equals(m.getSenderRole())
                        && "已排期，下版本上线".equals(m.getContent())));
        verify(notificationService).notify(eq(7L), eq(FeedbackNotificationEntity.TYPE_SUGGESTION_MESSAGE),
                eq(5L), org.mockito.ArgumentMatchers.contains("新留言"));
    }

    @Test
    void admin留言_提问目标_通知提问属主() {
        FeedbackQuestionEntity q = new FeedbackQuestionEntity();
        q.setId(9L);
        q.setUserId(8L);
        q.setTitle("怎么批量生成");
        when(questionMapper.selectById(9L)).thenReturn(q);
        when(messageMapper.insert(any())).thenAnswer(inv -> {
            ((FeedbackMessageEntity) inv.getArgument(0)).setId(101L);
            return 1;
        });

        service.addAdminMessage(FeedbackMessageEntity.TARGET_QUESTION, 9L, "见帮助文档第 3 篇", 1L);

        verify(notificationService).notify(eq(8L), eq(FeedbackNotificationEntity.TYPE_QUESTION_MESSAGE),
                eq(9L), org.mockito.ArgumentMatchers.contains("新留言"));
    }

    @Test
    void admin留言_目标不存在_404不通知() {
        when(suggestionMapper.selectById(5L)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class, () ->
                service.addAdminMessage(FeedbackMessageEntity.TARGET_SUGGESTION, 5L, "hi", 1L));

        assertEquals(404, e.getCode());
        verify(notificationService, never()).notify(any(), any(), any(), any());
    }

    @Test
    void admin留言_空内容_400() {
        BusinessException e = assertThrows(BusinessException.class, () ->
                service.addAdminMessage(FeedbackMessageEntity.TARGET_SUGGESTION, 5L, "   ", 1L));

        assertEquals(400, e.getCode());
    }

    @Test
    void 读线程_属主可读() {
        when(suggestionMapper.selectById(5L)).thenReturn(suggestion(5L, 7L));
        when(messageMapper.selectList(any())).thenReturn(List.of());

        List<FeedbackMessageVO> list = service.listMessages(
                FeedbackMessageEntity.TARGET_SUGGESTION, 5L, 7L, false);

        assertEquals(0, list.size());
    }

    @Test
    void 读线程_他人目标_404不泄露() {
        when(suggestionMapper.selectById(5L)).thenReturn(suggestion(5L, 7L));

        BusinessException e = assertThrows(BusinessException.class, () ->
                service.listMessages(FeedbackMessageEntity.TARGET_SUGGESTION, 5L, 8L, false));

        assertEquals(404, e.getCode());
    }

    @Test
    void 读线程_admin不受属主限制() {
        when(suggestionMapper.selectById(5L)).thenReturn(suggestion(5L, 7L));
        when(messageMapper.selectList(any())).thenReturn(List.of());

        service.listMessages(FeedbackMessageEntity.TARGET_SUGGESTION, 5L, 1L, true);

        verify(messageMapper).selectList(any());
    }
}
