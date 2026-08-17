package com.superprogrammer.knowledge.service;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.knowledge.dto.KnowledgeBaseRequest;
import com.superprogrammer.knowledge.dto.KnowledgeBaseVO;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.mapper.KnowledgeBaseMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.mapper.KnowledgePermissionMapper;
import com.superprogrammer.llm.service.LlmProviderService;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 14x#1 · answer_model 校验 + L4 换 embedding warning + 检索期 active 过滤回退。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceAnswerModelTest {

    @Mock private KnowledgeBaseMapper baseMapper;
    @Mock private KnowledgePermissionMapper permissionMapper;
    @Mock private KnowledgeDocumentMapper documentMapper;
    @Mock private LlmProviderService llmProviderService;
    @Mock private VisibilitySetService visibilitySetService;
    @Mock private SystemSettingService systemSettingService;

    @InjectMocks
    private KnowledgeBaseService service;

    private static final Long KB_ID = 1L;
    private static final Long USER_ID = 7L;

    @BeforeEach
    void stubActiveChatModels() {
        lenient().when(llmProviderService.listActiveModels("CHAT")).thenReturn(List.of("glm-5.1", "doubao-pro"));
    }

    private KnowledgeBase kb(String embeddingModel) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(KB_ID);
        kb.setTenantId(1L);
        kb.setName("kb1");
        kb.setVisibility("PRIVATE");
        kb.setEmbeddingModel(embeddingModel);
        kb.setStatus("ACTIVE");
        kb.setCreatedBy(USER_ID);
        return kb;
    }

    private KnowledgeBaseRequest req(String embeddingModel, String answerModel) {
        KnowledgeBaseRequest r = new KnowledgeBaseRequest();
        r.setName("kb1");
        r.setVisibility("PRIVATE");
        r.setEmbeddingModel(embeddingModel);
        r.setAnswerModel(answerModel);
        return r;
    }

    // ---- 保存校验 ----

    @Test
    void create_answerModelActive_accepted() {
        when(baseMapper.selectCount(any())).thenReturn(0L);
        when(baseMapper.insert(any(KnowledgeBase.class))).thenReturn(1);
        KnowledgeBaseVO vo = service.create(req("emb-a", "glm-5.1"), USER_ID);
        assertEquals("glm-5.1", vo.getAnswerModel());
    }

    @Test
    void create_answerModelBlank_staysNull() {
        when(baseMapper.selectCount(any())).thenReturn(0L);
        when(baseMapper.insert(any(KnowledgeBase.class))).thenReturn(1);
        KnowledgeBaseVO vo = service.create(req("emb-a", "  "), USER_ID);
        assertNull(vo.getAnswerModel());
    }

    @Test
    void create_answerModelArbitraryString_rejected() {
        when(baseMapper.selectCount(any())).thenReturn(0L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(req("emb-a", "not-a-model"), USER_ID));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        verify(baseMapper, never()).insert(any(KnowledgeBase.class));
    }

    @Test
    void create_answerModelTooLong_rejected() {
        when(baseMapper.selectCount(any())).thenReturn(0L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(req("emb-a", "x".repeat(129)), USER_ID));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    // ---- L4：换 embedding 且已有文档 → warning ----

    @Test
    void update_embeddingChangedWithDocs_returnsWarning() {
        KnowledgeBase kb = kb("emb-old");
        when(baseMapper.selectById(KB_ID)).thenReturn(kb);
        when(baseMapper.updateById(any(KnowledgeBase.class))).thenReturn(1);
        when(documentMapper.selectCount(any())).thenReturn(3L);

        KnowledgeBaseVO vo = service.update(KB_ID, req("emb-new", null), USER_ID, true);

        assertEquals("emb-new", vo.getEmbeddingModel());
        assertNotNull(vo.getWarning(), "换 embedding 且有存量文档必须返回重建索引强提示");
        assertTrue(vo.getWarning().contains("重建索引"));
    }

    @Test
    void update_embeddingChangedEmptyDb_noWarning() {
        KnowledgeBase kb = kb("emb-old");
        when(baseMapper.selectById(KB_ID)).thenReturn(kb);
        when(baseMapper.updateById(any(KnowledgeBase.class))).thenReturn(1);
        when(documentMapper.selectCount(any())).thenReturn(0L);

        KnowledgeBaseVO vo = service.update(KB_ID, req("emb-new", null), USER_ID, true);
        assertNull(vo.getWarning(), "空库无存量向量，无需提示");
    }

    @Test
    void update_embeddingUnchanged_noWarning() {
        KnowledgeBase kb = kb("emb-a");
        when(baseMapper.selectById(KB_ID)).thenReturn(kb);
        when(baseMapper.updateById(any(KnowledgeBase.class))).thenReturn(1);
        // 未换模型不查文档数（selectCount 不 stub，若被调用会 NPE/断言失败）

        KnowledgeBaseVO vo = service.update(KB_ID, req("emb-a", "glm-5.1"), USER_ID, true);
        assertNull(vo.getWarning());
        verify(documentMapper, never()).selectCount(any());
        assertEquals("glm-5.1", vo.getAnswerModel());
    }

    // ---- 检索期解析（L5：下线回退） ----

    @Test
    void resolveAnswerModel_nullOrInactive_fallsBackNull() {
        assertNull(service.resolveAnswerModel(null));
        assertNull(service.resolveAnswerModel(kb("emb")));

        KnowledgeBase kb = kb("emb");
        kb.setAnswerModel("glm-5.1");
        assertEquals("glm-5.1", service.resolveAnswerModel(kb));

        kb.setAnswerModel("retired-model");  // 已下线（不在 active 列表）→ 回退全局默认
        assertNull(service.resolveAnswerModel(kb));
    }

    @Test
    void resolveAnswerModel_blank_fallsBackNull_withoutProviderQuery() {
        KnowledgeBase kb = kb("emb");
        kb.setAnswerModel("   ");
        assertNull(service.resolveAnswerModel(kb));
        verify(llmProviderService, never()).listActiveModels(anyString());
    }
}
