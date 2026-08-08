package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.mapper.MemoryProjectEntryMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 记忆二期 P3 · Step 4（FR-204）· 项目 FILE 条目成员下载放行裁决。
 * 放行仅走「条目 ACTIVE + content_type=FILE + 成员 ACTIVE」单 SQL 路径；空入参短路不放行。
 */
@ExtendWith(MockitoExtension.class)
class MemoryFileEntryAccessGrantorTest {

    @Mock
    MemoryProjectEntryMapper entryMapper;

    @Test
    void blankArgs_deny() {
        MemoryFileEntryAccessGrantor g = new MemoryFileEntryAccessGrantor(entryMapper);
        assertFalse(g.canAccess(null, 1L));
        assertFalse(g.canAccess("  ", 1L));
        assertFalse(g.canAccess("f-a", null));
        verifyNoInteractions(entryMapper);
    }

    @Test
    void activeEntryAndMember_allows() {
        when(entryMapper.countAccessibleFileEntries("f-a", 2L)).thenReturn(1L);
        MemoryFileEntryAccessGrantor g = new MemoryFileEntryAccessGrantor(entryMapper);
        assertTrue(g.canAccess("f-a", 2L), "ACTIVE FILE 条目 + ACTIVE 成员 → 放行");
    }

    @Test
    void noAccessibleEntry_denies() {
        when(entryMapper.countAccessibleFileEntries("f-a", 3L)).thenReturn(0L);
        MemoryFileEntryAccessGrantor g = new MemoryFileEntryAccessGrantor(entryMapper);
        assertFalse(g.canAccess("f-a", 3L), "非成员/非 ACTIVE 条目 → 维持 403");
    }
}
