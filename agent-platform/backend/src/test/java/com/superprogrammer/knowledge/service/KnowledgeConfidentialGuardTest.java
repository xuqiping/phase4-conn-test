package com.superprogrammer.knowledge.service;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.knowledge.dto.KnowledgeBaseRequest;
import com.superprogrammer.knowledge.dto.KnowledgeBaseVO;
import com.superprogrammer.knowledge.dto.KnowledgeDocumentVO;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.entity.KnowledgeNode;
import com.superprogrammer.knowledge.dto.KnowledgeNodeVO;
import com.superprogrammer.knowledge.mapper.KnowledgeBaseMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeNodeMapper;
import com.superprogrammer.knowledge.mapper.KnowledgePermissionMapper;
import com.superprogrammer.llm.service.LlmProviderService;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 14x#3 · 库级保密 spec §5.3 收紧矩阵（服务端强制）：
 * 列表/单查剔除 fileRef、asset 403、nodes 403；owner/admin 直通；PUBLIC 互斥；非保密库零变化。
 * retrieve 调试 403 与问答放行在 RagRetrieveConfidentialTest（mock 脚手架不同）。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeConfidentialGuardTest {

    private static final Long KB_ID = 1L;
    private static final Long DOC_ID = 9L;
    private static final Long OWNER = 7L;
    private static final Long MEMBER = 8L;

    // Document/Node 服务（KnowledgeBaseService 为 mock；Guard 是静态真实逻辑）
    @Mock private KnowledgeBaseService knowledgeBaseService;
    @Mock private KnowledgeDocumentMapper documentMapper;
    @Mock private KnowledgeNodeMapper nodeMapper;
    @Mock private com.superprogrammer.file.service.FileStorageService fileStorageService;

    // KnowledgeBaseService 直测（PUBLIC 互斥 + 开关落库；documentMapper 复用上面 mock）
    @Mock private KnowledgeBaseMapper baseMapper;
    @Mock private KnowledgePermissionMapper permissionMapper;
    @Mock private LlmProviderService llmProviderService;
    @Mock private VisibilitySetService visibilitySetService;
    @Mock private SystemSettingService systemSettingService;
    @InjectMocks private KnowledgeBaseService kbService;

    private KnowledgeBase confidentialKb() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(KB_ID);
        kb.setName("sec-kb");
        kb.setVisibility("TEAM");
        kb.setConfidential(true);
        kb.setCreatedBy(OWNER);
        return kb;
    }

    private KnowledgeDocument doc() {
        KnowledgeDocument d = new KnowledgeDocument();
        d.setId(DOC_ID);
        d.setKbId(KB_ID);
        d.setTitle("秘密文档");
        d.setDocType("FILE");
        d.setStatus("INDEXED");
        d.setFileRef("/api/files/f1");
        return d;
    }

    private void stubReadable(KnowledgeBase kb) {
        when(knowledgeBaseService.ensure(KB_ID)).thenReturn(kb);
        lenient().when(knowledgeBaseService.canRead(eq(kb), anyLong(), anyBoolean())).thenReturn(true);
    }

    private KnowledgeDocumentService docService() {
        return new KnowledgeDocumentService(documentMapper, nodeMapper, null, null, knowledgeBaseService,
                fileStorageService, null, null, null, null, null);
    }

    // ==================== Guard 单元矩阵 ====================

    @Test
    void guard_matrix() {
        KnowledgeBase kb = confidentialKb();
        assertTrue(KnowledgeConfidentialGuard.isRestricted(kb, MEMBER, false), "保密库成员受限");
        assertFalse(KnowledgeConfidentialGuard.isRestricted(kb, OWNER, false), "owner 直通");
        assertFalse(KnowledgeConfidentialGuard.isRestricted(kb, MEMBER, true), "admin 直通");
        kb.setConfidential(false);
        assertFalse(KnowledgeConfidentialGuard.isRestricted(kb, MEMBER, false), "非保密库零变化");
        assertDoesNotThrow(() -> KnowledgeConfidentialGuard.assertCanViewContent(confidentialKb(), OWNER, false));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> KnowledgeConfidentialGuard.assertCanViewContent(confidentialKb(), MEMBER, false));
        assertEquals(ErrorCode.KNOWLEDGE_CONFIDENTIAL_DENIED.getCode(), ex.getCode());
    }

    // ==================== 行1：文档列表/单查 → 保留元数据、剔除 fileRef ====================

    @Test
    void list_memberOfConfidentialKb_stripsFileRefKeepsMetadata() {
        stubReadable(confidentialKb());
        KnowledgeDocumentService service = docService();
        when(documentMapper.selectList(any())).thenReturn(List.of(doc()));
        when(documentMapper.selectById(DOC_ID)).thenReturn(doc());
        lenient().when(fileStorageService.findMeta(any())).thenReturn(null);

        List<KnowledgeDocumentVO> vos = service.list(KB_ID, MEMBER, false);
        assertEquals(1, vos.size());
        assertNull(vos.get(0).getFileRef(), "保密库成员列表剔除 fileRef");
        assertEquals("秘密文档", vos.get(0).getTitle(), "元数据保留（目录索引）");
        assertEquals("INDEXED", vos.get(0).getStatus());
        assertNull(service.get(DOC_ID, MEMBER, false).getFileRef(), "单查同语义剔除");

        List<KnowledgeDocumentVO> ownerVos = service.list(KB_ID, OWNER, false);
        assertEquals("/api/files/f1", ownerVos.get(0).getFileRef(), "owner 不剔除");
    }

    // ==================== 行2：asset 下载原件 → 403 ====================

    @Test
    void asset_memberOfConfidentialKb_denied403_ownerPassesGuard() {
        stubReadable(confidentialKb());
        KnowledgeDocumentService service = docService();
        when(documentMapper.selectById(DOC_ID)).thenReturn(doc());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.streamAsset(DOC_ID, MEMBER, false));
        assertEquals(ErrorCode.KNOWLEDGE_CONFIDENTIAL_DENIED.getCode(), ex.getCode());
        verify(fileStorageService, never()).load(any(), any(), anyBoolean());

        // owner 越过保密门：fileRef 置空让后续抛 NOT_FOUND「文档无原件」——证明守卫已放行
        KnowledgeDocument noRef = doc();
        noRef.setFileRef(null);
        when(documentMapper.selectById(DOC_ID)).thenReturn(noRef);
        BusinessException ownerEx = assertThrows(BusinessException.class,
                () -> service.streamAsset(DOC_ID, OWNER, false));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ownerEx.getCode());
    }

    // ==================== 行3：nodes 切片原文 → 403 ====================

    @Test
    void nodes_memberOfConfidentialKb_denied403_ownerSeesNodes() {
        stubReadable(confidentialKb());
        when(documentMapper.selectById(DOC_ID)).thenReturn(doc());
        when(nodeMapper.selectList(any())).thenReturn(List.of(new KnowledgeNode()));
        KnowledgeNodeService service = new KnowledgeNodeService(nodeMapper, documentMapper, null, knowledgeBaseService, null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.listByDocument(DOC_ID, MEMBER, false));
        assertEquals(ErrorCode.KNOWLEDGE_CONFIDENTIAL_DENIED.getCode(), ex.getCode());

        List<KnowledgeNodeVO> owner = service.listByDocument(DOC_ID, OWNER, false);
        assertEquals(1, owner.size(), "owner 直通看切片");
    }

    // ==================== PUBLIC 互斥 + 开关落库 ====================

    @Test
    void create_publicWithConfidential_rejected() {
        KnowledgeBaseRequest r = new KnowledgeBaseRequest();
        r.setName("pub-sec");
        r.setVisibility("PUBLIC");
        r.setConfidential(true);
        r.setEmbeddingModel("emb");
        when(baseMapper.selectCount(any())).thenReturn(0L);

        BusinessException ex = assertThrows(BusinessException.class, () -> kbService.create(r, OWNER));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        verify(baseMapper, never()).insert(any(KnowledgeBase.class));
    }

    @Test
    void update_confidentialToggleLands_andOffRestores() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(KB_ID);
        kb.setName("kb1");
        kb.setVisibility("TEAM");
        kb.setCreatedBy(OWNER);
        kb.setEmbeddingModel("emb");
        when(baseMapper.selectById(KB_ID)).thenReturn(kb);
        when(baseMapper.updateById(any(KnowledgeBase.class))).thenReturn(1);

        KnowledgeBaseRequest on = new KnowledgeBaseRequest();
        on.setName("kb1");
        on.setConfidential(true);
        assertTrue(kbService.update(KB_ID, on, OWNER, true).isConfidential(), "开关闭合落库且 VO 回显");

        KnowledgeBaseRequest off = new KnowledgeBaseRequest();
        off.setName("kb1");
        off.setConfidential(false);
        assertFalse(kbService.update(KB_ID, off, OWNER, true).isConfidential(), "可逆：OFF 恢复");
    }

    @Test
    void update_nullConfidential_keepsExistingSwitch() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(KB_ID);
        kb.setName("kb1");
        kb.setVisibility("TEAM");
        kb.setConfidential(true);
        kb.setCreatedBy(OWNER);
        kb.setEmbeddingModel("emb");
        when(baseMapper.selectById(KB_ID)).thenReturn(kb);
        when(baseMapper.updateById(any(KnowledgeBase.class))).thenReturn(1);

        KnowledgeBaseRequest r = new KnowledgeBaseRequest();  // 不带 confidential 字段（旧客户端）
        r.setName("kb1");
        assertTrue(kbService.update(KB_ID, r, OWNER, true).isConfidential(), "null=不动既有开关（向后兼容）");
    }

    @Test
    void update_publicKbToggleConfidentialOn_rejected() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(KB_ID);
        kb.setName("pub");
        kb.setVisibility("PUBLIC");
        kb.setCreatedBy(OWNER);
        kb.setEmbeddingModel("emb");
        when(baseMapper.selectById(KB_ID)).thenReturn(kb);

        KnowledgeBaseRequest r = new KnowledgeBaseRequest();
        r.setName("pub");
        r.setConfidential(true);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> kbService.update(KB_ID, r, OWNER, true));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }
}
