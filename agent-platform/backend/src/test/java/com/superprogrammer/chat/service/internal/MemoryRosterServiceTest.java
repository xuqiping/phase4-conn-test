package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.MemoryRosterVO;
import com.superprogrammer.chat.entity.MemoryProjectMember;
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

    // ===== isMember（roster 端点可见性判据）=====

    private MemoryProjectMember member(String role, String status) {
        MemoryProjectMember m = new MemoryProjectMember();
        m.setProjectId(100L);
        m.setUserId(1L);
        m.setRole(role);
        m.setStatus(status);
        return m;
    }

    @Test
    void isMember_activeMember_true() {
        when(memberMapper.selectOne(any())).thenReturn(member("MEMBER", "ACTIVE"));
        assertTrue(service.isMember(100L, 1L));
    }

    @Test
    void isMember_ownerActive_true() {
        when(memberMapper.selectOne(any())).thenReturn(member("OWNER", "ACTIVE"));
        assertTrue(service.isMember(100L, 1L));
    }

    @Test
    void isMember_departed_false() {
        // DEPARTED 已离开 → 无项目读权 → false
        when(memberMapper.selectOne(any())).thenReturn(member("MEMBER", "DEPARTED"));
        assertFalse(service.isMember(100L, 1L));
    }

    @Test
    void isMember_nonMember_false() {
        when(memberMapper.selectOne(any())).thenReturn(null);
        assertFalse(service.isMember(100L, 999L));
    }

    @Test
    void isMember_nullArgs_false() {
        assertFalse(service.isMember(null, 1L));
        assertFalse(service.isMember(100L, null));
        verifyNoInteractions(memberMapper);
    }
}
