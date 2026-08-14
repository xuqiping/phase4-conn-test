package com.superprogrammer.canvas.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

/**
 * 焦点编辑图片裁剪请求（C10 增强：原仅记录 cropRect+描述不产真图，现按归一化框选区真裁剪）。
 *
 * <p>归一化坐标（0-1）= 前端 FocusEditOverlay 框选 px / stage 尺寸，与源图自然分辨率解耦：
 * 后端按源图实际像素换算裁剪（见 VideoFrameService.cropImage）。x+w / y+h 须 ≤1（service 校验）。
 */
@Data
public class ImageCropRequest {

    /** 裁剪区左上角 x 归一化（0-1）。 */
    @DecimalMin("0") @DecimalMax("1")
    private Double x;
    /** 裁剪区左上角 y 归一化（0-1）。 */
    @DecimalMin("0") @DecimalMax("1")
    private Double y;
    /** 裁剪区宽归一化（0-1）。 */
    @DecimalMin("0") @DecimalMax("1")
    private Double w;
    /** 裁剪区高归一化（0-1）。 */
    @DecimalMin("0") @DecimalMax("1")
    private Double h;
}
