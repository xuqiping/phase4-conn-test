package com.superprogrammer.media.edit.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 可剪辑素材视图（素材库面板用）。
 *
 * <p>当前仅列 SeedDance 已生成视频（{@code media_gen_tasks} SUCCEEDED）；用户上传视频走
 * {@code POST /api/files/upload} 拿 fileId 后直接进时间线（归属由上传咽喉点保证）。
 */
@Data
@Builder
public class MediaAssetVO {

    /** → stored_files.file_id（进时间线的标识）。 */
    private String fileId;
    /** 展示名。 */
    private String name;
    /** 时长（秒）。 */
    private Double durationSeconds;
    /** 来源：GEN（生成）。 */
    private String sourceType;
    private OffsetDateTime createdAt;
}
