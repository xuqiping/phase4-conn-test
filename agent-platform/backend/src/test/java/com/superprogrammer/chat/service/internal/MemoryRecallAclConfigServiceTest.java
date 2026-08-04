package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.MemoryRecallAclRequest;
import com.superprogrammer.chat.entity.MemoryProjectMember;
import com.superprogrammer.chat.entity.MemoryRecallAcl;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import com.superprogrammer.chat.mapper.MemoryRecallAclMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 计划12 · I2 · MemoryRecallAclConfigService 单测（Mockito，无 DB 实依赖）。
 * <p>
 * 覆盖（对齐 I2 plan 向量 14/15）：
 * <ol>
 *   <li>配置权边界 isConfigurable：owner / admin+recall_admin / admin 非 recall_admin / member / 非成员 / null。</li>
 *   <li>replaceAll 全量替换：删旧+插新原子 / target ∩ 成员过滤 / DEPARTED 保留 / 空集清权 / reader 非成员 skip / created_by 审计。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class MemoryRecallAclConfigServiceTest {

    @Mock MemoryRecallAclMapper recallAclMapper;
    @Mock MemoryProjectMemberMapper memberMapper;

    private MemoryRecallAclConfigService service;

    @BeforeEach
    void setUp() {
        service = new MemoryRecallAclConfigService(recallAclMapper, memberMapper);
    }

    private MemoryProjectMember member(long userId, String role, Boolean recallAdmin, String status) {
        MemoryProjectMember m = new MemoryProjectMember();
        m.setProjectId(100L);
        m.setUserId(userId);
        m.setRole(role);
        m.setRecallAdmin(recallAdmin);
        m.setStatus(status);
        return m;
    }

    // ===== isConfigurable 配置权边界（向量 14）=====

    @Test
    void isConfigurable_owner_true() {
        when(memberMapper.selectOne(any())).thenReturn(member(1L, "OWNER", false, "ACTIVE"));
        assertTrue(service.isConfigurable(100L, 1L));
    }

    @Test
    void isConfigurable_adminWithRecallAdmin_true() {
        when(memberMapper.selectOne(any())).thenReturn(member(2L, "ADMIN", true, "ACTIVE"));
        assertTrue(service.isConfigurable(100L, 2L));
    }

    @Test
    void isConfigurable_adminWithoutRecallAdmin_false() {
        when(memberMapper.selectOne(any())).thenReturn(member(2L, "ADMIN", false, "ACTIVE"));
        assertFalse(service.isConfigurable(100L, 2L));
    }

    @Test
    void isConfigurable_member_false() {
        when(memberMapper.selectOne(any())).thenReturn(member(3L, "MEMBER", false, "ACTIVE"));
        assertFalse(service.isConfigurable(100L, 3L));
    }

    @Test
    void isConfigurable_nonMember_false() {
        when(memberMapper.selectOne(any())).thenReturn(null);
        assertFalse(service.isConfigurable(100L, 999L));
    }

    @Test
    void isConfigurable_nullArgs_false() {
        assertFalse(service.isConfigurable(null, 1L));
        assertFalse(service.isConfigurable(100L, null));
        verifyNoInteractions(memberMapper);
    }

    // ===== replaceAll 全量替换 =====

    @Test
    void replaceAll_fullReplace_deletesOldInsertsNew_withAudit() {
        when(memberMapper.selectOne(any())).thenReturn(member(2L, "ADMIN", true, "ACTIVE"));
        when(memberMapper.selectList(any())).thenReturn(List.of(
                member(1L, "OWNER", false, "ACTIVE"),
                member(2L, "ADMIN", true, "ACTIVE"),
                member(10L, "MEMBER", false, "ACTIVE"),
                member(11L, "MEMBER", false, "ACTIVE")));
        when(recallAclMapper.deleteByProjectAndReader(100L, 2L)).thenReturn(3);
        MemoryRecallAclRequest req = new MemoryRecallAclRequest();
        req.setReaderUserId(2L);
        req.setTargetUserIds(List.of(10L, 11L));

        int written = service.replaceAll(100L, 2L, req, 99L);

        assertEquals(2, written);
        verify(recallAclMapper).deleteByProjectAndReader(100L, 2L);
        ArgumentCaptor<MemoryRecallAcl> captor = ArgumentCaptor.forClass(MemoryRecallAcl.class);
        verify(recallAclMapper, times(2)).insert(captor.capture());
        // created_by 审计（向量 15）
        assertTrue(captor.getAllValues().stream().allMatch(r -> Long.valueOf(99L).equals(r.getCreatedBy())));
        // project_id + reader 写对
        assertTrue(captor.getAllValues().stream().allMatch(r -> Long.valueOf(100L).equals(r.getProjectId())
                && Long.valueOf(2L).equals(r.getReaderUserId())));
    }

    @Test
    void replaceAll_filtersNonMemberTargets() {
        // target 999 非项目成员 → 静默滤掉，只插成员
        when(memberMapper.selectOne(any())).thenReturn(member(2L, "ADMIN", true, "ACTIVE"));
        when(memberMapper.selectList(any())).thenReturn(List.of(
                member(2L, "ADMIN", true, "ACTIVE"), member(10L, "MEMBER", false, "ACTIVE")));
        when(recallAclMapper.deleteByProjectAndReader(anyLong(), anyLong())).thenReturn(0);
        MemoryRecallAclRequest req = new MemoryRecallAclRequest();
        req.setReaderUserId(2L);
        req.setTargetUserIds(List.of(10L, 999L));  // 999 非成员

        int written = service.replaceAll(100L, 2L, req, 99L);

        assertEquals(1, written);
        verify(recallAclMapper, times(1)).insert(any());
    }

    @Test
    void replaceAll_keepsDepartedTargets() {
        // DEPARTED 成员仍是合法 target（保交接，§3.7）
        MemoryProjectMember departed = member(11L, "MEMBER", false, "DEPARTED");
        when(memberMapper.selectOne(any())).thenReturn(member(2L, "ADMIN", true, "ACTIVE"));
        when(memberMapper.selectList(any())).thenReturn(List.of(
                member(2L, "ADMIN", true, "ACTIVE"), departed));
        when(recallAclMapper.deleteByProjectAndReader(anyLong(), anyLong())).thenReturn(0);
        MemoryRecallAclRequest req = new MemoryRecallAclRequest();
        req.setReaderUserId(2L);
        req.setTargetUserIds(List.of(11L));

        int written = service.replaceAll(100L, 2L, req, 99L);

        assertEquals(1, written);  // DEPARTED target 保留
    }

    @Test
    void replaceAll_emptyTargets_clearsRights_onlyDelete() {
        // 空集 = 清权（删旧不插新，合法）
        when(memberMapper.selectOne(any())).thenReturn(member(2L, "ADMIN", true, "ACTIVE"));
        when(recallAclMapper.deleteByProjectAndReader(100L, 2L)).thenReturn(2);
        MemoryRecallAclRequest req = new MemoryRecallAclRequest();
        req.setReaderUserId(2L);
        req.setTargetUserIds(List.of());

        int written = service.replaceAll(100L, 2L, req, 99L);

        assertEquals(0, written);
        verify(recallAclMapper).deleteByProjectAndReader(100L, 2L);
        verify(recallAclMapper, never()).insert(any());
    }

    @Test
    void replaceAll_readerNonMember_skipNoDeleteNoInsert() {
        // reader 非项目成员 → 不删不插（防配非成员读者的权）
        when(memberMapper.selectOne(any())).thenReturn(null);

        MemoryRecallAclRequest req = new MemoryRecallAclRequest();
        req.setReaderUserId(999L);
        req.setTargetUserIds(List.of(10L));

        int written = service.replaceAll(100L, 999L, req, 99L);

        assertEquals(0, written);
        verify(recallAclMapper, never()).deleteByProjectAndReader(anyLong(), anyLong());
        verify(recallAclMapper, never()).insert(any());
    }

    @Test
    void replaceAll_distinctDedup() {
        // 同 target 重复传 → 去重只插一次
        when(memberMapper.selectOne(any())).thenReturn(member(2L, "ADMIN", true, "ACTIVE"));
        when(memberMapper.selectList(any())).thenReturn(List.of(
                member(2L, "ADMIN", true, "ACTIVE"), member(10L, "MEMBER", false, "ACTIVE")));
        when(recallAclMapper.deleteByProjectAndReader(anyLong(), anyLong())).thenReturn(0);
        MemoryRecallAclRequest req = new MemoryRecallAclRequest();
        req.setReaderUserId(2L);
        req.setTargetUserIds(List.of(10L, 10L, 10L));

        int written = service.replaceAll(100L, 2L, req, 99L);

        assertEquals(1, written);
    }

    @Test
    void replaceAll_nullArgs_zero() {
        assertEquals(0, service.replaceAll(null, 2L, new MemoryRecallAclRequest(), 99L));
        assertEquals(0, service.replaceAll(100L, null, new MemoryRecallAclRequest(), 99L));
        verifyNoInteractions(recallAclMapper);
    }

    @Test
    void getMatrix_delegates() {
        service.getMatrix(100L);
        verify(recallAclMapper).findGrantedDetails(100L);
    }

    @Test
    void getMatrix_null_empty() {
        assertTrue(service.getMatrix(null).isEmpty());
        verifyNoInteractions(recallAclMapper);
    }
}
