package com.superprogrammer.feedback.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.feedback.dto.CreateSuggestionRequest;
import com.superprogrammer.feedback.entity.FeedbackSuggestionEntity;
import com.superprogrammer.feedback.mapper.FeedbackSuggestionMapper;
import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 建议台用户侧单测（19x#1）：提交落库+快照、附件属主、mine 强制 self。 */
@ExtendWith(MockitoExtension.class)
class FeedbackServiceTest {

    @Mock private FeedbackSuggestionMapper suggestionMapper;
    @Mock private FileStorageService fileStorageService;
    @Mock private UserMapper userMapper;
    @Mock private FeedbackNotificationService notificationService;

    private FeedbackService service;

    @BeforeEach
    void setUp() {
        service = new FeedbackService(suggestionMapper, fileStorageService, userMapper, notificationService);
    }

    private User user(long id, String username) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        return u;
    }

    @Test
    void 提交_落库含username快照与PENDING() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, "alice"));
        when(suggestionMapper.insert(any())).thenAnswer(inv -> {
            ((FeedbackSuggestionEntity) inv.getArgument(0)).setId(99L);
            return 1;
        });

        Long id = service.submitSuggestion(7L, new CreateSuggestionRequest("标题", "内容", null));

        assertEquals(99L, id);
        ArgumentCaptor<FeedbackSuggestionEntity> cap = ArgumentCaptor.forClass(FeedbackSuggestionEntity.class);
        verify(suggestionMapper).insert(cap.capture());
        FeedbackSuggestionEntity e = cap.getValue();
        assertEquals(7L, e.getUserId());
        assertEquals("alice", e.getUsername());           // 快照
        assertEquals("PENDING", e.getStatus());
        assertNull(e.getAttachmentFileIds());             // 无附件 → null（不占 jsonb）
    }

    @Test
    void 提交_带本人附件_序列化jsonb() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, "alice"));
        StoredFileEntity f = new StoredFileEntity();
        f.setOwnerUserId(7L);
        when(fileStorageService.findMeta("abc.png")).thenReturn(f);
        when(suggestionMapper.insert(any())).thenReturn(1);

        service.submitSuggestion(7L, new CreateSuggestionRequest("t", "c", List.of("abc.png")));

        ArgumentCaptor<FeedbackSuggestionEntity> cap = ArgumentCaptor.forClass(FeedbackSuggestionEntity.class);
        verify(suggestionMapper).insert(cap.capture());
        assertEquals("[\"abc.png\"]", cap.getValue().getAttachmentFileIds());
    }

    @Test
    void 提交_挂他人附件_400() {
        StoredFileEntity f = new StoredFileEntity();
        f.setOwnerUserId(8L);                            // 属主是别人
        when(fileStorageService.findMeta("other.png")).thenReturn(f);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.submitSuggestion(7L, new CreateSuggestionRequest("t", "c", List.of("other.png"))));
        assertTrue(ex.getMessage().contains("附件无效"));
    }

    @Test
    void 提交_附件不存在_400() {
        when(fileStorageService.findMeta("ghost.png")).thenReturn(null);

        assertThrows(BusinessException.class, () ->
                service.submitSuggestion(7L, new CreateSuggestionRequest("t", "c", List.of("ghost.png"))));
    }

    @Test
    void 提交_未登录_401() {
        assertThrows(BusinessException.class, () ->
                service.submitSuggestion(null, new CreateSuggestionRequest("t", "c", null)));
    }

    @Test
    void 我的建议_强制self过滤() {
        Page<FeedbackSuggestionEntity> p = new Page<>(1, 10);
        FeedbackSuggestionEntity e = new FeedbackSuggestionEntity();
        e.setId(1L);
        e.setUserId(7L);
        e.setUsername("alice");
        e.setTitle("t");
        e.setStatus("ADOPTED");
        e.setReply("已采纳");
        e.setAttachmentFileIds("[\"a.png\",\"b.png\"]");
        p.setRecords(List.of(e));
        p.setTotal(1);
        when(suggestionMapper.selectPage(any(), any())).thenReturn(p);

        var result = service.mySuggestions(7L, 1, 10);

        assertEquals(1, result.getTotal());
        assertEquals("已采纳", result.getRecords().get(0).reply());
        assertEquals(List.of("a.png", "b.png"), result.getRecords().get(0).attachmentFileIds());
        // 查询条件强制 eq userId=7（wrapper 内容由 MP lambda 组装，此处验证 mapper 被调即可；
        // SQL 层 self 过滤由 IT 覆盖）
        verify(suggestionMapper).selectPage(any(), any());
    }

    @Test
    void json数组_解析容错() {
        assertEquals(List.of(), FeedbackService.parseJsonArray(null));
        assertEquals(List.of(), FeedbackService.parseJsonArray("[]"));
        assertEquals(List.of("x"), FeedbackService.parseJsonArray("[\"x\"]"));
        assertEquals(List.of(), FeedbackService.parseJsonArray("garbage"));
    }

    // ==================== Step3：admin 审核 + 通知 ====================

    private FeedbackSuggestionEntity suggestion(long id, long userId, String status) {
        FeedbackSuggestionEntity e = new FeedbackSuggestionEntity();
        e.setId(id);
        e.setUserId(userId);
        e.setUsername("alice");
        e.setTitle("加个暗黑模式");
        e.setStatus(status);
        return e;
    }

    @Test
    void 审核_PENDING到ADOPTED_发通知() {
        when(suggestionMapper.selectById(1L)).thenReturn(suggestion(1L, 7L, "PENDING"));
        when(suggestionMapper.reviewIfStatusIn(eq(1L), eq("ADOPTED"), any(), eq(99L),
                eq(List.of("PENDING", "REJECTED")))).thenReturn(1);

        service.reviewSuggestion(1L, "ADOPTED", "下个版本安排", 99L);

        verify(notificationService).notify(eq(7L), eq("SUGGESTION_REVIEWED"), eq(1L),
                org.mockito.ArgumentMatchers.contains("已采纳"));
    }

    @Test
    void 审核_改判REJECTED到ADOPTED_重发通知() {
        // 拍板联动表：ADOPTED↔REJECTED 可改判且重发通知
        when(suggestionMapper.selectById(1L)).thenReturn(suggestion(1L, 7L, "REJECTED"));
        when(suggestionMapper.reviewIfStatusIn(eq(1L), eq("ADOPTED"), any(), eq(99L),
                eq(List.of("PENDING", "REJECTED")))).thenReturn(1);

        service.reviewSuggestion(1L, "ADOPTED", null, 99L);

        verify(notificationService).notify(eq(7L), eq("SUGGESTION_REVIEWED"), eq(1L), any());
    }

    @Test
    void 审核_CLOSED终态拒改_409() {
        when(suggestionMapper.selectById(1L)).thenReturn(suggestion(1L, 7L, "CLOSED"));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.reviewSuggestion(1L, "ADOPTED", null, 99L));
        assertTrue(ex.getMessage().contains("终态"));
        verify(notificationService, org.mockito.Mockito.never()).notify(any(), any(), any(), any());
    }

    @Test
    void 审核_并发抢态失败_409且不发通知() {
        // 两 admin 同审：mapper 返 0 = 被对方抢先
        when(suggestionMapper.selectById(1L)).thenReturn(suggestion(1L, 7L, "PENDING"));
        when(suggestionMapper.reviewIfStatusIn(any(), any(), any(), any(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                service.reviewSuggestion(1L, "ADOPTED", null, 99L));
        assertTrue(ex.getMessage().contains("已被其他管理员处理"));
        verify(notificationService, org.mockito.Mockito.never()).notify(any(), any(), any(), any());
    }

    @Test
    void 审核_不存在_404() {
        when(suggestionMapper.selectById(1L)).thenReturn(null);

        assertThrows(BusinessException.class, () ->
                service.reviewSuggestion(1L, "ADOPTED", null, 99L));
    }

    @Test
    void 审核_非法结论_400() {
        when(suggestionMapper.selectById(1L)).thenReturn(suggestion(1L, 7L, "PENDING"));

        assertThrows(BusinessException.class, () ->
                service.reviewSuggestion(1L, "MAYBE", null, 99L));
    }
}
