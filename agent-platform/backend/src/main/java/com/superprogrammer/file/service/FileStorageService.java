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

    public FileStorageService(@Value("${app.files.storage-dir:uploads/workflow-inputs}") String storageDir,
                              StoredFileMapper storedFileMapper) {
        this.storageRoot = Paths.get(storageDir).toAbsolutePath().normalize();
        this.storedFileMapper = storedFileMapper;
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
     * 强校验归属后返回 Resource。owner 不匹配且非 admin → FORBIDDEN；登记行缺失 → NOT_FOUND。
     */
    public Resource load(String fileId, Long userId, boolean admin) {
        StoredFileEntity meta = storedFileMapper.selectById(fileId);
        if (meta == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在: " + fileId);
        }
        if (!admin && !meta.getOwnerUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该文件");
        }
        Path path = resolveSafe(fileId);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在: " + fileId);
        }
        return new FileSystemResource(path);
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
