package com.superprogrammer.media.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 视频生成提交请求。
 *
 * <p>参数对齐 SeedDance 2.0 官方契约：
 * <ul>
 *   <li>{@code ratio} 画面比例，默认 16:9（controller 兜底）。</li>
 *   <li>{@code duration} 时长 4–15 秒（官方区间），默认 5。</li>
 *   <li>{@code resolution} 分辨率 480p/720p/1080p/4K，默认 720p。</li>
 *   <li>{@code watermark} 水印开关，默认 false。</li>
 *   <li>{@code generateAudio} 生成原生音频开关，默认 false。</li>
 * </ul>
 *
 * <p>图生视频（IMAGE2VIDEO）的参考图先经 {@code POST /api/files} 上传拿 fileId，
 * 再填 {@code refFileId} 提交——复用单一上传咽喉点（归属校验集中），不在本端点重做 multipart。
 */
@Data
public class MediaSubmitRequest {

    @NotBlank(message = "提示词不能为空")
    private String prompt;

    /** 画面比例（官方 ratio），默认 16:9。 */
    private String ratio;

    /** 时长秒，4–15（官方区间），默认 5。 */
    @Min(value = 4, message = "时长至少 4 秒")
    @Max(value = 15, message = "时长至多 15 秒")
    private Integer duration;

    /** 分辨率：480p / 720p / 1080p / 4K，默认 720p。 */
    private String resolution;

    /** 是否加水印，默认 false。 */
    private Boolean watermark;

    /** 是否同步生成原生音频（2.0 特色），默认 false。 */
    private Boolean generateAudio;

    /** TEXT2VIDEO / IMAGE2VIDEO，默认 TEXT2VIDEO。 */
    private String taskType;

    /** 图生视频参考图 stored_files.file_id（IMAGE2VIDEO 必填）。 */
    private String refFileId;

    /** Ark 模型 id（可选，默认取视频 provider 首个模型）。 */
    private String model;
}
