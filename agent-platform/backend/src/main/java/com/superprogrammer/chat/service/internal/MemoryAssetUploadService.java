package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.MemoryAssetUploadVO;
import com.superprogrammer.chat.entity.MemoryAssetMemory;
import com.superprogrammer.chat.mapper.MemoryAssetMemoryMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.file.service.StoredFile;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 聊天附件上传 + 文件记忆登记（V69 记忆二期 P3 Step 1，FR-201）。
 * <p>
 * 咽喉点：mime/大小在落盘前拒；页数/时长在落盘后解析预检（超限删文件+友好话术拒收）。
 * 校验通过 → stored_files(source=CHAT) + memory_asset_memories(PROCESSING) 一行
 * （一文件一记忆；ingestion 由 worker 异步接手，Step 2）。
 * <p>
 * 解析预检读不懂（损坏文件）<b>不拒收</b>——留给 ingestion 标 FAILED 可重试。
 */
@Slf4j
@Service
public class MemoryAssetUploadService {

    /** 文件数量上限（单条消息携带附件数）。 */
    public static final int MAX_ATTACHMENTS_PER_MESSAGE = 5;

    private final long maxSizeBytes;
    private final int maxPages;
    private final long maxDurationUs;
    private final FileStorageService fileStorageService;
    private final MemoryAssetMemoryMapper assetMemoryMapper;

    public MemoryAssetUploadService(
            @Value("${memory.asset.max-size-mb:50}") long maxSizeMb,
            @Value("${memory.asset.max-pages:200}") int maxPages,
            @Value("${memory.asset.max-duration-minutes:30}") long maxDurationMinutes,
            FileStorageService fileStorageService,
            MemoryAssetMemoryMapper assetMemoryMapper) {
        this.maxSizeBytes = maxSizeMb * 1024 * 1024;
        this.maxPages = maxPages;
        this.maxDurationUs = maxDurationMinutes * 60 * 1_000_000L;
        this.fileStorageService = fileStorageService;
        this.assetMemoryMapper = assetMemoryMapper;
    }

