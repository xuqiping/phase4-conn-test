package com.superprogrammer.knowledge.service;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.knowledge.dto.KnowledgeBaseRequest;
import com.superprogrammer.knowledge.dto.KnowledgeBaseVO;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.entity.KnowledgePermission;
import com.superprogrammer.knowledge.mapper.KnowledgeBaseMapper;
import com.superprogrammer.knowledge.mapper.KnowledgePermissionMapper;
import com.superprogrammer.knowledge.service.internal.VisibleDocSet;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 14x#2 只读越权修复 · 授权语义 9 格矩阵回归。
 * 原谓词 canRead ‖ (requireWrite && canWrite)：只读授权即可通过写检查。
 * 修复后档位严格：READ=任一位；WRITE=canWrite/canManage；MANAGE=仅 canManage；
 * KB 改名/删除仅 owner/admin（授予位不含销毁库）。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceGrantMatrixTest {

    private static final Long KB_ID = 5L;
    private static final Long OWNER_ID = 1L;
    private static final Long GRANTEE_ID = 2L;

    @Mock private KnowledgeBaseMapper baseMapper;
    @Mock private KnowledgePermissionMapper permissionMapper;
    @Mock private VisibilitySetService visibilitySetService;
    @Mock private SystemSettingService systemSettingService;

    @InjectMocks
    private KnowledgeBaseService service;

    private KnowledgeBase kb;

    @BeforeEach
    void setUp() {
        kb = new KnowledgeBase();
        kb.setId(KB_ID);
        kb.setTenantId(1L);
        kb.setName("kb-1");
        kb.setVisibility("PRIVATE");
        kb.setStatus("ACTIVE");
        kb.setCreatedBy(OWNER_ID);
    }

    private static KnowledgePermission grant(Boolean read, Boolean write, Boolean manage) {
        KnowledgePermission p = new KnowledgePermission();
        p.setTargetType("KB");
        p.setTargetId(KB_ID);
        p.setSubjectType("USER");
        p.setSubjectId(GRANTEE_ID);
        p.setCanRead(read);
        p.setCanWrite(write);
        p.setCanManage(manage);
        return p;
    }

    private void stubGrants(KnowledgePermission... perms) {
        when(permissionMapper.selectList(any())).thenReturn(List.of(perms));
    }

    // ---------- 9 格矩阵：授权位 × 读/写/治理 ----------

    @Test
    @DisplayName("canRead 授权：读 ✅ / 写 ❌ / 治理 ❌（越权修复核心回归）")
    void readGrant_readOnly_matrix() {
        stubGrants(grant(true, false, false));

        assertFalse(service.canWrite(kb, GRANTEE_ID, false), "canRead 授权不得通过写检查");
        assertFalse(service.canManage(kb, GRANTEE_ID, false));
        // 读走可见集权威（hasKbLevelRead 含 KB 级 USER 授权）
        when(visibilitySetService.getVisibleDocs(KB_ID, GRANTEE_ID, false))
                .thenReturn(VisibleDocSet.all());
        assertTrue(service.canRead(kb, GRANTEE_ID, false));
    }

    @Test
    @DisplayName("canWrite 授权：写 ✅ / 治理 ❌（读档位由可见集权威另行覆盖）")
    void writeGrant_matrix() {
        stubGrants(grant(false, true, false));

        assertTrue(service.canWrite(kb, GRANTEE_ID, false));
        assertFalse(service.canManage(kb, GRANTEE_ID, false));
    }

    @Test
    @DisplayName("canManage 授权：写 ✅ / 治理 ✅（高位含低位）")
    void manageGrant_matrix() {
        stubGrants(grant(false, false, true));

        assertTrue(service.canManage(kb, GRANTEE_ID, false));
        assertTrue(service.canWrite(kb, GRANTEE_ID, false));
    }

    @Test
    @DisplayName("无任何授权：三态全 false")
    void noGrant_allDenied() {
        stubGrants();

        assertFalse(service.canWrite(kb, GRANTEE_ID, false));
        assertFalse(service.canManage(kb, GRANTEE_ID, false));
    }

    // ---------- owner/admin 直通 ----------

    @Test
    @DisplayName("owner：三态全 true，零授权表查询（短路）")
    void owner_allTrue_noGrantQuery() {
        assertTrue(service.canManage(kb, OWNER_ID, false));
        assertTrue(service.canWrite(kb, OWNER_ID, false));
        assertTrue(service.canRead(kb, OWNER_ID, false));
        verifyNoInteractions(permissionMapper);
        verifyNoInteractions(visibilitySetService);
    }

    @Test
    @DisplayName("admin：三态全 true，零授权表查询")
    void admin_allTrue_noGrantQuery() {
        assertTrue(service.canManage(kb, 999L, true));
        assertTrue(service.canWrite(kb, 999L, true));
        assertTrue(service.canRead(kb, 999L, true));
        verifyNoInteractions(permissionMapper);
    }

    // ---------- canManage 授予位边界：不含销毁库 ----------

    @Test
    @DisplayName("canManage 授予者不能删库（仅 owner/admin，不查授权表）")
    void manageGrant_cannotDeleteKb() {
        when(baseMapper.selectById(KB_ID)).thenReturn(kb);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.delete(KB_ID, GRANTEE_ID, false));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
        verify(baseMapper, never()).deleteById(any(Long.class));
        verifyNoInteractions(permissionMapper);
    }

    @Test
    @DisplayName("canManage 授予者不能改库元数据（改名/可见性/模型，不查授权表）")
    void manageGrant_cannotRenameKb() {
        when(baseMapper.selectById(KB_ID)).thenReturn(kb);
        KnowledgeBaseRequest request = new KnowledgeBaseRequest();
        request.setName("改名");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(KB_ID, request, GRANTEE_ID, false));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
        verify(baseMapper, never()).updateById(any(KnowledgeBase.class));
        verifyNoInteractions(permissionMapper);
    }

    @Test
    @DisplayName("canManage 授予者可管理授权链（assertManageTarget 依据 canManage 放行）")
    void manageGrant_canManageGrants() {
        stubGrants(grant(false, false, true));
        when(baseMapper.selectById(KB_ID)).thenReturn(kb);
        assertTrue(service.canManage(KB_ID, GRANTEE_ID, false));
    }

    // ---------- VO 透出 ----------

    @Test
    @DisplayName("VO.canWrite 随授权位：写授权 true/管理 false；读授权两 false")
    void vo_canWriteFlag_perGrant() {
        when(baseMapper.selectById(KB_ID)).thenReturn(kb);

        // 写授权
        stubGrants(grant(false, true, false));
        when(visibilitySetService.getVisibleDocs(eq(KB_ID), eq(GRANTEE_ID), eq(false)))
                .thenReturn(VisibleDocSet.all());
        KnowledgeBaseVO vo = service.get(KB_ID, GRANTEE_ID, false);
        assertTrue(vo.isCanWrite());
        assertFalse(vo.isCanManage());

        // 只读授权
        stubGrants(grant(true, false, false));
        vo = service.get(KB_ID, GRANTEE_ID, false);
        assertFalse(vo.isCanWrite());
        assertFalse(vo.isCanManage());
        assertTrue(vo.isCanRead());
    }
}
