package com.superprogrammer.media.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.typehandler.JsonbStringTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 媒体生成任务（media_gen_tasks，V54）。
 *
 * <p>append-only 任务日志 + 状态机，刻意不继承 {@code BaseEntity}（不带 deleted/version：
 * 任务表不软删，靠归档清理，同 stored_files 思路）。created_at/updated_at 由 DB 默认值维护 +
 * txService 显式 setUpdatedAt 保证 updated_at 跟随状态变更。
 *
 * <p>状态机：PENDING → RUNNING → SUCCEEDED / FAILED / DOWNLOAD_FAILED。
 */
@Data
@TableName(value = "media_gen_tasks", autoResultMap = true)
public class MediaGenTask {

    public static final String TYPE_TEXT2VIDEO = "TEXT2VIDEO";
    public static final String TYPE_IMAGE2VIDEO = "IMAGE2VIDEO";
    /** 图片生成任务类型（V84 起，Seedream 生图）：纯文生图 / 参考图生图（图生图/多图融合）。 */
    public static final String TYPE_TEXT2IMAGE = "TEXT2IMAGE";
    public static final String TYPE_IMAGE2IMAGE = "IMAGE2IMAGE";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_DOWNLOAD_FAILED = "DOWNLOAD_FAILED";

    /** usage 口径标记（与 status 正交）：SUCCESS 真值 / ESTIMATED 像素公式估算 / FAILED 无 usage。 */
    public static final String FLAG_SUCCESS = "SUCCESS";
    public static final String FLAG_ESTIMATED = "ESTIMATED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    /** nullable：系统调用无 user。 */
    private Long userId;
    private Long providerId;
    private String model;
    private String taskType;
    private String status;
    private String arkTaskId;
    /** JSONB：{prompt, ratio, duration, resolution, watermark, generateAudio, refFileId?}。用 JsonbStringTypeHandler 做 String↔jsonb 转换（同 ChatMessage.metadata）。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String requestConfig;
    /** → stored_files.file_id。 */
    private String resultFileId;
    /**
     * JSONB：图片任务结果元数据 {imageFileIds[],generatedImages,outputTokens}（V84）。
     * 图片一次返 N 张图，与视频单 url 不同，故多图 fileId 存此列；视频任务恒 NULL。
     * 用 JsonbStringTypeHandler 做 String↔jsonb 转换（同 requestConfig）。
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String resultMeta;
    private Integer tokensCost;
    private BigDecimal cost;
    private String statusFlag;
    private String errorMsg;
    private Integer attempt;
    private OffsetDateTime lockedUntil;
    /** 提交者 IP（submit 时从 MDC 盖戳，worker 终态审计取用，问题修复 #6）。 */
    private String clientIp;
}
