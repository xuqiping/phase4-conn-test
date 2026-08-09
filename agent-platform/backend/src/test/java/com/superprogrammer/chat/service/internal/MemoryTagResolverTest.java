package com.superprogrammer.chat.service.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.entity.MemoryTag;
import com.superprogrammer.chat.mapper.MemoryTagMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmResponse;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 计划12 B · MemoryTagResolver 四路径 + 并发兜底单测（Mockito，无 DB/LLM 实依赖）。
 * <p>
 * 覆盖：①精确槽位命中（含同名/异名 alias 滚进）②aliases 命中 ③anchor+LLM 判同义命中
 *      ③LLM 判无同义→新建 ④anchor 失败→新建 ④并发 UNIQUE 撞→复用 + 入参校验。
 */
@ExtendWith(MockitoExtension.class)
class MemoryTagResolverTest {

    @Mock MemoryTagMapper tagMapper;
    @Mock MemoryTagAnchorService anchorService;
    @Mock SystemSettingService systemSettingService;
    @Mock LlmGateway llmGateway;
    @Mock com.superprogrammer.chat.mapper.MemoryNotificationMapper notificationMapper;

    private MemoryTagResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new MemoryTagResolver(tagMapper, anchorService, systemSettingService, llmGateway,
                new ObjectMapper(), notificationMapper);
    }

    private MemoryTag tag(long id, String label, String subject, String topic) {
        MemoryTag t = new MemoryTag();
        t.setId(id);
        t.setLabel(label);
        t.setSubject(subject);
        t.setTopic(topic);
        t.setUserId(1L);
        t.setUsageCount(3);
        return t;
    }

    /** 模拟 DB 生成主键：真实 insertWithAnchor 走 @Options(useGeneratedKeys) 回填 m.id，Mock 下手动塞。 */
    private void stubInsertId(long fakeId) {
        doAnswer(inv -> {
            MemoryTag m = inv.getArgument(0);
            m.setId(fakeId);
            return null;
        }).when(tagMapper).insertWithAnchor(any(), any(), any());
    }

    // ===== 路径① 精确槽位 =====

    @Test
    void path1_slotHit_sameLabel_reuse_noAlias() {
        when(tagMapper.findByUserSubjectTopic(1L, "我", "居住")).thenReturn(tag(10L, "杭州", "我", "居住"));
        Long id = resolver.resolve(1L, "我", "居住", "杭州");
        assertEquals(10L, id);
        verify(tagMapper).incrementUsage(10L);
        verify(tagMapper, never()).appendAlias(anyLong(), anyString());
        verify(tagMapper, never()).insertWithAnchor(any(), any(), any());
    }

    @Test
    void path1_slotHit_diffLabel_rollsAlias() {
        when(tagMapper.findByUserSubjectTopic(1L, "我", "居住")).thenReturn(tag(10L, "杭州", "我", "居住"));
        Long id = resolver.resolve(1L, "我", "居住", "萧山");
        assertEquals(10L, id);
        verify(tagMapper).incrementUsage(10L);
        verify(tagMapper).appendAlias(10L, "萧山");
        verify(tagMapper, never()).insertWithAnchor(any(), any(), any());
    }

    // ===== 路径② aliases 命中 =====

    @Test
    void path2_labelInAliases_reuse() {
        when(tagMapper.findByUserSubjectTopic(eq(1L), anyString(), anyString())).thenReturn(null);
        when(tagMapper.findByLabelInAliases(1L, "住址")).thenReturn(tag(20L, "杭州", "我", "居住"));
        Long id = resolver.resolve(1L, "我", "居住", "住址");
        assertEquals(20L, id);
        verify(tagMapper).incrementUsage(20L);
        verify(tagMapper, never()).insertWithAnchor(any(), any(), any());
    }

    // ===== 路径③ anchor + LLM 判同义 =====

    @Test
    void path3_anchorAndLlmSynonym_reuse_rollAlias() {
        MemoryTagAnchorService.AnchorPayload ap = new MemoryTagAnchorService.AnchorPayload("[0.1]", "tok");
        when(anchorService.build(eq(1L), anyString(), anyString(), anyString(), any())).thenReturn(ap);
        when(tagMapper.findWithinAnchorThreshold(eq(1L), eq("[0.1]"), anyDouble(), anyInt()))
                .thenReturn(List.of(tag(30L, "杭州", "我", "居住")));
        when(llmGateway.chat(any(), any())).thenReturn(LlmResponse.builder().content("[30]").build());
        // 路径①② miss
        when(tagMapper.findByUserSubjectTopic(eq(1L), anyString(), anyString())).thenReturn(null);
        when(tagMapper.findByLabelInAliases(eq(1L), anyString())).thenReturn(null);

        Long id = resolver.resolve(1L, "我", "居住", "住址");

        assertEquals(30L, id);
        verify(tagMapper).incrementUsage(30L);
        verify(tagMapper).appendAlias(30L, "住址");
        verify(tagMapper, never()).insertWithAnchor(any(), any(), any());
    }

    @Test
    void path3_llmNoSynonym_fallsToNewInsert() {
        MemoryTagAnchorService.AnchorPayload ap = new MemoryTagAnchorService.AnchorPayload("[0.1]", "tok");
        when(anchorService.build(eq(1L), anyString(), anyString(), anyString(), any())).thenReturn(ap);
        when(tagMapper.findWithinAnchorThreshold(eq(1L), eq("[0.1]"), anyDouble(), anyInt()))
                .thenReturn(List.of(tag(30L, "工作地", "我", "工作")));
        when(llmGateway.chat(any(), any())).thenReturn(LlmResponse.builder().content("[]").build()); // LLM 明确判无同义
        when(tagMapper.findByUserSubjectTopic(eq(1L), anyString(), anyString())).thenReturn(null);
        when(tagMapper.findByLabelInAliases(eq(1L), anyString())).thenReturn(null);
        stubInsertId(777L); // 模拟 DB 生成主键

        Long id = resolver.resolve(1L, "我", "居住", "住址");

        assertEquals(777L, id);
        verify(tagMapper).insertWithAnchor(any(), eq("[0.1]"), eq("tok"));
        verify(tagMapper, never()).incrementUsage(30L);
    }

    @Test
    void path3_llmFailure_safeFallbackToNewInsert() {
        MemoryTagAnchorService.AnchorPayload ap = new MemoryTagAnchorService.AnchorPayload("[0.1]", "tok");
        when(anchorService.build(eq(1L), anyString(), anyString(), anyString(), any())).thenReturn(ap);
        when(tagMapper.findWithinAnchorThreshold(eq(1L), eq("[0.1]"), anyDouble(), anyInt()))
                .thenReturn(List.of(tag(30L, "工作地", "我", "工作")));
        when(llmGateway.chat(any(), any())).thenThrow(new RuntimeException("LLM 挂了")); // 两次都炸
        when(tagMapper.findByUserSubjectTopic(eq(1L), anyString(), anyString())).thenReturn(null);
        when(tagMapper.findByLabelInAliases(eq(1L), anyString())).thenReturn(null);
        stubInsertId(777L);

        Long id = resolver.resolve(1L, "我", "居住", "住址");

        assertEquals(777L, id);
        verify(tagMapper).insertWithAnchor(any(), eq("[0.1]"), eq("tok")); // 安全落空→新建（防误并）
    }

    // ===== 路径④ anchor 失败 / 全 miss =====

    @Test
    void path4_anchorNull_skipsPath3_insertsWithNullAnchor() {
        when(anchorService.build(eq(1L), anyString(), anyString(), anyString(), any())).thenReturn(null);
        when(tagMapper.findByUserSubjectTopic(eq(1L), anyString(), anyString())).thenReturn(null);
        when(tagMapper.findByLabelInAliases(eq(1L), anyString())).thenReturn(null);
        stubInsertId(777L);

        Long id = resolver.resolve(1L, "我", "居住", "住址");

        assertEquals(777L, id);
        verify(tagMapper, never()).findWithinAnchorThreshold(anyLong(), anyString(), anyDouble(), anyInt());
        verify(tagMapper).insertWithAnchor(any(), isNull(), isNull()); // anchor 降级 null
    }

    @Test
    void path4_duplicateKey_concurrentFallbackReuses() {
        when(anchorService.build(eq(1L), anyString(), anyString(), anyString(), any())).thenReturn(null);
        when(tagMapper.findByUserSubjectTopic(eq(1L), anyString(), anyString())).thenReturn(null, tag(99L, "住址", "我", "居住"));
        when(tagMapper.findByLabelInAliases(eq(1L), anyString())).thenReturn(null);
        // 第一次插撞 UNIQUE（并发对手先建），改查命中赢家
        doThrow(new DuplicateKeyException("uniq"))
                .when(tagMapper).insertWithAnchor(any(), any(), any());

        Long id = resolver.resolve(1L, "我", "居住", "住址");

        assertEquals(99L, id);
        verify(tagMapper).incrementUsage(99L);
    }

    // ===== V77 needs_review：词表外新标签建条 + 发非阻塞通知 =====

    @Test
    void path4_needsReviewTrue_insertsTagAndNotification() {
        when(anchorService.build(eq(1L), anyString(), anyString(), anyString(), any())).thenReturn(null);
        when(tagMapper.findByUserSubjectTopic(eq(1L), anyString(), anyString())).thenReturn(null);
        when(tagMapper.findByLabelInAliases(eq(1L), anyString())).thenReturn(null);
        stubInsertId(888L);

        Long id = resolver.resolve(1L, "我", "异宠养殖", "养螳螂", true);

        assertEquals(888L, id);
        // needsReview 透传到实体
        org.mockito.ArgumentCaptor<MemoryTag> cap = org.mockito.ArgumentCaptor.forClass(MemoryTag.class);
        verify(tagMapper).insertWithAnchor(cap.capture(), isNull(), isNull());
        assertEquals(Boolean.TRUE, cap.getValue().getNeedsReview(), "词表外新标签 needs_review=true");
        // 发 TAG_NEEDS_REVIEW 通知
        verify(notificationMapper).insert(org.mockito.ArgumentMatchers.argThat(n ->
                "TAG_NEEDS_REVIEW".equals(n.getType()) && 888L == n.getRefId()));
    }

    @Test
    void path4_needsReviewFalse_noNotification() {
        when(anchorService.build(eq(1L), anyString(), anyString(), anyString(), any())).thenReturn(null);
        when(tagMapper.findByUserSubjectTopic(eq(1L), anyString(), anyString())).thenReturn(null);
        when(tagMapper.findByLabelInAliases(eq(1L), anyString())).thenReturn(null);
        stubInsertId(889L);

        Long id = resolver.resolve(1L, "我", "居住", "住址", false);

        assertEquals(889L, id);
        verify(notificationMapper, never()).insert(any());
    }

    // ===== 入参校验 =====

    @Test
    void blankTopic_throws() {
        assertThrows(BusinessException.class, () -> resolver.resolve(1L, "我", "  ", "x"));
    }

    @Test
    void blankLabel_throws() {
        assertThrows(BusinessException.class, () -> resolver.resolve(1L, "我", "居住", "  "));
    }
}
