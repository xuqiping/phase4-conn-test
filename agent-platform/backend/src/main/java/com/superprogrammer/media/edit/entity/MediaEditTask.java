package com.superprogrammer.media.edit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.typehandler.JsonbStringTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 视频剪辑渲染任务（media_edit_tasks，V55）。
 *
 * <p>append-only 任务日志 + 状态机，刻意不继承 {@code BaseEntity}（不带 deleted/version：
 * 任务表不软删，靠归档清理，同 {@code media_gen_tasks} / {@code stored_files} 思路）。
 * created_at/updated_at 由 DB 默认值维护 + txService 显式 setUpdatedAt 保证 updated_at 跟随状态变更。
 *
 * <p>状态机：PENDING → RUNNING → SUCCEEDED / FAILED / DOWNLOAD_FAILED。
 * 与 {@code MediaGenTask} 区别：本地 FFmpeg 渲染无 provider/arkTaskId/model 列，edit_spec 即完整渲染意图。
 */
@Data
@TableName(value = "media_edit_tasks", autoResultMap = true)
public class MediaEditTask {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_DOWNLOAD_FAILED = "DOWNLOAD_FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    /** nullable：系统调用无 user。 */
    private Long userId;
    private String status;

    /** JSONB：剪辑意图 EditSpec（clips/texts/audio/output），用 JsonbStringTypeHandler 做 String↔jsonb 转换。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String editSpec;

    /** → stored_files.file_id（source=EDIT）。 */
    private String resultFileId;
    private String errorMsg;
    private Integer attempt;
    private OffsetDateTime lockedUntil;
    /** 提交者 IP（submit 时从 MDC 盖戳，worker 终态审计取用，问题修复 #6）。 */
    private String clientIp;
}
