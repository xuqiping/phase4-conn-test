package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.MemoryRecallScopeRequest;
import com.superprogrammer.chat.entity.MemoryRecallScopePref;
import com.superprogrammer.chat.mapper.MemoryRecallScopePrefMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 计划12 · D-7 · MemoryRecallScopePreferenceService 单测（Mockito，mock prefMapper）。
 * <p>
 * 覆盖：
 * <ol>
 *   <li>getScope 无记录 → null。</li>
 *   <li>getScope 有记录 → 全字段映射 req。</li>
 *   <li>saveScope 无记录 → insert + null 规范化（personalOn→true / direction→BOTH / includeDeparted→true / projectIds→[]）。</li>
 *   <li>saveScope 有记录 → updateById 保留原 id + 字段更新。</li>
 *   <li>saveScope relativeDays/timeWindow nullable 透传。</li>
 *   <li>saveScope req=null → 全默认。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class MemoryRecallScopePreferenceServiceTest {

    @Mock
    MemoryRecallScopePrefMapper prefMapper;

    private MemoryRecallScopePreferenceService service;

    @BeforeEach
    void setUp() {
        service = new MemoryRecallScopePreferenceService(prefMapper);
    }

    // ===== getScope =====

    @Test
    void getScope_noRecord_returnsNull() {
        when(prefMapper.selectOne(any())).thenReturn(null);
        assertNull(service.getScope(1L));
    }

    @Test
    void getScope_record_mapsAllFields() {
        MemoryRecallScopePref p = new MemoryRecallScopePref();
        p.setUserId(1L);
        p.setPersonalOn(false);
        p.setProjectIds(List.of(7L, 8L));
        p.setDirection("INPUT");
        p.setRelativeDays(30);
        p.setIncludeDeparted(false);
        OffsetDateTime s = OffsetDateTime.now();
        OffsetDateTime e = s.plusDays(1);
        p.setTwStart(s);
        p.setTwEnd(e);
        when(prefMapper.selectOne(any())).thenReturn(p);

        MemoryRecallScopeRequest r = service.getScope(1L);
        assertEquals(false, r.getPersonalOn());
        assertEquals(List.of(7L, 8L), r.getProjectIds());
        assertEquals("INPUT", r.getDirection());
        assertEquals(30, r.getRelativeDays());
        assertEquals(s, r.getStart());
        assertEquals(e, r.getEnd());
        assertEquals(false, r.getIncludeDeparted());
    }

    // ===== saveScope insert =====

    @Test
    void saveScope_noRecord_insertsNormalizedDefaults() {
        when(prefMapper.selectOne(any())).thenReturn(null);
        MemoryRecallScopeRequest req = new MemoryRecallScopeRequest();  // 全 null 字段

        service.saveScope(1L, req);

        ArgumentCaptor<MemoryRecallScopePref> cap = ArgumentCaptor.forClass(MemoryRecallScopePref.class);
        verify(prefMapper).insert(cap.capture());
        verify(prefMapper, never()).updateById(any());
        MemoryRecallScopePref saved = cap.getValue();
        assertEquals(1L, saved.getUserId());
        assertEquals(true, saved.getPersonalOn(), "null→true");
        assertEquals(List.of(), saved.getProjectIds(), "null→[]");
        assertEquals("BOTH", saved.getDirection(), "null→BOTH");
        assertEquals(true, saved.getIncludeDeparted(), "null→true");
    }

    @Test
    void saveScope_noRecord_relativeDaysAndTimeWindowPassedThrough() {
        when(prefMapper.selectOne(any())).thenReturn(null);
        MemoryRecallScopeRequest req = new MemoryRecallScopeRequest();
        req.setRelativeDays(7);
        OffsetDateTime s = OffsetDateTime.now();
        req.setStart(s);
        req.setEnd(s.plusDays(7));

        service.saveScope(1L, req);

        ArgumentCaptor<MemoryRecallScopePref> cap = ArgumentCaptor.forClass(MemoryRecallScopePref.class);
        verify(prefMapper).insert(cap.capture());
        MemoryRecallScopePref saved = cap.getValue();
        assertEquals(7, saved.getRelativeDays());
        assertEquals(s, saved.getTwStart());
        assertEquals(s.plusDays(7), saved.getTwEnd());
    }

    // ===== saveScope update =====

    @Test
    void saveScope_existing_updatesFieldsKeepsId() {
        MemoryRecallScopePref existing = new MemoryRecallScopePref();
        existing.setId(99L);
        existing.setUserId(1L);
        when(prefMapper.selectOne(any())).thenReturn(existing);

        MemoryRecallScopeRequest req = new MemoryRecallScopeRequest();
        req.setPersonalOn(false);
        req.setProjectIds(List.of(7L));
        req.setDirection("OUTPUT");

        service.saveScope(1L, req);

        ArgumentCaptor<MemoryRecallScopePref> cap = ArgumentCaptor.forClass(MemoryRecallScopePref.class);
        verify(prefMapper).updateById(cap.capture());
        verify(prefMapper, never()).insert(any());
        MemoryRecallScopePref upd = cap.getValue();
        assertEquals(99L, upd.getId(), "保留原 id");
        assertEquals(false, upd.getPersonalOn());
        assertEquals(List.of(7L), upd.getProjectIds());
        assertEquals("OUTPUT", upd.getDirection());
    }

    // ===== req null 全默认 =====

    @Test
    void saveScope_nullRequest_appliesAllDefaults() {
        when(prefMapper.selectOne(any())).thenReturn(null);
        service.saveScope(1L, null);
        ArgumentCaptor<MemoryRecallScopePref> cap = ArgumentCaptor.forClass(MemoryRecallScopePref.class);
        verify(prefMapper).insert(cap.capture());
        MemoryRecallScopePref saved = cap.getValue();
        assertEquals(true, saved.getPersonalOn());
        assertEquals("BOTH", saved.getDirection());
        assertEquals(true, saved.getIncludeDeparted());
        assertEquals(List.of(), saved.getProjectIds());
        assertNull(saved.getRelativeDays(), "nullable 透传");
    }
}
