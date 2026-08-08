package com.superprogrammer.chat.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import com.superprogrammer.common.typehandler.LongArrayTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 文件记忆（V69 记忆二期 P3）。表走 BaseEntity 软删。
 * <p>
 * 一文件一条目，<b>个人域资产</b>（owner=上传者）：用户问「那个课件讲了什么」召回到总结+原文件回链。
 * 原文件读取走 stored_files ACL 咽喉点（FileStorageService.load），本表不复制文件字节。
 * <p>
 * 状态机：PROCESSING（上传即返，异步 ingestion）→ READY / FAILED（可重试，retry_count 由 worker 硬卡上限）；
 * 模态降级 weak_memory=TRUE（OCR/转写失败 → 仅元数据+「读不懂内容」明示话术，FR-205）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "memory_asset_memories", autoResultMap = true)
public class MemoryAssetMemory extends BaseEntity {

    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";

    public static final String KIND_IMAGE = "IMAGE";
    public static final String KIND_DOC = "DOC";
    public static final String KIND_PPT = "PPT";
    public static final String KIND_PDF = "PDF";
    public static final String KIND_AUDIO = "AUDIO";
    public static final String KIND_VIDEO = "VIDEO";
    public static final String KIND_OTHER = "OTHER";

    /** 文件类型中文标签（卡片/总结话术共用，ingest 与 recall 同源）。 */
    public static String kindLabel(String fileKind) {
        return switch (fileKind == null ? "" : fileKind) {
            case KIND_IMAGE -> "图片";
            case KIND_PDF -> "PDF 文档";
            case KIND_PPT -> "PPT 演示文稿";
            case KIND_DOC -> "文档";
            case KIND_AUDIO -> "音频";
            case KIND_VIDEO -> "视频";
            default -> "文件";
        };
    }

    private Long ownerUserId;        // 个人域边界（随用户 CASCADE）
    private String fileId;           // stored_files 登记行（UUID+ext 自然主键）
    private String fileKind;         // IMAGE/DOC/PPT/PDF/AUDIO/VIDEO/OTHER，决定 ingestion 分派
    private String originalName;     // 冗余文件名（stored_files CLEANED 后仍可展示「原文件已删除」）
    private String l1Summary;        // 一句话总结（READY 后填）
    private String l2Detail;         // 结构化详述
    /** 标签 id 集（个人标签库，与对话记忆同一归一体系）。BIGINT[] 走 LongArrayTypeHandler。 */
    @TableField(typeHandler = LongArrayTypeHandler.class)
    private List<Long> tagIds;
    private String ingestStatus;     // PROCESSING / READY / FAILED
    private String ingestError;      // FAILED 原因（固定话术）
    private Integer retryCount;      // 重试次数
    private java.time.OffsetDateTime lockedUntil;  // worker 认领锁（条件 UPDATE 防并发，崩溃自愈）
    private Boolean weakMemory;      // 降级弱记忆标（FR-205）
}
