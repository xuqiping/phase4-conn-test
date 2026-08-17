package com.superprogrammer.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.knowledge.dto.KnowledgeBaseRequest;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.mapper.KnowledgeBaseMapper;
import com.superprogrammer.knowledge.mapper.KnowledgePermissionMapper;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 安全体系 S5 · SEC-FR-027（C8 枚举残点）：KB visibility 服务端白名单。
 * 原实现自由串直写库——脏值会让 canRead 的 "PUBLIC".equalsIgnoreCase 分支永不命中，权限语义不可预期。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceVisibilityTest {

    @Mock private KnowledgeBaseMapper baseMapper;
    @Mock private KnowledgePermissionMapper permissionMapper;
    @Mock private com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper documentMapper;
    @Mock private com.superprogrammer.llm.service.LlmProviderService llmProviderService;
    @Mock private VisibilitySetService visibilitySetService;
    @Mock private SystemSettingService systemSettingService;

    @InjectMocks
    private KnowledgeBaseService service;

    private KnowledgeBaseRequest request;

    @BeforeEach
    void setUp() {
        request = new KnowledgeBaseRequest();
        request.setName("kb-1");
        request.setEmbeddingModel("doubao-embedding");
    }

    @Test
    @DisplayName("create：null visibility → 默认 PRIVATE")
    void create_nullVisibility_defaultsPrivate() {
        when(baseMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        request.setVisibility(null);

        service.create(request, 1L);

        ArgumentCaptor<KnowledgeBase> captor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(baseMapper).insert(captor.capture());
        assertEquals("PRIVATE", captor.getValue().getVisibility());
    }

    @Test
    @DisplayName("create：非法 visibility → 400（不再自由串入库）")
    void create_invalidVisibility_rejected() {
        when(baseMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        request.setVisibility("EVERYONE_SEES_ALL");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(request, 1L));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        verify(baseMapper, org.mockito.Mockito.never()).insert(any(KnowledgeBase.class));
    }

    @Test
    @DisplayName("create：小写合法值归一化为大写（team → TEAM）")
    void create_lowercaseNormalized() {
        when(baseMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        request.setVisibility("team");

        service.create(request, 1L);

        ArgumentCaptor<KnowledgeBase> captor = ArgumentCaptor.forClass(KnowledgeBase.class);
        verify(baseMapper).insert(captor.capture());
        assertEquals("TEAM", captor.getValue().getVisibility());
    }

    @Test
    @DisplayName("update：非法 visibility → 400")
    void update_invalidVisibility_rejected() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(5L);
        kb.setTenantId(1L);
        kb.setName("kb-1");
        kb.setVisibility("PRIVATE");
        when(baseMapper.selectById(5L)).thenReturn(kb);
        request.setVisibility("PUBLIC-ISH");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(5L, request, 1L, true));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        verify(baseMapper, org.mockito.Mockito.never()).updateById(any(KnowledgeBase.class));
    }

    @Test
    @DisplayName("update：合法 visibility（public 小写）→ 归一化落库")
    void update_validVisibility_normalized() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(5L);
        kb.setTenantId(1L);
        kb.setName("kb-1");
        kb.setVisibility("PRIVATE");
        when(baseMapper.selectById(5L)).thenReturn(kb);
        request.setVisibility("public");

        service.update(5L, request, 1L, true);

        assertEquals("PUBLIC", kb.getVisibility());
        verify(baseMapper).updateById(kb);
    }
}