    /**
     * 上传聊天附件：校验 → 落盘登记 → 解析预检 → 建文件记忆行（PROCESSING）。
     * 超限/不支持类型 → BAD_REQUEST 友好话术（已落盘的删干净）。
     */
    public MemoryAssetUploadVO upload(MultipartFile file, Long userId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "附件不能为空");
        }
        if (file.getSize() > maxSizeBytes) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "文件超过大小上限（≤" + (maxSizeBytes / 1024 / 1024) + "MB），请压缩后重试");
        }
        String fileKind = resolveFileKind(file.getContentType(), file.getOriginalFilename());
        if (fileKind == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "不支持的文件类型，请上传图片/文档（PDF/Word/PPT/txt）/音视频");
        }

        StoredFile stored = fileStorageService.store(file, userId, StoredFileEntity.SOURCE_CHAT);
        try {
            String violation = precheck(stored.fileId(), userId, fileKind);
            if (violation != null) {
                fileStorageService.delete(stored.fileId());
                throw new BusinessException(ErrorCode.BAD_REQUEST, violation);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 预检读不了（损坏/未知格式）不拒收：留 ingestion 标 FAILED
            log.warn("附件预检解析失败 fileId={} kind={}: {}", stored.fileId(), fileKind, e.getMessage());
        }

        MemoryAssetMemory row = new MemoryAssetMemory();
        row.setOwnerUserId(userId);
        row.setFileId(stored.fileId());
        row.setFileKind(fileKind);
        row.setOriginalName(stored.name());
        row.setTagIds(new ArrayList<>());
        row.setIngestStatus(MemoryAssetMemory.STATUS_PROCESSING);
        row.setRetryCount(0);
        row.setWeakMemory(false);
        assetMemoryMapper.insert(row);

        MemoryAssetUploadVO vo = new MemoryAssetUploadVO();
        vo.setMemoryId(row.getId());
        vo.setFileId(stored.fileId());
        vo.setOriginalName(stored.name());
        vo.setFileKind(fileKind);
        vo.setSize(stored.size());
        vo.setIngestStatus(row.getIngestStatus());
        return vo;
    }

    /**
     * 消息附件归属校验（P3 Step 1：消息体携带 file_ids）。
     * 每个 fileId 须存在 + owner=本人 + source=CHAT + status=ACTIVE，任一不符 → BAD_REQUEST。
     *
     * @return 附件原始名列表（与入参同序），供消息 metadata 落库 + turn 提及「含附件《名》」
     */
    public List<String> resolveOwnedAttachmentNames(List<String> fileIds, Long userId) {
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        if (fileIds.size() > MAX_ATTACHMENTS_PER_MESSAGE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "单条消息最多携带 " + MAX_ATTACHMENTS_PER_MESSAGE + " 个附件");
        }
        List<String> names = new ArrayList<>();
        for (String fileId : fileIds) {
            StoredFileEntity meta = fileStorageService.findMeta(fileId);
            if (meta == null || !userId.equals(meta.getOwnerUserId())
                    || !StoredFileEntity.SOURCE_CHAT.equals(meta.getSource())
                    || !StoredFileEntity.STATUS_ACTIVE.equals(meta.getStatus())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "附件不存在或已失效，请重新上传");
            }
            names.add(meta.getOriginalName());
        }
        return names;
    }

    /** 页数/时长预检：超限返友好话术（调用方删文件拒收）；未超限/无法判定返 null。解析异常上抛由调用方放行。 */
    private String precheck(String fileId, Long userId, String fileKind) throws Exception {
        Path path = fileStorageService.loadPath(fileId, userId, false);
        return switch (fileKind) {
            case MemoryAssetMemory.KIND_PDF -> {
                try (PDDocument doc = PDDocument.load(path.toFile())) {
                    yield doc.getNumberOfPages() > maxPages
                            ? "PDF 超过页数上限（≤" + maxPages + " 页），请拆分后重试" : null;
                }
            }
            case MemoryAssetMemory.KIND_PPT -> {
                try (InputStream in = java.nio.file.Files.newInputStream(path);
                     XMLSlideShow ppt = new XMLSlideShow(in)) {
                    yield ppt.getSlides().size() > maxPages
                            ? "PPT 超过页数上限（≤" + maxPages + " 页），请拆分后重试" : null;
                }
            }
            case MemoryAssetMemory.KIND_AUDIO, MemoryAssetMemory.KIND_VIDEO -> {
                try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(path.toFile())) {
                    grabber.start();
                    long us = grabber.getLengthInTime();
                    grabber.stop();
                    yield us > maxDurationUs
                            ? "音视频超过时长上限（≤" + (maxDurationUs / 60 / 1_000_000) + " 分钟），请裁剪后重试" : null;
                }
            }
            default -> null;   // IMAGE/DOC/OTHER：页数不可靠或无意义，仅大小门控
        };
    }

    /** mime + 扩展名 → file_kind；不在白名单返 null。 */
    private String resolveFileKind(String mime, String filename) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        String ext = extension(filename);
        if (m.startsWith("image/")) return MemoryAssetMemory.KIND_IMAGE;
        if (m.equals("application/pdf") || ext.equals(".pdf")) return MemoryAssetMemory.KIND_PDF;
        if (m.contains("presentation") || m.contains("powerpoint") || ext.equals(".ppt") || ext.equals(".pptx")) {
            return MemoryAssetMemory.KIND_PPT;
        }
        if (m.contains("word") || m.equals("text/plain") || m.equals("text/markdown")
                || ext.equals(".doc") || ext.equals(".docx") || ext.equals(".txt") || ext.equals(".md")) {
            return MemoryAssetMemory.KIND_DOC;
        }
        if (m.startsWith("audio/") || ext.equals(".mp3") || ext.equals(".wav") || ext.equals(".m4a")
                || ext.equals(".flac") || ext.equals(".ogg")) {
            return MemoryAssetMemory.KIND_AUDIO;
        }
        if (m.startsWith("video/") || ext.equals(".mp4") || ext.equals(".mov") || ext.equals(".webm")
                || ext.equals(".mkv") || ext.equals(".avi")) {
            return MemoryAssetMemory.KIND_VIDEO;
        }
        // mime 缺失时图片扩展名兜底（image/* 未命中说明 mime 空/octet-stream）
        if (ext.equals(".jpg") || ext.equals(".jpeg") || ext.equals(".png") || ext.equals(".gif")
                || ext.equals(".webp") || ext.equals(".bmp")) {
            return MemoryAssetMemory.KIND_IMAGE;
        }
        return null;
    }

    private String extension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase(Locale.ROOT);
    }
}
