package com.superprogrammer.canvas.dto;

import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

/**
 * 视频抽帧请求（plan C11 / IC-12）。
 *
 * <ul>
 *   <li>{@link #mode} — {@code FIRST}（首帧）/ {@code LAST}（尾帧）/ {@code AT}（指定秒），见 VideoFrameService.FrameMode。</li>
 *   <li>{@link #second} — 仅 {@code AT} 用，单位秒；越界 [0, 视频时长] 由 service 校验。</li>
 * </ul>
 */
@Data
public class FrameExtractRequest {

    /** FIRST / LAST / AT。 */
    private String mode;
    /** AT 模式抽帧秒数。 */
    @PositiveOrZero
    private Long second;
}
