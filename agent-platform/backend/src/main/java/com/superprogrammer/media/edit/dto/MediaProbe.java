package com.superprogrammer.media.edit.dto;

/**
 * ffprobe 解析结果（素材校验 / 渲染前探测用）。
 *
 * <p>{@code hasVideo/hasAudio} 探测文件实际流类型——用户上传「改后缀的假视频」会被识破
 * （无 video 流即拒）；渲染期据此决定 concat 是否带音轨（部分素材无音频时降级，见 FfmpegEditProvider）。
 *
 * @param hasVideo        是否含视频流
 * @param hasAudio        是否含音频流
 * @param width           视频宽（无视频流=null）
 * @param height          视频高（无视频流=null）
 * @param durationSeconds 时长（秒，取自 format.duration）
 */
public record MediaProbe(boolean hasVideo, boolean hasAudio, Integer width, Integer height, Double durationSeconds) {
}
