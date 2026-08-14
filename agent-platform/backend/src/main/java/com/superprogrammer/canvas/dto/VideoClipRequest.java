package com.superprogrammer.canvas.dto;

import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

/**
 * 视频截取请求（plan C12 / IC-13）。
 *
 * <ul>
 *   <li>{@link #startSec} — 起始秒（≥0）。</li>
 *   <li>{@link #endSec} — 结束秒（&gt;startSec，且 ≤ 视频时长，由 service 校验）。</li>
 * </ul>
 */
@Data
public class VideoClipRequest {

    /** 起始秒。 */
    @PositiveOrZero
    private Long startSec;
    /** 结束秒。 */
    @PositiveOrZero
    private Long endSec;
}
