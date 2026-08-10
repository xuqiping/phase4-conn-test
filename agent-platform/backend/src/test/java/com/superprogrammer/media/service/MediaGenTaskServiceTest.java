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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
    private BizMetrics bizMetrics;

    private MediaGenTaskService service;
    private LlmProviderEntity provider;

    @BeforeEach
    void setUp() {
        provider = new LlmProviderEntity();
        provider.setId(7L);
        provider.setName("seedance");
        provider.setModels("[\"" + SEEDANCE_2 + "\"]");

        service = new MediaGenTaskService(
                taskMapper, mediaModelService,
                new MediaModelCapabilityService(new ObjectMapper()),
                fileStorageService, new MediaGenProperties(), new ObjectMapper(), assetService, walletService,
                inflightGate, bizMetrics);

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
    void submit_firstFramePlusReferenceAccepted() {
        // SeedDance 2.0 契约：first_frame + 参考图合法（last_frame 才与参考图互斥）。
        List<AttachmentRef> attachments = new ArrayList<>();
        attachments.add(att("first.png", "image", "first_frame"));
        attachments.add(att("ref.png", "image", null));
        attachments.forEach(a -> stubOwnedFile(a.getFileId(), "image/png"));

        service.submit("首帧+参考图", "16:9", 5, "720p", false, false,
                null, null, attachments, SEEDANCE_2, USER_ID, false);

        ArgumentCaptor<MediaGenTask> captor = ArgumentCaptor.forClass(MediaGenTask.class);
        verify(taskMapper).insert(captor.capture());
        String cfg = captor.getValue().getRequestConfig();
        assertTrue(cfg.contains("first_frame"), "首帧 frameRole 须落库");
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
        assertTrue(e.getMessage().contains("互斥"), "须提示 last_frame 与参考图互斥，实际: " + e.getMessage());
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
}
