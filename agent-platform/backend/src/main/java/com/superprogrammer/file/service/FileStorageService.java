package com.superprogrammer.file.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.mapper.StoredFileMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * 文件存储 + 归属咽喉点（V40 stored_files）。
 *
 * <p>单一归属校验点 {@link #load(String, Long, boolean)}：owner 不匹配且非 admin → FORBIDDEN。
 * 根治 {@code GET /api/files/{id}} authenticated IDOR（Excel多Sheet导入设计 §10.3）。
 * 所有调用方必须传 userId：FileController.get / DocumentParserService.extract / Excel preview-upload 内部。
 */
@Service
public class FileStorageService {

    /** 单租户占位（与既有表 DEFAULT 1 一致；多租户落地前固定）。 */
    private static final long DEFAULT_TENANT_ID = 1L;

    private final Path storageRoot;
    private final StoredFileMapper storedFileMapper;
    /** 共享放行钩子（P3 记忆二期：项目 FILE 条目成员放行）；无实现 bean 时 Spring 注空列表 = 纯 owner 校验。 */
    private final java.util.List<FileSharedAccessGrantor> sharedAccessGrantors;

    /**
     * 上传内容嗅探（安全体系 S4 · SEC-FR-031）。横切可选依赖：手写构造（单测/纯 owner 校验）
     * 不注入 → null 直通，只保留 S1 扩展名白名单，不破坏既有切片。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FileUploadValidator uploadValidator;

    /**
     * per-user 存储配额（安全体系 S4 · SEC-FR-033）。横切可选依赖同上；null → 默认 2048MB。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.superprogrammer.system.service.SystemSettingService systemSettingService;

    /** 配额拒收指标（S4 · SEC-FR-033）。横切可选依赖：缺席不计数。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.superprogrammer.common.metrics.BizMetrics bizMetrics;

    @org.springframework.beans.factory.annotation.Autowired
    public FileStorageService(@Value("${app.files.storage-dir:uploads/workflow-inputs}") String storageDir,
                              StoredFileMapper storedFileMapper,
                              java.util.List<FileSharedAccessGrantor> sharedAccessGrantors) {
        this.storageRoot = Paths.get(storageDir).toAbsolutePath().normalize();
        this.storedFileMapper = storedFileMapper;
        this.sharedAccessGrantors = sharedAccessGrantors == null ? java.util.List.of() : sharedAccessGrantors;
    }

    /** 测试/纯 owner 校验用（无共享放行钩子）。 */
    public FileStorageService(String storageDir, StoredFileMapper storedFileMapper) {
        this(storageDir, storedFileMapper, java.util.List.of());
    }

    /** 落盘 + 登记 owner（kb_id 留空，通用上传）。 */
    public StoredFile store(MultipartFile file, Long ownerUserId, String source) {
        return store(file, ownerUserId, source, null, null);
    }

    /** 落盘 + 登记 owner + kb_id（KB 场景，便于按 KB 清理）。expiresAt 仅 PREVIEW 用。 */
    public StoredFile store(MultipartFile file, Long ownerUserId, String source, Long kbId, OffsetDateTime expiresAt) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File must not be empty");
        }
        // 安全体系 S1 · SEC-FR-030c：上传白名单咽喉点——所有用户上传入口（/api/files、画布、
        // 资产库、聊天记忆、KB 文档）都经本方法，扩展名正列举校验一处生效；
        // html/svg/js/exe 等拒收，与下载端 inline 白名单（Step1）双保险根治存储型 XSS。
        // storeStream 不校验：服务端可信来源（Ark 媒体产物回拉）。
        validateUploadExtension(file.getOriginalFilename());
        // 安全体系 S4 · SEC-FR-031：第二关 magic number 嗅探（exe 改名 .png 在此拒收）。
        // validator 缺席（手写构造的单测）或嗅探层异常均放行，不破坏可用性。
        if (uploadValidator != null) {
            uploadValidator.sniff(file);
        }
        // 安全体系 S4 · SEC-FR-033：第三关 per-user 存储配额（storeStream 不查——服务端可信回源）。
        // SUM 查询异常放行（检测层不自残）；0=关闭。
        enforceStorageQuota(ownerUserId, file.getSize());
        String originalName = StringUtils.cleanPath(
                file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                        ? "file"
                        : file.getOriginalFilename());
        String fileId = UUID.randomUUID() + extensionOf(originalName);
        Path target = resolveSafe(fileId);

        try {
            Files.createDirectories(storageRoot);
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store uploaded file", e);
        }

        String mimeType = file.getContentType();
        if (mimeType == null || mimeType.isBlank()) {
            mimeType = "application/octet-stream";
        }

        StoredFileEntity row = new StoredFileEntity();
        row.setFileId(fileId);
        row.setTenantId(DEFAULT_TENANT_ID);
        row.setOwnerUserId(ownerUserId);
        row.setKbId(kbId);
        row.setSource(source);
        row.setStatus(StoredFileEntity.STATUS_ACTIVE);
        row.setOriginalName(originalName);
        row.setMime(mimeType);
        row.setSize(file.getSize());
        row.setExpiresAt(expiresAt);
        storedFileMapper.insert(row);

        return new StoredFile(
                fileId,
                "/api/files/" + fileId,
                originalName,
                mimeType,
                file.getSize());
    }

    /**
     * 从 InputStream 落盘 + 登记 owner（媒体生成产物下载用，非 MultipartFile 来源）。
     *
     * <p>SeedDance 任务 SUCCEEDED 后，Ark 返回的 video_url 是 OSS 临时链接（有时效），
     * 须即时流式下载到本地 + 登记 {@code stored_files}(source=MEDIA)，后续只依赖本地 fileId。
     * 复用同一存储咽喉点（resolveSafe 防路径穿越 + 登记 owner），与 MultipartFile 上传一致安全口径。
     *
     * @return fileId（UUID+ext），调用方写入 media_gen_tasks.result_file_id
     */
    public String storeStream(InputStream input, String originalName, String mime,
                              Long size, Long ownerUserId, String source) {
        if (input == null) {
            throw new IllegalArgumentException("InputStream must not be null");
        }
        String cleanName = StringUtils.cleanPath(
                originalName == null || originalName.isBlank() ? "media.bin" : originalName);
        String fileId = UUID.randomUUID() + extensionOf(cleanName);
        Path target = resolveSafe(fileId);

        long copied;
        try {
            Files.createDirectories(storageRoot);
            try (InputStream in = input) {
                copied = Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store streamed file", e);
        }

        String mimeType = (mime == null || mime.isBlank()) ? "application/octet-stream" : mime;
        Long rowSize = size != null ? size : copied;

        StoredFileEntity row = new StoredFileEntity();
        row.setFileId(fileId);
        row.setTenantId(DEFAULT_TENANT_ID);
        row.setOwnerUserId(ownerUserId);
        row.setSource(source);
        row.setStatus(StoredFileEntity.STATUS_ACTIVE);
        row.setOriginalName(cleanName);
        row.setMime(mimeType);
        row.setSize(rowSize);
        storedFileMapper.insert(row);

        return fileId;
    }

    /**
     * 强校验归属后返回 Resource。owner 不匹配且非 admin → FORBIDDEN；登记行缺失 → NOT_FOUND。
     */
    public Resource load(String fileId, Long userId, boolean admin) {
        return new FileSystemResource(loadPath(fileId, userId, admin));
    }

    /**
     * 强校验归属后返回本地 Path（C11 抽帧用：javacv FFmpegFrameGrabber 需可 seek 的文件路径，非 InputStream）。
     * 与 {@link #load} 同一归属咽喉点（meta 校验 + 存在性校验），仅返回类型不同。
     */
    public Path loadPath(String fileId, Long userId, boolean admin) {
        StoredFileEntity meta = storedFileMapper.selectById(fileId);
        if (meta == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在: " + fileId);
        }
        if (!admin && !meta.getOwnerUserId().equals(userId) && !grantedByShare(fileId, userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该文件");
        }
        Path path = resolveSafe(fileId);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在: " + fileId);
        }
        return path;
    }

    /** 共享放行分支（FR-204）：咨询已注册 grantor，任一放行即允许；grantor 异常 fail-closed。 */
    private boolean grantedByShare(String fileId, Long userId) {
        if (userId == null) {
            return false;
        }
        for (FileSharedAccessGrantor grantor : sharedAccessGrantors) {
            try {
                if (grantor.canAccess(fileId, userId)) {
                    return true;
                }
            } catch (Exception e) {
                // fail-closed：放行链故障绝不打开门
                org.slf4j.LoggerFactory.getLogger(FileStorageService.class)
                        .warn("共享放行 grantor 异常 fileId={} userId={}: {}", fileId, userId, e.getMessage());
            }
        }
        return false;
    }

    /** 删磁盘字节 + 删登记行（D5 文件生命周期：文档 INDEXED/删除后清 orphan）。 */
    public void delete(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(resolveSafe(fileId));
        } catch (IOException e) {
            // 字节删失败不阻断业务（登记行仍删，避免残留引用）；记日志即可
            // 无 log 字段时静默——后续可注入 Logger
        }
        storedFileMapper.deleteById(fileId);
    }

    /**
     * D5 文件生命周期：文档 INDEXED 后清原件字节。
     *
     * <p>与 {@link #delete} 的区别：删字节但<strong>保留登记行</strong>并置 status=CLEANED，
     * 留作生命周期/审计记录（区别于文档删除的硬删行）。删字节后 load 会因文件不存在抛 NOT_FOUND，
     * 这是预期的——检索/重嵌读 knowledge_nodes，不依赖原件（设计 §10.5）。
     *
     * <p>幂等：字节已删时 Files.deleteIfExists 无副作用，status 重复置 CLEANED 无害。
     * 字节删失败不抛（不阻断 INDEXED），仅 status 不前移——下次清理/删除兜底。
     */
    public void cleanAfterIndex(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(resolveSafe(fileId));
        } catch (IOException e) {
            // 字节删失败不阻断：status 不前移，文档删除时 delete() 兜底清字节 + 删行
            return;
        }
        LambdaUpdateWrapper<StoredFileEntity> u = new LambdaUpdateWrapper<>();
        u.eq(StoredFileEntity::getFileId, fileId)
                .set(StoredFileEntity::getStatus, StoredFileEntity.STATUS_CLEANED);
        storedFileMapper.update(null, u);
    }

    public String detectMimeType(String fileId) {
        try {
            String mimeType = Files.probeContentType(resolveSafe(fileId));
            return mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType;
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }

    /** 读 stored_files 登记行（mime/originalName/size 等），不做归属校验。内部回显/元数据注入用。null=行不存在。 */
    public StoredFileEntity findMeta(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            return null;
        }
        return storedFileMapper.selectById(fileId);
    }

    /**
     * 上传扩展名白名单校验（安全体系 S1 · SEC-FR-030c，FileUploadValidator 雏形）。
     * 正列举放行生产资料（文档/图片/音视频/压缩包），危险类型与无扩展名一律 400 固定话术。
     * S4 F-2 将叠加 magic number 字节嗅探，本方法保留为入口第一关。
     */
    public void validateUploadExtension(String originalFilename) {
        if (!FileSecurityPolicy.isUploadAllowed(originalFilename)) {
            throw new BusinessException(ErrorCode.FILE_TYPE_NOT_ALLOWED);
        }
    }

    /**
     * per-user 存储配额预检（安全体系 S4 · SEC-FR-033，F-4）。
     *
     * <p>已用量 = stored_files 按 owner 汇总 ACTIVE 行 size（单条聚合查询，owner 维度行数有限无 N+1 面）；
     * 已用 + 本文件 > 配额 → {@code STORAGE_QUOTA_EXCEEDED} 固定话术（含用量/上限）+ 拒收计数。
     * 查询/设置异常一律放行 + WARN（检测层不自残）；配额 ≤0 = 关闭（不查库零开销）。
     */
    private void enforceStorageQuota(Long ownerUserId, long incomingSize) {
        try {
            long quotaMb = systemSettingService == null
                    ? 2048L
                    : systemSettingService.getUserStorageQuotaMb();
            if (quotaMb <= 0) {
                return;
            }
            long quotaBytes = quotaMb * 1024 * 1024;
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<StoredFileEntity> w =
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            w.select("COALESCE(SUM(size),0) AS used")
                    .eq("owner_user_id", ownerUserId)
                    .eq("status", StoredFileEntity.STATUS_ACTIVE);
            long used = 0L;
            for (java.util.Map<String, Object> row : storedFileMapper.selectMaps(w)) {
                Object v = row == null ? null : row.get("used");
                if (v instanceof Number n) {
                    used = n.longValue();
                }
                break;   // 聚合单行
            }
            if (used + incomingSize > quotaBytes) {
                if (bizMetrics != null) {
                    bizMetrics.uploadQuotaDenied();
                }
                throw new BusinessException(ErrorCode.STORAGE_QUOTA_EXCEEDED,
                        "存储空间已满（已用 " + toGb(used) + "GB / 上限 " + toGb(quotaBytes)
                                + "GB），请删除旧文件后重试");
            }
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(FileStorageService.class)
                    .warn("存储配额检查失败(放行) owner={}: {}", ownerUserId, e.getMessage());
        }
    }

    private static String toGb(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    private Path resolveSafe(String fileId) {
        if (fileId == null || fileId.isBlank() || fileId.contains("/") || fileId.contains("\\")) {
            throw new IllegalArgumentException("Invalid file id");
        }
        Path target = storageRoot.resolve(fileId).normalize();
        if (!target.startsWith(storageRoot)) {
            throw new IllegalArgumentException("Invalid file id");
        }
        return target;
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        String extension = filename.substring(dot).toLowerCase(Locale.ROOT);
        return extension.matches("\\.[a-z0-9]{1,12}") ? extension : "";
    }
}
