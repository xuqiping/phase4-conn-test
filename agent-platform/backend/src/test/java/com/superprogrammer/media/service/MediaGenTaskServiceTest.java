package com.superprogrammer.media.service;

import com.superprogrammer.common.metrics.BizMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.asset.service.AssetService;
import com.superprogrammer.billing.service.PointsWalletService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.media.config.MediaGenProperties;
import com.superprogrammer.media.config.MediaModelCapabilityService;
import com.superprogrammer.media.dto.AttachmentRef;
import com.superprogrammer.media.entity.MediaGenTask;
import com.superprogrammer.media.mapper.MediaGenTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.superprogrammer.billing.service.InflightGateService;

/**
 * MediaGenTaskService 单测：模型路由 + 能力校验 + 附件归属校验 + taskType 派生。
 * 覆盖：模型不在任何 provider → 400；分类/总数超限 → 400；非法 kind → 400；
 * 他人附件 → 403；attachments+refFileId 互斥；旧 refFileId 路径回归；空 model 回退默认。
 */
@ExtendWith(MockitoExtension.class)
class MediaGenTaskServiceTest {

    private static final Long USER_ID = 10L;
    private static final String SEEDANCE_2 = "doubao-seedance-2-0-260128";

    @Mock
    private MediaGenTaskMapper taskMapper;
    @Mock
    private MediaModelService mediaModelService;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private AssetService assetService;
    @Mock
    private PointsWalletService walletService;
    @Mock
    private InflightGateService inflightGate;
    @Mock
    private com.superprogrammer.media.service.internal.MediaInflightGateService mediaInflightGate;
    @Mock
    private BizMetrics bizMetrics;
    @Mock
    private com.superprogrammer.common.audit.AuditLogService auditLogService;
    /** 计划5 Step5：组池预检/估价 mock（估价缺价路径走 catch 记 0，不触发 NPE）。 */
    @Mock
    private com.superprogrammer.projectgroup.service.ProjectGroupWalletService groupWalletService;
    @Mock
    private com.superprogrammer.projectgroup.service.ProjectGroupService projectGroupService;
    @Mock
    private com.superprogrammer.billing.service.PricingService pricingService;
    @Mock
    private com.superprogrammer.billing.service.PointsRatioService pointsRatioService;
    @Mock
    private com.superprogrammer.billing.service.MediaBillingService mediaBillingService;
    /** C1（17x-2）：预估个人口径——成员行 + 管理可分配额度 mock。 */
    @Mock
    private com.superprogrammer.projectgroup.mapper.ProjectGroupMemberMapper memberMapper;
    @Mock
    private com.superprogrammer.projectgroup.service.MemberBudgetService memberBudgetService;

    private MediaGenTaskService service;
    private LlmProviderEntity provider;

    @BeforeEach
    void setUp() {
        provider = new LlmProviderEntity();
        provider.setId(7L);
        provider.setName("seedance");
        provider.setModels("[\"" + SEEDANCE_2 + "\"]");

        MediaGenProperties properties = new MediaGenProperties();
        properties.getReference().setPublicBaseUrl("https://media.example.com");
        properties.getReference().setSigningKey("test-secret-at-least-32-bytes-long");
        service = new MediaGenTaskService(
                taskMapper, mediaModelService,
                new MediaModelCapabilityService(new ObjectMapper()),
                fileStorageService, properties, new ObjectMapper(), assetService, walletService,
                inflightGate, mediaInflightGate, bizMetrics, auditLogService,
                groupWalletService, projectGroupService, pricingService, pointsRatioService,
                mediaBillingService, memberMapper, memberBudgetService);

        // 默认：指定模型可路由到 seedance provider；附件元数据归属当前用户
        lenient().when(mediaModelService.resolveProviderByModel(SEEDANCE_2)).thenReturn(provider);
        lenient().when(mediaModelService.defaultProvider()).thenReturn(provider);
        lenient().when(mediaModelService.firstModelOf(provider)).thenReturn(SEEDANCE_2);
    }

    private AttachmentRef att(String fileId, String kind) {
        AttachmentRef a = new AttachmentRef();
        a.setFileId(fileId);
        a.setKind(kind);
        return a;
    }

    /** 带 frameRole 的附件构造（B1 附件级首/尾帧）。 */
    private AttachmentRef att(String fileId, String kind, String frameRole) {
        AttachmentRef a = att(fileId, kind);
        a.setFrameRole(frameRole);
        return a;
    }

    private void stubOwnedFile(String fileId, String mime) {
        StoredFileEntity meta = new StoredFileEntity();
        meta.setFileId(fileId);
        meta.setOwnerUserId(USER_ID);
        meta.setMime(mime);
        meta.setOriginalName(fileId);
        // lenient：总数/类型白名单校验先于归属校验短路时，部分桩不会命中
        lenient().when(fileStorageService.findMeta(fileId)).thenReturn(meta);
    }

