package com.superprogrammer.media.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 视频生成提交请求。
 *
 * <p>图生视频（IMAGE2VIDEO）的参考图先经 {@code POST /api/files} 上传拿 fileId，
 * 再填 {@code refFileId} 提交——复用单一上传咽喉点（归属校验集中），不在本端点重做 multipart。
 */
@Data
public class MediaSubmitRequest {

    @NotBlank(message = "提示词不能为空")
    private String prompt;

    /** 时长秒，默认 5。 */
    @Min(value = 1, message = "时长至少 1 秒")
    @Max(value = 10, message = "时长至多 10 秒")
    private Integer duration;

    /** 分辨率：480p / 720p / 1080p，默认 720p。 */
    private String resolution;

    /** TEXT2VIDEO / IMAGE2VIDEO，默认 TEXT2VIDEO。 */
    private String taskType;

    /** 图生视频参考图 stored_files.file_id（IMAGE2VIDEO 必填）。 */
    private String refFileId;

    /** Ark 模型 id（可选，默认取 doubao 首个模型）。 */
    private String model;
}
