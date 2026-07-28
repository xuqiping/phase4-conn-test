package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.MemoryRosterVO;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 计划12 · I2 · MemoryRosterService 单测（Mockito）。
 * 仅校验委托 + null 兜底；join users / DEPARTED 含留在 IT 实跑（I2-4）。
 */
@ExtendWith(MockitoExtension.class)
class MemoryRosterServiceTest {

    @Mock MemoryProjectMemberMapper memberMapper;

    private MemoryRosterService service;

    @BeforeEach
    void setUp() {
        service = new MemoryRosterService(memberMapper);
    }

    @Test
    void getRoster_delegatesToMapper() {
        MemoryRosterVO vo = MemoryRosterVO.builder().userId(1L).role("OWNER").status("ACTIVE").build();
        when(memberMapper.findRoster(100L)).thenReturn(List.of(vo));

        List<MemoryRosterVO> roster = service.getRoster(100L);

        assertEquals(1, roster.size());
        assertSame(vo, roster.get(0));
    }

    @Test
    void getRoster_nullProjectId_empty() {
        assertTrue(service.getRoster(null).isEmpty());
        verifyNoInteractions(memberMapper);
    }
}