    private List<AttachmentRef> images(int n) {
        List<AttachmentRef> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String fid = "img-" + i + ".png";
            stubOwnedFile(fid, "image/png");
            list.add(att(fid, "image"));
        }
        return list;
    }

    @Test
    void submit_withAttachments_derivesImage2VideoAndPersists() {
        List<AttachmentRef> attachments = images(2);
        stubOwnedFile("v1.mp4", "video/mp4");
        stubOwnedFile("a1.mp3", "audio/mpeg");
        attachments.add(att("v1.mp4", "video"));
        attachments.add(att("a1.mp3", "audio"));

        service.submit("以图1为产品参考", "16:9", 5, "720p", false, false,
                null, null, attachments, SEEDANCE_2, USER_ID, false);

        ArgumentCaptor<MediaGenTask> captor = ArgumentCaptor.forClass(MediaGenTask.class);
        verify(taskMapper).insert(captor.capture());
        MediaGenTask task = captor.getValue();
        assertEquals(MediaGenTask.TYPE_IMAGE2VIDEO, task.getTaskType());
        assertEquals(SEEDANCE_2, task.getModel());
        assertEquals(7L, task.getProviderId());
        assertTrue(task.getRequestConfig().contains("\"attachments\""));
        assertTrue(task.getRequestConfig().contains("v1.mp4"));
    }

    @Test
    void submit_attachmentFrameRole_persistsFirstAndLast() {
        // B1：首帧+尾帧同请求合法（无参考图），frameRole 落 config。
        // 注意：SeedDance 2.0 契约——last_frame 与 reference_image 互斥（Phase4 真跑确认），
        // 故首+尾帧组合不再带参考图；带参考图的合法组合见 submit_firstFramePlusReferenceAccepted。
        List<AttachmentRef> attachments = new ArrayList<>();
        attachments.add(att("first.png", "image", "first_frame"));
        attachments.add(att("last.png", "image", "last_frame"));
        attachments.forEach(a -> stubOwnedFile(a.getFileId(), "image/png"));

        service.submit("首尾帧", "16:9", 5, "720p", false, false,
                null, null, attachments, SEEDANCE_2, USER_ID, false);

        ArgumentCaptor<MediaGenTask> captor = ArgumentCaptor.forClass(MediaGenTask.class);
        verify(taskMapper).insert(captor.capture());
        String cfg = captor.getValue().getRequestConfig();
        assertTrue(cfg.contains("first_frame"), "首帧 frameRole 须落库");
        assertTrue(cfg.contains("last_frame"), "尾帧 frameRole 须落库");
    }

    @Test
    void submit_firstFramePlusReferenceImage_400() {
        List<AttachmentRef> attachments = new ArrayList<>();
        attachments.add(att("first.png", "image", "first_frame"));
        attachments.add(att("ref.png", "image", null));
        attachments.forEach(a -> stubOwnedFile(a.getFileId(), "image/png"));

        BusinessException e = assertThrows(BusinessException.class, () ->
                service.submit("首帧+参考图", "16:9", 5, "720p", false, false,
                        null, null, attachments, SEEDANCE_2, USER_ID, false));
        assertTrue(e.getMessage().contains("参考媒体") || e.getMessage().contains("互斥"));
        verify(taskMapper, never()).insert(any());
    }

    @Test
    void submit_firstFramePlusReferenceVideo_400() {
        List<AttachmentRef> attachments = List.of(
                att("first.png", "image", "first_frame"),
                att("ref.mp4", "video"));
        stubOwnedFile("first.png", "image/png");
        stubOwnedFile("ref.mp4", "video/mp4");

        BusinessException e = assertThrows(BusinessException.class, () ->
                service.submit("首帧+参考视频", "16:9", 5, "720p", false, false,
                        null, null, attachments, SEEDANCE_2, USER_ID, false));
        assertTrue(e.getMessage().contains("参考媒体") || e.getMessage().contains("互斥"));
        verify(taskMapper, never()).insert(any());
    }

    @Test
    void submit_lastFramePlusReferenceAudio_400() {
        List<AttachmentRef> attachments = List.of(
                att("last.png", "image", "last_frame"),
                att("ref.mp3", "audio"));
        stubOwnedFile("last.png", "image/png");
        stubOwnedFile("ref.mp3", "audio/mpeg");

        BusinessException e = assertThrows(BusinessException.class, () ->
                service.submit("尾帧+参考音频", "16:9", 5, "720p", false, false,
                        null, null, attachments, SEEDANCE_2, USER_ID, false));
        assertTrue(e.getMessage().contains("参考媒体") || e.getMessage().contains("互斥"));
        verify(taskMapper, never()).insert(any());
    }

    @Test
    void submit_lastFramePlusReference_400() {
        // SeedDance 2.0 契约（Phase4 真跑确认）：last_frame 与 reference_image 互斥——
        // ctaigw 对「尾帧+参考图」返 400 "last frame image content cannot be mixed with
        // reference image"。service 前置拦截，给中文提示，不透传网关英文 400。
        List<AttachmentRef> attachments = new ArrayList<>();
        attachments.add(att("last.png", "image", "last_frame"));
        attachments.add(att("ref.png", "image", null));
        attachments.forEach(a -> stubOwnedFile(a.getFileId(), "image/png"));

        BusinessException e = assertThrows(BusinessException.class, () ->
                service.submit("尾帧+参考图", "16:9", 5, "720p", false, false,
                        null, null, attachments, SEEDANCE_2, USER_ID, false));
        assertTrue(e.getMessage().contains("参考媒体") || e.getMessage().contains("互斥"),
                "须提示帧模式与参考媒体互斥，实际: " + e.getMessage());
    }

    @Test
    void submit_twoFirstFrames_400() {
        // 全局首帧 ≤1
        List<AttachmentRef> attachments = new ArrayList<>();
        attachments.add(att("f1.png", "image", "first_frame"));
        attachments.add(att("f2.png", "image", "first_frame"));
        attachments.forEach(a -> stubOwnedFile(a.getFileId(), "image/png"));

        BusinessException e = assertThrows(BusinessException.class, () ->
                service.submit("p", "16:9", 5, "720p", false, false,
                        null, null, attachments, SEEDANCE_2, USER_ID, false));
        assertTrue(e.getMessage().contains("首帧最多 1 张"));
    }

    @Test
    void submit_frameRoleOnVideo_ignoredNotStored() {
        // frameRole 配在 video 上：normalizeFrameRole 非 image → null，不报错也不落 role
        stubOwnedFile("v.mp4", "video/mp4");
        List<AttachmentRef> attachments = new ArrayList<>();
        attachments.add(att("v.mp4", "video", "first_frame"));

        service.submit("p", "16:9", 5, "720p", false, false,
                null, null, attachments, SEEDANCE_2, USER_ID, false);

        ArgumentCaptor<MediaGenTask> captor = ArgumentCaptor.forClass(MediaGenTask.class);
        verify(taskMapper).insert(captor.capture());
        assertFalse(captor.getValue().getRequestConfig().contains("frameRole"),
                "video 附件的 frameRole 必须被忽略，不入 config");
    }

    @Test
    void submit_insufficientBalance_rejectedBeforeInsert() {
        // Chunk F 联动：余额≤0 → requireAffordable 抛 INSUFFICIENT_POINTS，task 不建（insert 不调）
        doThrow(new BusinessException(ErrorCode.INSUFFICIENT_POINTS)).when(walletService)
                .requireAffordable(USER_ID);

        BusinessException e = assertThrows(BusinessException.class, () ->
                service.submit("p", "16:9", 5, "720p", false, false,
                        null, null, null, SEEDANCE_2, USER_ID, false));
        assertEquals(ErrorCode.INSUFFICIENT_POINTS.getCode(), e.getCode());
        verify(taskMapper, never()).insert(any());
    }

    @Test
    void submit_modelNotInAnyProvider_400() {
        when(mediaModelService.resolveProviderByModel("unknown-model")).thenReturn(null);
        BusinessException e = assertThrows(BusinessException.class, () ->
                service.submit("p", "16:9", 5, "720p", false, false,
                        null, null, null, "unknown-model", USER_ID, false));
        assertTrue(e.getMessage().contains("模型不可用"));
    }

    @Test
    void submit_blankModel_fallsBackToDefaultProvider() {
        service.submit("p", "16:9", 5, "720p", false, false,
                null, null, null, null, USER_ID, false);
        ArgumentCaptor<MediaGenTask> captor = ArgumentCaptor.forClass(MediaGenTask.class);
        verify(taskMapper).insert(captor.capture());
        assertEquals(SEEDANCE_2, captor.getValue().getModel());
        assertEquals(MediaGenTask.TYPE_TEXT2VIDEO, captor.getValue().getTaskType());
    }

    @Test
    void submit_tenImages_400() {
        BusinessException e = assertThrows(BusinessException.class, () ->
                service.submit("p", "16:9", 5, "720p", false, false,
                        null, null, images(10), SEEDANCE_2, USER_ID, false));
        assertTrue(e.getMessage().contains("参考图超限"));
    }

    @Test
    void submit_thirteenAttachments_400Total() {
        List<AttachmentRef> list = images(9);
        for (int i = 0; i < 3; i++) {
            String fid = "v" + i + ".mp4";
            stubOwnedFile(fid, "video/mp4");
            list.add(att(fid, "video"));
        }
        String extra = "a9.mp3";
        stubOwnedFile(extra, "audio/mpeg");
        list.add(att(extra, "audio")); // 9+3+1 = 13 > 12
        BusinessException e = assertThrows(BusinessException.class, () ->
                service.submit("p", "16:9", 5, "720p", false, false,
                        null, null, list, SEEDANCE_2, USER_ID, false));
        assertTrue(e.getMessage().contains("附件总数超限"));
    }

    @Test
    void submit_badKind_400() {
        stubOwnedFile("f1.txt", "text/plain");
        BusinessException e = assertThrows(BusinessException.class, () ->
                service.submit("p", "16:9", 5, "720p", false, false,
                        null, null, List.of(att("f1.txt", "document")), SEEDANCE_2, USER_ID, false));
        assertTrue(e.getMessage().contains("附件类型非法"));
    }

    @Test
    void submit_mimeMismatch_400() {
        stubOwnedFile("fake.png", "video/mp4"); // 声明 image 实为视频
        BusinessException e = assertThrows(BusinessException.class, () ->
                service.submit("p", "16:9", 5, "720p", false, false,
                        null, null, List.of(att("fake.png", "image")), SEEDANCE_2, USER_ID, false));
        assertTrue(e.getMessage().contains("附件类型不符"));
    }

    @Test
    void submit_foreignAttachment_403() {
        StoredFileEntity meta = new StoredFileEntity();
        meta.setFileId("other.png");
        meta.setOwnerUserId(999L); // 他人的文件
        meta.setMime("image/png");
        when(fileStorageService.findMeta("other.png")).thenReturn(meta);
        BusinessException e = assertThrows(BusinessException.class, () ->
                service.submit("p", "16:9", 5, "720p", false, false,
                        null, null, List.of(att("other.png", "image")), SEEDANCE_2, USER_ID, false));
        assertTrue(e.getMessage().contains("无权使用该附件"));
    }

    @Test
    void submit_adminBypassesOwnership() {
        StoredFileEntity meta = new StoredFileEntity();
        meta.setFileId("other.png");
        meta.setOwnerUserId(999L);
        meta.setMime("image/png");
        when(fileStorageService.findMeta("other.png")).thenReturn(meta);
        // admin=true → 不抛
        service.submit("p", "16:9", 5, "720p", false, false,
                null, null, List.of(att("other.png", "image")), SEEDANCE_2, USER_ID, true);
        verify(taskMapper).insert(any(MediaGenTask.class));
    }

    @Test
    void submit_sharedAssetAccessible_passes() {
        // 他人上传的资产文件（owner=999），但当前用户是同项目成员 → 资产 ACL 放行
        StoredFileEntity meta = new StoredFileEntity();
        meta.setFileId("asset-img.png");
        meta.setOwnerUserId(999L);
        meta.setMime("image/png");
        when(fileStorageService.findMeta("asset-img.png")).thenReturn(meta);
        when(assetService.isAttachmentFileAccessible("asset-img.png", USER_ID, false)).thenReturn(true);
        service.submit("p", "16:9", 5, "720p", false, false,
                null, null, List.of(att("asset-img.png", "image")), SEEDANCE_2, USER_ID, false);
        verify(taskMapper).insert(any(MediaGenTask.class));
    }

    @Test
    void submit_sharedAssetNoAccess_403() {
        // 资产文件存在但当前用户非项目成员 → 资产 ACL 不放行 → 403
        StoredFileEntity meta = new StoredFileEntity();
        meta.setFileId("asset-img.png");
        meta.setOwnerUserId(999L);
        meta.setMime("image/png");
        when(fileStorageService.findMeta("asset-img.png")).thenReturn(meta);
        when(assetService.isAttachmentFileAccessible("asset-img.png", USER_ID, false)).thenReturn(false);
        BusinessException e = assertThrows(BusinessException.class, () ->
                service.submit("p", "16:9", 5, "720p", false, false,
                        null, null, List.of(att("asset-img.png", "image")), SEEDANCE_2, USER_ID, false));
        assertTrue(e.getMessage().contains("无权使用该附件"));
    }

    @Test
    void submit_attachmentsWithRefFileId_400Mutex() {
        BusinessException e = assertThrows(BusinessException.class, () ->
                service.submit("p", "16:9", 5, "720p", false, false,
                        "IMAGE2VIDEO", "legacy.png", images(1), SEEDANCE_2, USER_ID, false));
        assertTrue(e.getMessage().contains("互斥"));
    }

    @Test
    void submit_legacyRefFileId_stillWorks() {
        service.submit("p", "16:9", 5, "720p", false, false,
                "IMAGE2VIDEO", "legacy.png", null, SEEDANCE_2, USER_ID, false);
        ArgumentCaptor<MediaGenTask> captor = ArgumentCaptor.forClass(MediaGenTask.class);
        verify(taskMapper).insert(captor.capture());
        assertEquals(MediaGenTask.TYPE_IMAGE2VIDEO, captor.getValue().getTaskType());
        assertTrue(captor.getValue().getRequestConfig().contains("\"refFileId\":\"legacy.png\""));
    }

    @Test
    void submit_generateAudioUnsupported_400() {
        // seedance-1-0-pro 不支持 generate_audio
        String m1 = "doubao-seedance-1-0-pro-250528";
        LlmProviderEntity p1 = new LlmProviderEntity();
        p1.setId(8L);
        p1.setName("seedance1");
        p1.setModels("[\"" + m1 + "\"]");
        when(mediaModelService.resolveProviderByModel(m1)).thenReturn(p1);
        BusinessException e = assertThrows(BusinessException.class, () ->
                service.submit("p", "16:9", 5, "720p", false, true,
                        null, null, null, m1, USER_ID, false));
        assertTrue(e.getMessage().contains("不支持生成音频"));
    }

    @Test
    void submit_resolutionBeyondModel_400() {
        // seedance-2-0-fast 不支持 4K
        String fast = "doubao-seedance-2-0-fast-260128";
        LlmProviderEntity pf = new LlmProviderEntity();
        pf.setId(9L);
        pf.setName("seedance-fast");
        pf.setModels("[\"" + fast + "\"]");
        when(mediaModelService.resolveProviderByModel(fast)).thenReturn(pf);
        BusinessException e = assertThrows(BusinessException.class, () ->
                service.submit("p", "16:9", 5, "4K", false, false,
                        null, null, null, fast, USER_ID, false));
        assertTrue(e.getMessage().contains("不支持分辨率"));
    }

    @Test
    void submit_success_recordsSubmittedMetric() {
        // 指标：落库成功计 mediaSubmit(video)；acquire/校验失败不计（由既有失败用例覆盖）
        service.submit("p", "16:9", 5, "720p", null, null, MediaGenTask.TYPE_TEXT2VIDEO,
                null, null, SEEDANCE_2, 100L, false, null);

        verify(bizMetrics).mediaSubmit("video");
    }

    // ---------- C3 · 每用户媒体并发闸门接入 ----------

    // C3：闸门拒（超并发上限 42904）→ 异常上抛 + 不建任务 + 不重复释放（acquire 内已回退计数）
    @Test
    void submit_mediaInflightOverLimit_propagates42904WithoutInsert() {
        when(mediaInflightGate.acquire(USER_ID, com.superprogrammer.media.service.internal.MediaInflightGateService.KIND_VIDEO))
                .thenThrow(new BusinessException(ErrorCode.MEDIA_CONCURRENT_LIMIT));

        BusinessException e = assertThrows(BusinessException.class, () ->
                service.submit("p", "16:9", 5, "720p", false, false,
                        null, null, null, SEEDANCE_2, USER_ID, false));

        assertEquals(42904, e.getCode());
        verify(taskMapper, never()).insert(any());
        verify(mediaInflightGate, never()).release(any(), any());
    }

    // C3：acquire 成功但落库前校验失败（模型不可用）→ catch 内配对 release，防失败提交自我锁死至 TTL
    @Test
    void submit_validationFailAfterAcquire_releasesMediaSlot() {
        when(mediaInflightGate.acquire(USER_ID, com.superprogrammer.media.service.internal.MediaInflightGateService.KIND_VIDEO))
                .thenReturn(true);
        when(mediaModelService.resolveProviderByModel("unknown-model")).thenReturn(null);

        assertThrows(BusinessException.class, () ->
                service.submit("p", "16:9", 5, "720p", false, false,
                        null, null, null, "unknown-model", USER_ID, false));

        verify(mediaInflightGate).release(USER_ID,
                com.superprogrammer.media.service.internal.MediaInflightGateService.KIND_VIDEO);
        verify(taskMapper, never()).insert(any());
    }

    // ---------- 计划5 Step5：组池提交预检 + 估价快照 ----------

    /** 估价桩（V152）：estimateVideoPoints 改走 estimateVideoYuan(providerId, model, seconds, resolution, hasRef)。 */
    private void stubEstimate(java.math.BigDecimal points) {
        lenient().when(pricingService.estimateVideoYuan(7L, SEEDANCE_2, 5, "720p", false))
                .thenReturn(new java.math.BigDecimal("0.5"));
        lenient().when(pointsRatioService.toPoints(new java.math.BigDecimal("0.5"))).thenReturn(points);
    }

    @Test
    void submit_withGroup_prechecksPoolAndStampsTask() {
        // 选组提交：组池预检替代个人预检（requireAffordableGroup）；task 落 projectGroupId + estimatedCost
        when(groupWalletService.requireAffordableGroup(5L, USER_ID, "VIDEO"))
                .thenReturn(new java.math.BigDecimal("1000"));
        stubEstimate(new java.math.BigDecimal("50"));

        service.submit("p", "16:9", 5, "720p", false, false,
                null, null, null, SEEDANCE_2, USER_ID, false, null, 5L);

        verify(walletService, never()).requireAffordable(any());
        ArgumentCaptor<MediaGenTask> captor = ArgumentCaptor.forClass(MediaGenTask.class);
        verify(taskMapper).insert(captor.capture());
        assertEquals(5L, captor.getValue().getProjectGroupId());
        assertEquals(0, captor.getValue().getEstimatedCost()
                .compareTo(new java.math.BigDecimal("50")));
    }

    @Test
    void submit_groupPoolInsufficient_rejected() {
        // 组池余量 < 预估消耗 → 40201 拒，task 不建；并发闸配对释放（防自我锁死）
        when(mediaInflightGate.acquire(USER_ID,
                com.superprogrammer.media.service.internal.MediaInflightGateService.KIND_VIDEO))
                .thenReturn(true);
        when(groupWalletService.requireAffordableGroup(5L, USER_ID, "VIDEO"))
                .thenReturn(new java.math.BigDecimal("10"));
        stubEstimate(new java.math.BigDecimal("50"));

        BusinessException e = assertThrows(BusinessException.class, () ->
                service.submit("p", "16:9", 5, "720p", false, false,
                        null, null, null, SEEDANCE_2, USER_ID, false, null, 5L));

        assertEquals(ErrorCode.INSUFFICIENT_POINTS.getCode(), e.getCode());
        verify(taskMapper, never()).insert(any());
        verify(mediaInflightGate).release(USER_ID,
                com.superprogrammer.media.service.internal.MediaInflightGateService.KIND_VIDEO);
    }

    @Test
    void submit_memberQuotaExceeded_rejected() {
        // used(100)+预估(50) > 组长限额(120) → 400 拒；组池余量本身够（隔离两道预检）
        when(groupWalletService.requireAffordableGroup(5L, USER_ID, "VIDEO"))
                .thenReturn(new java.math.BigDecimal("1000"));
        stubEstimate(new java.math.BigDecimal("50"));
        com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity member =
                new com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity();
        member.setUsedPoints(new java.math.BigDecimal("100"));
        member.setQuotaLimitPoints(new java.math.BigDecimal("120"));
        when(projectGroupService.findMember(5L, USER_ID)).thenReturn(member);

        BusinessException e = assertThrows(BusinessException.class, () ->
                service.submit("p", "16:9", 5, "720p", false, false,
                        null, null, null, SEEDANCE_2, USER_ID, false, null, 5L));

        assertTrue(e.getMessage().contains("成员积分限额"));
        verify(taskMapper, never()).insert(any());
    }

    @Test
    void submit_estimateMissingPrice_rejected() {
        // 2026-08-25 fail-closed：价表缺价 → 估价抛 PRICING_NOT_FOUND，提交直接拒（task 不建）。
        // 旧口径「记 0 容忍」会跳过预检/预扣放白嫖，已废。
        when(groupWalletService.requireAffordableGroup(5L, USER_ID, "VIDEO"))
                .thenReturn(new java.math.BigDecimal("10"));
        when(pricingService.estimateVideoYuan(any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new BusinessException(ErrorCode.PRICING_NOT_FOUND));

        BusinessException e = assertThrows(BusinessException.class, () ->
                service.submit("p", "16:9", 5, "720p", false, false,
                        null, null, null, SEEDANCE_2, USER_ID, false, null, 5L));

        assertEquals(ErrorCode.PRICING_NOT_FOUND.getCode(), e.getCode());
        verify(taskMapper, never()).insert(any());
    }

    @Test
    void submit_estimateZero_hardGateRejects() {
        // 2026-08-25 fail-closed 硬闸：估价为 0（显式 0 价等）→ PRICING_NOT_FOUND 拒，task 不建。
        when(groupWalletService.requireAffordableGroup(5L, USER_ID, "VIDEO"))
                .thenReturn(new java.math.BigDecimal("1000"));
        stubEstimate(java.math.BigDecimal.ZERO);
        when(walletService.isEnabled()).thenReturn(true);

        BusinessException e = assertThrows(BusinessException.class, () ->
                service.submit("p", "16:9", 5, "720p", false, false,
                        null, null, null, SEEDANCE_2, USER_ID, false, null, 5L));

        assertEquals(ErrorCode.PRICING_NOT_FOUND.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("预估价"));
        verify(taskMapper, never()).insert(any());
    }

    // ---------- 7x-2（V152）：个人钱包预估消耗预检 ----------

    @Test
    void submit_personalBalanceBelowEstimate_rejected() {
        // 7x-2：个人余额(10) < 预估消耗(50) → 40201 拒，task 不建（防 100 积分跑 2000 积分任务）
        when(walletService.requireAffordable(USER_ID)).thenReturn(new java.math.BigDecimal("10"));
        stubEstimate(new java.math.BigDecimal("50"));

        BusinessException e = assertThrows(BusinessException.class, () ->
                service.submit("p", "16:9", 5, "720p", false, false,
                        null, null, null, SEEDANCE_2, USER_ID, false));

        assertEquals(ErrorCode.INSUFFICIENT_POINTS.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("预估消耗"));
        verify(taskMapper, never()).insert(any());
    }

    @Test
    void submit_personalBalanceCoversEstimate_proceeds() {
        // 7x-2：个人余额(1000) ≥ 预估(50) → 放行，estimated_cost 落快照
        when(walletService.requireAffordable(USER_ID)).thenReturn(new java.math.BigDecimal("1000"));
        stubEstimate(new java.math.BigDecimal("50"));

        service.submit("p", "16:9", 5, "720p", false, false,
                null, null, null, SEEDANCE_2, USER_ID, false);

        ArgumentCaptor<MediaGenTask> captor = ArgumentCaptor.forClass(MediaGenTask.class);
        verify(taskMapper).insert(captor.capture());
        assertEquals(0, captor.getValue().getEstimatedCost()
                .compareTo(new java.math.BigDecimal("50")));
    }

    // ---------- C1（17x-2）：预估预览个人口径（组内限额卡归因） ----------

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> scopeOf(java.util.Map<String, Object> out) {
        return (java.util.Map<String, Object>) out.get("personalScope");
    }

    @Test
    void estimate_groupMemberQuotaBinds_memberConstraint() {
        // 限额成员：quota 60 - used 50 = 10 < est 50 → 卡在 MEMBER（池 1000 够），affordable=false
        stubEstimate(new java.math.BigDecimal("50"));
        when(groupWalletService.getGroupBalance(5L)).thenReturn(new java.math.BigDecimal("1000"));
        com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity member =
                new com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity();
        member.setRole(com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity.ROLE_MEMBER);
        member.setQuotaLimitPoints(new java.math.BigDecimal("60"));
        member.setUsedPoints(new java.math.BigDecimal("50"));
        when(memberMapper.selectByGroupUser(5L, USER_ID)).thenReturn(member);

        java.util.Map<String, Object> out = service.estimatePreview("VIDEO", SEEDANCE_2, 5, "720p",
                false, null, USER_ID, 5L);

        assertEquals(Boolean.FALSE, out.get("affordable"));
        java.util.Map<String, Object> scope = scopeOf(out);
        assertEquals("MEMBER", scope.get("bindingConstraint"));
        assertEquals(Boolean.FALSE, scope.get("affordableMember"));
        assertEquals(0, ((java.math.BigDecimal) scope.get("inProjectAvailable"))
                .compareTo(new java.math.BigDecimal("10")));
    }

    @Test
    void estimate_groupMemberUnlimited_poolConstraint() {
        // 不限额成员（quota=null）：只看组池；池 30 < est 50 → 卡在 POOL，personalScope 照返
        stubEstimate(new java.math.BigDecimal("50"));
        when(groupWalletService.getGroupBalance(5L)).thenReturn(new java.math.BigDecimal("30"));
        com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity member =
                new com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity();
        member.setRole(com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity.ROLE_MEMBER);
        member.setQuotaLimitPoints(null);
        member.setUsedPoints(new java.math.BigDecimal("10"));
        when(memberMapper.selectByGroupUser(5L, USER_ID)).thenReturn(member);

        java.util.Map<String, Object> out = service.estimatePreview("VIDEO", SEEDANCE_2, 5, "720p",
                false, null, USER_ID, 5L);

        assertEquals(Boolean.FALSE, out.get("affordable"));
        java.util.Map<String, Object> scope = scopeOf(out);
        assertEquals("POOL", scope.get("bindingConstraint"));
        assertEquals(Boolean.TRUE, scope.get("affordableMember"));
        assertNull(scope.get("inProjectAvailable"));
        assertNull(scope.get("quota"));
    }

    @Test
    void estimate_groupManagerTakesMinOfTwoCards() {
        // 限额管理双卡：quota−used=40，可分配额度=25 → 取更紧 25 < est 50 → MEMBER 卡
        stubEstimate(new java.math.BigDecimal("50"));
        when(groupWalletService.getGroupBalance(5L)).thenReturn(new java.math.BigDecimal("1000"));
        com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity mgr =
                new com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity();
        mgr.setRole(com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity.ROLE_MANAGER);
        mgr.setQuotaLimitPoints(new java.math.BigDecimal("100"));
        mgr.setUsedPoints(new java.math.BigDecimal("60"));
        when(memberMapper.selectByGroupUser(5L, USER_ID)).thenReturn(mgr);
        when(memberBudgetService.allocatable(5L, mgr, null)).thenReturn(new java.math.BigDecimal("25"));

        java.util.Map<String, Object> out = service.estimatePreview("VIDEO", SEEDANCE_2, 5, "720p",
                false, null, USER_ID, 5L);

        java.util.Map<String, Object> scope = scopeOf(out);
        assertEquals("MEMBER", scope.get("bindingConstraint"));
        assertEquals(0, ((java.math.BigDecimal) scope.get("inProjectAvailable"))
                .compareTo(new java.math.BigDecimal("25")));
        assertEquals(Boolean.FALSE, out.get("affordable"));
    }

    @Test
    void estimate_groupMemberDebtFrozen_debtConstraint_V161() {
        // V161 欠款冻结口径：debt_leader 2 + debt_pool 3 → 组内可用=0、卡点 DEBT（先于限额/池展示）；
        // 名下余额 selfPoints 不算限额内可用（第二腿资金源），单列透出
        stubEstimate(new java.math.BigDecimal("50"));
        when(groupWalletService.getGroupBalance(5L)).thenReturn(new java.math.BigDecimal("1000"));
        com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity member =
                new com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity();
        member.setRole(com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity.ROLE_MEMBER);
        member.setQuotaLimitPoints(new java.math.BigDecimal("60"));
        member.setUsedPoints(new java.math.BigDecimal("10"));
        member.setSelfPoints(new java.math.BigDecimal("8"));
        member.setDebtPoolPoints(new java.math.BigDecimal("3"));
        member.setDebtLeaderPoints(new java.math.BigDecimal("2"));
        when(memberMapper.selectByGroupUser(5L, USER_ID)).thenReturn(member);

        java.util.Map<String, Object> out = service.estimatePreview("VIDEO", SEEDANCE_2, 5, "720p",
                false, null, USER_ID, 5L);

        assertEquals(Boolean.FALSE, out.get("affordable"));
        java.util.Map<String, Object> scope = scopeOf(out);
        assertEquals("DEBT", scope.get("bindingConstraint"));
        assertEquals(Boolean.FALSE, scope.get("affordableMember"));
        assertEquals(0, ((java.math.BigDecimal) scope.get("inProjectAvailable"))
                .compareTo(java.math.BigDecimal.ZERO));
        assertEquals(0, ((java.math.BigDecimal) scope.get("debtTotalPoints"))
                .compareTo(new java.math.BigDecimal("5")));
        assertEquals(0, ((java.math.BigDecimal) scope.get("selfPoints"))
                .compareTo(new java.math.BigDecimal("8")));
    }

    @Test
    void estimate_groupNoMemberRow_noPersonalScope() {
        // 非成员（行缺失，理论走不到——上游已 403）：退化为只看池，兼容旧口径
        stubEstimate(new java.math.BigDecimal("50"));
        when(groupWalletService.getGroupBalance(5L)).thenReturn(new java.math.BigDecimal("1000"));
        when(memberMapper.selectByGroupUser(5L, USER_ID)).thenReturn(null);

        java.util.Map<String, Object> out = service.estimatePreview("VIDEO", SEEDANCE_2, 5, "720p",
                false, null, USER_ID, 5L);

        assertEquals(Boolean.TRUE, out.get("affordable"));
        assertNull(out.get("personalScope"));
    }

    // ---------- C3（6x/Q5）：图片比例模式（ratio→推导 WxH 覆盖 size） ----------

    private static final String SEEDREAM_LITE = "doubao-seedream-5.0-lite";

    private void stubImageEstimate(java.math.BigDecimal points) {
        lenient().when(walletService.requireAffordable(USER_ID))
                .thenReturn(new java.math.BigDecimal("1000"));
        lenient().when(mediaInflightGate.acquire(USER_ID,
                com.superprogrammer.media.service.internal.MediaInflightGateService.KIND_IMAGE))
                .thenReturn(true);
        lenient().when(mediaModelService.resolveImageProviderByModel(SEEDREAM_LITE)).thenReturn(provider);
        lenient().when(pricingService.computeCost(
                com.superprogrammer.billing.entity.LlmUsageLogEntity.KIND_IMAGE, 7L, SEEDREAM_LITE,
                null, null, null, 1, false))
                .thenReturn(new java.math.BigDecimal("0.5"));
        lenient().when(pointsRatioService.toPoints(new java.math.BigDecimal("0.5"))).thenReturn(points);
    }

    @Test
    void submitImage_ratioWithCustomWhSize_400Mutex() {
        // C3：ratio 与显式宽x高互斥（都传 400），推导前拦——不建任务
        stubImageEstimate(new java.math.BigDecimal("50"));
        BusinessException e = assertThrows(BusinessException.class, () ->
                service.submitImage("p", null, "2048x1152", null, null, null, null,
                        null, null, null, SEEDREAM_LITE, USER_ID, false, null, "16:9"));
        assertTrue(e.getMessage().contains("互斥"), e.getMessage());
        verify(taskMapper, never()).insert(any());
    }

    @Test
    void submitImage_ratioPlusTier_derivesWhAndStampsRatio() {
        // C3：16:9+2K → 推导 2731x1536 覆盖 size（等面积），原始 ratio 留痕 config
        stubImageEstimate(new java.math.BigDecimal("50"));
        lenient().when(mediaBillingService.holdMediaEstimated(
                org.mockito.ArgumentMatchers.eq(USER_ID), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(true);

        service.submitImage("p", null, "2K", null, null, null, null,
                null, null, null, SEEDREAM_LITE, USER_ID, false, null, "16:9");

        ArgumentCaptor<MediaGenTask> captor = ArgumentCaptor.forClass(MediaGenTask.class);
        verify(taskMapper).insert(captor.capture());
        String cfg = captor.getValue().getRequestConfig();
        assertTrue(cfg.contains("\"size\":\"2731x1536\""), cfg);
        assertTrue(cfg.contains("\"ratio\":\"16:9\""), cfg);
        assertEquals(0, captor.getValue().getEstimatedCost()
                .compareTo(new java.math.BigDecimal("50")));
    }

    @Test
    void submitImage_ratioOnly_defaultsTier2K() {
        // C3：ratio 带、size 空 → 默认 2K 档推导
        stubImageEstimate(new java.math.BigDecimal("50"));
        lenient().when(mediaBillingService.holdMediaEstimated(
                org.mockito.ArgumentMatchers.eq(USER_ID), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(true);

        service.submitImage("p", null, null, null, null, null, null,
                null, null, null, SEEDREAM_LITE, USER_ID, false, null, "16:9");

        ArgumentCaptor<MediaGenTask> captor = ArgumentCaptor.forClass(MediaGenTask.class);
        verify(taskMapper).insert(captor.capture());
        assertTrue(captor.getValue().getRequestConfig().contains("\"size\":\"2731x1536\""),
                captor.getValue().getRequestConfig());
    }

    @Test
    void submitImage_lowTierRatio_400WithGuidance() {
        // C3：1K 档像素预算低于下限 → 明确报错指引（pro 模型 1K 预设场景）
        stubImageEstimate(new java.math.BigDecimal("50"));
        BusinessException e = assertThrows(BusinessException.class, () ->
                service.submitImage("p", null, "1K", null, null, null, null,
                        null, null, null, SEEDREAM_LITE, USER_ID, false, null, "16:9"));
        assertTrue(e.getMessage().contains("不支持比例模式"), e.getMessage());
        verify(taskMapper, never()).insert(any());
    }

    // ---------- HHX-9/10：附属任务提交分流（context-ir / regeneration） ----------

    private static final String MM_CTX_IR = "minimax-h3-context-ir";
    private static final String MM_REGEN = "minimax-h3-regeneration";

    private void stubMmModel(String model) {
        lenient().when(mediaModelService.resolveProviderByModel(model)).thenReturn(provider);
    }

    private void stubChatEstimate(java.math.BigDecimal yuan) {
        // computeCost 8 参重载（kind, providerId, model, in, out, videoSeconds, imageCount, hasReference）
        lenient().when(pricingService.computeCost(
                org.mockito.ArgumentMatchers.eq("CHAT"), org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(MM_CTX_IR),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(yuan);
        lenient().when(pointsRatioService.toPoints(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new java.math.BigDecimal("1"));
    }

    @Test
    void submit_contextIr_taskTypeContextIr_chatEstimate() {
        stubMmModel(MM_CTX_IR);
        stubChatEstimate(new java.math.BigDecimal("0.092"));
        List<AttachmentRef> attachments = images(2);

        service.submit("一只猫在霓虹街头", null, 5, null, null, null, null, null,
                attachments, MM_CTX_IR, USER_ID, false, null, null, null);

        ArgumentCaptor<MediaGenTask> captor = ArgumentCaptor.forClass(MediaGenTask.class);
        verify(taskMapper).insert(captor.capture());
        MediaGenTask task = captor.getValue();
        assertEquals(MediaGenTask.TYPE_CONTEXT_IR, task.getTaskType());
        assertTrue(task.getRequestConfig().contains("一只猫在霓虹街头"), "config 落提示词");
        assertTrue(task.getRequestConfig().contains("\"attachments\""), "附件照常多模态输入");
        assertEquals(0, task.getEstimatedCost().compareTo(new java.math.BigDecimal("1")),
                "CHAT 估价经 toPoints 折积分");
        // 估算 in = ceil(8 字×0.75)=6 + 2 图×1500 = 3006，out=4000 固定
        org.mockito.ArgumentCaptor<Integer> in = org.mockito.ArgumentCaptor.forClass(Integer.class);
        verify(pricingService).computeCost(
                org.mockito.ArgumentMatchers.eq("CHAT"), org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(MM_CTX_IR), in.capture(),
                org.mockito.ArgumentMatchers.eq(4000),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyBoolean());
        assertEquals(3006, in.getValue());
    }

    @Test
    void submit_regeneration_sourceValidationChain() {
        stubMmModel(MM_REGEN);

        // 1) 缺 sourceTaskId → 400
        BusinessException e1 = assertThrows(BusinessException.class, () ->
                service.submit(null, null, null, null, null, null, null, null, null,
                        MM_REGEN, USER_ID, false, null, null, null));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e1.getCode());

        // 2) 源任务不存在 → 404
        when(taskMapper.selectById(100L)).thenReturn(null);
        BusinessException e2 = assertThrows(BusinessException.class, () ->
                service.submit(null, null, null, null, null, null, null, null, null,
                        MM_REGEN, USER_ID, false, null, null, 100L));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), e2.getCode());

        // 3) 非本人 → 403（admin 旁路不在此测）
        MediaGenTask foreign = regenSource(100L);
        foreign.setUserId(99L);
        when(taskMapper.selectById(100L)).thenReturn(foreign);
        BusinessException e3 = assertThrows(BusinessException.class, () ->
                service.submit(null, null, null, null, null, null, null, null, null,
                        MM_REGEN, USER_ID, false, null, null, 100L));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), e3.getCode());

        // 4) 源未成功 → 400
        MediaGenTask running = regenSource(100L);
        running.setStatus(MediaGenTask.STATUS_RUNNING);
        when(taskMapper.selectById(100L)).thenReturn(running);
        BusinessException e4 = assertThrows(BusinessException.class, () ->
                service.submit(null, null, null, null, null, null, null, null, null,
                        MM_REGEN, USER_ID, false, null, null, 100L));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e4.getCode());

        // 5) 源超 7 天窗口 → 400
        MediaGenTask stale = regenSource(100L);
        stale.setCreatedAt(java.time.OffsetDateTime.now().minusDays(8));
        when(taskMapper.selectById(100L)).thenReturn(stale);
        BusinessException e5 = assertThrows(BusinessException.class, () ->
                service.submit(null, null, null, null, null, null, null, null, null,
                        MM_REGEN, USER_ID, false, null, null, 100L));
        assertTrue(e5.getMessage().contains("7 天"), e5.getMessage());

        verify(taskMapper, never()).insert(any());
    }

    private MediaGenTask regenSource(Long id) {
        MediaGenTask t = new MediaGenTask();
        t.setId(id);
        t.setUserId(USER_ID);
        t.setProviderId(7L);
        t.setModel("minimax-h3");
        t.setTaskType(MediaGenTask.TYPE_TEXT2VIDEO);
        t.setStatus(MediaGenTask.STATUS_SUCCEEDED);
        t.setArkTaskId("424010985738629");
        t.setCreatedAt(java.time.OffsetDateTime.now());
        t.setRequestConfig("{\"prompt\":\"p\",\"duration\":8,\"resolution\":\"768p\"}");
        return t;
    }

    @Test
    void submit_regeneration_happyPath_minimalConfig_andSecondPricing() {
        stubMmModel(MM_REGEN);
        when(taskMapper.selectById(100L)).thenReturn(regenSource(100L));

        service.submit(null, null, null, null, null, null, null, null, null,
                MM_REGEN, USER_ID, false, null, null, 100L);

        ArgumentCaptor<MediaGenTask> captor = ArgumentCaptor.forClass(MediaGenTask.class);
        verify(taskMapper).insert(captor.capture());
        MediaGenTask task = captor.getValue();
        assertEquals(MediaGenTask.TYPE_REGENERATION, task.getTaskType());
        String cfg = task.getRequestConfig();
        assertTrue(cfg.contains("\"sourceArkTaskId\":\"424010985738629\""), cfg);
        assertTrue(cfg.contains("\"sourceTaskId\":100"), cfg);
        assertTrue(cfg.contains("\"resolution\":\"2k\""), cfg);
        assertFalse(cfg.contains("\"prompt\""), "再生成无提示词");
        // 估价：继承源时长 8s × regeneration 行（2k 秒价），无参考视频
        verify(pricingService).estimateVideoYuan(7L, MM_REGEN, 8, "2k", false);
    }

    @Test
    void submit_regeneration_foreignProviderSource_400() {
        stubMmModel(MM_REGEN);
        MediaGenTask other = regenSource(100L);
        other.setProviderId(999L);
        when(taskMapper.selectById(100L)).thenReturn(other);
        BusinessException e = assertThrows(BusinessException.class, () ->
                service.submit(null, null, null, null, null, null, null, null, null,
                        MM_REGEN, USER_ID, false, null, null, 100L));
        assertTrue(e.getMessage().contains("非本供应商"), e.getMessage());
        verify(taskMapper, never()).insert(any());
    }

    @Test
    void estimatePreview_contextIr_usesPromptCharsChatFormula() {
        stubMmModel(MM_CTX_IR);
        stubChatEstimate(new java.math.BigDecimal("0.023"));
        when(walletService.getBalance(USER_ID)).thenReturn(new java.math.BigDecimal("100"));

        java.util.Map<String, Object> out = service.estimatePreview("VIDEO", MM_CTX_IR, null, null,
                false, null, USER_ID, null, 100);

        assertEquals(0, ((java.math.BigDecimal) out.get("estimatedPoints"))
                .compareTo(new java.math.BigDecimal("1")));
        // in = ceil(100×0.75)=75，out=4000
        verify(pricingService).computeCost(
                org.mockito.ArgumentMatchers.eq("CHAT"), org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(MM_CTX_IR),
                org.mockito.ArgumentMatchers.eq(75), org.mockito.ArgumentMatchers.eq(4000),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }
}
