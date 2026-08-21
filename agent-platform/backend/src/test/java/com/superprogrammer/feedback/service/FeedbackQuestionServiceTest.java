package com.superprogrammer.feedback.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.feedback.dto.CreateQuestionRequest;
import com.superprogrammer.feedback.dto.FaqVO;
import com.superprogrammer.feedback.entity.FeedbackQuestionEntity;
import com.superprogrammer.feedback.mapper.FeedbackQuestionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 提问台单测（19x#2）：首答通知/改答案不重发/终态拒答/FAQ 脱敏字段不存在。 */
@ExtendWith(MockitoExtension.class)
class FeedbackQuestionServiceTest {

    @Mock private FeedbackQuestionMapper questionMapper;
    @Mock private UserMapper userMapper;
    @Mock private FeedbackNotificationService notificationService;

    private FeedbackQuestionService service;

    @BeforeEach
    void setUp() {
        service = new FeedbackQuestionService(questionMapper, userMapper, notificationService);
    }

    private FeedbackQuestionEntity question(long id, long userId, String status) {
        FeedbackQuestionEntity e = new FeedbackQuestionEntity();
        e.setId(id);
        e.setUserId(userId);
        e.setUsername("bob");
        e.setTitle("积分怎么充？");
        e.setStatus(status);
        return e;
    }

    @Test
    void 提问_落库OPEN不公开() {
        User u = new User();
        u.setId(7L);
        u.setUsername("bob");
        when(userMapper.selectById(7L)).thenReturn(u);
        when(questionMapper.insert(any())).thenAnswer(inv -> {
            ((FeedbackQuestionEntity) inv.getArgument(0)).setId(5L);
            return 1;
        });

        Long id = service.submitQuestion(7L, new CreateQuestionRequest("t", "c"));

        assertEquals(5L, id);
        var cap = org.mockito.ArgumentCaptor.forClass(FeedbackQuestionEntity.class);
        verify(questionMapper).insert(cap.capture());
        assertEquals("OPEN", cap.getValue().getStatus());
        assertEquals(false, cap.getValue().getIsPublic());
    }

    @Test
    void 回答_首答发通知() {
        when(questionMapper.selectById(1L)).thenReturn(question(1L, 7L, "OPEN"));
        when(questionMapper.answerIfOpen(eq(1L), eq("看这里"), eq(true), eq(99L))).thenReturn(1);

        service.answerQuestion(1L, "看这里", true, 99L);

        verify(notificationService).notify(eq(7L), eq("QUESTION_ANSWERED"), eq(1L), any());
    }

    @Test
    void 回答_改答案不重发通知() {
        // 拍板：ANSWERED→ANSWERED 允许（改答案），但仅首次落 ANSWERED 发通知
        when(questionMapper.selectById(1L)).thenReturn(question(1L, 7L, "ANSWERED"));
        when(questionMapper.answerIfOpen(anyLong(), any(), anyBoolean(), anyLong())).thenReturn(1);

        service.answerQuestion(1L, "改后的答案", false, 99L);

        verify(questionMapper).answerIfOpen(1L, "改后的答案", false, 99L);
        verify(notificationService, never()).notify(any(), any(), any(), any());
    }

    @Test
    void 回答_CLOSED终态拒答_409() {
        when(questionMapper.selectById(1L)).thenReturn(question(1L, 7L, "CLOSED"));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.answerQuestion(1L, "a", false, 99L));
        assertTrue(ex.getMessage().contains("终态"));
        verify(questionMapper, never()).answerIfOpen(anyLong(), any(), anyBoolean(), anyLong());
    }

    @Test
    void 关闭_幂等拒绝重复() {
        when(questionMapper.closeIfNotClosed(1L)).thenReturn(0);
        when(questionMapper.selectById(1L)).thenReturn(question(1L, 7L, "CLOSED"));

        assertThrows(BusinessException.class, () -> service.closeQuestion(1L));
    }

    @Test
    void FAQ_响应VO无username字段_脱敏字段不存在层() {
        // 坑表②：不是置空是字段不存在——反射枚举 FaqVO 全部字段名
        List<String> fields = Arrays.stream(FaqVO.class.getRecordComponents())
                .map(c -> c.getName()).toList();
        assertTrue(fields.stream().noneMatch(f -> f.toLowerCase().contains("user")),
                "FaqVO 不得含任何 user 相关字段: " + fields);
        assertEquals(List.of("id", "title", "content", "answer", "answeredAt"), fields);
    }

    @Test
    void FAQ_检索委托mapper前缀查询() {
        when(questionMapper.countFaq("积分")).thenReturn(1L);
        when(questionMapper.pageFaq(eq("积分"), eq(0L), eq(20L))).thenReturn(List.of(
                new FaqVO(1L, "积分怎么充", "c", "a", OffsetDateTime.now())));

        var result = service.faq("积分", 1, 20);

        assertEquals(1, result.getTotal());
        assertEquals("积分怎么充", result.getRecords().get(0).title());
        verify(questionMapper).pageFaq("积分", 0L, 20L);
    }

    @Test
    void FAQ_空结果短路免分页查询() {
        when(questionMapper.countFaq("不存在")).thenReturn(0L);

        var result = service.faq("不存在", 1, 20);

        assertEquals(0, result.getTotal());
        verify(questionMapper, never()).pageFaq(any(), anyLong(), anyLong());
    }
}
