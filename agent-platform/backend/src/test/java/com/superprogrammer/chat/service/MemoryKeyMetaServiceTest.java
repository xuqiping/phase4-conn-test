package com.superprogrammer.chat.service;

import com.superprogrammer.chat.entity.MemoryKeyMeta;
import com.superprogrammer.chat.mapper.MemoryKeyMetaMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** M2:MemoryKeyMetaService 单测——upsert 语义(LLM_ASK 不覆盖已有,USER_OVERRIDE 覆盖)。 */
@ExtendWith(MockitoExtension.class)
class MemoryKeyMetaServiceTest {

    @Mock
    MemoryKeyMetaMapper mapper;
    @InjectMocks
    MemoryKeyMetaService service;

    @Test
    void recordFromAsk_noExisting_insertsLlmAsk() {
        when(mapper.findByUserKey(1L, "address")).thenReturn(null);
        service.recordFromAsk(1L, "address", true);
        ArgumentCaptor<MemoryKeyMeta> cap = ArgumentCaptor.forClass(MemoryKeyMeta.class);
        verify(mapper).insert(cap.capture());
        assertEquals("LLM_ASK", cap.getValue().getSource());
        assertEquals(true, cap.getValue().getIsTemporal());
        assertNotNull(cap.getValue().getUpdatedAt());
    }

    @Test
    void recordFromAsk_existingNotOverwritten() {
        MemoryKeyMeta existing = new MemoryKeyMeta();
        existing.setIsTemporal(false);
        existing.setSource("USER_OVERRIDE");
        when(mapper.findByUserKey(1L, "address")).thenReturn(existing);
        MemoryKeyMeta out = service.recordFromAsk(1L, "address", true);
        verify(mapper, never()).insert(any());
        verify(mapper, never()).updateById(any());
        assertSame(existing, out);  // 已有标(尤其 USER_OVERRIDE)不被 LLM_ASK 覆盖
    }

    @Test
    void override_noExisting_insertsUserOverride() {
        when(mapper.findByUserKey(1L, "address")).thenReturn(null);
        service.override(1L, "address", true);
        ArgumentCaptor<MemoryKeyMeta> cap = ArgumentCaptor.forClass(MemoryKeyMeta.class);
        verify(mapper).insert(cap.capture());
        assertEquals("USER_OVERRIDE", cap.getValue().getSource());
    }

    @Test
    void override_existing_updatesAndFlipsSource() {
        MemoryKeyMeta existing = new MemoryKeyMeta();
        existing.setId(7L);
        existing.setIsTemporal(false);
        existing.setSource("LLM_ASK");
        when(mapper.findByUserKey(1L, "address")).thenReturn(existing);
        service.override(1L, "address", true);
        verify(mapper).updateById(existing);
        assertEquals(true, existing.getIsTemporal());
        assertEquals("USER_OVERRIDE", existing.getSource());
        assertNotNull(existing.getUpdatedAt());
    }

    @Test
    void isTemporal_noMark_defaultsFalse() {
        when(mapper.findByUserKey(1L, "name")).thenReturn(null);
        assertFalse(service.isTemporal(1L, "name"));
    }

    @Test
    void isTemporal_blankKey_safeNull() {
        assertNull(service.findByUserKey(1L, ""));
        assertNull(service.findByUserKey(1L, null));
    }
}
