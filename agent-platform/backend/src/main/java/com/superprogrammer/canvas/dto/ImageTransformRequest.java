package com.superprogrammer.canvas.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 图片变换请求（2x 四轮 S6 翻转/旋转 + S7 彩色标注合成 / spec §6.1-6.2）。
 *
 * <p>op ∈ {@code FLIP_H | FLIP_V | ROTATE_90 | ROTATE_180 | ROTATE_270 | ANNOTATE}
 * （白名单枚举，service 层 {@code VideoFrameService.TransformOp.parse} 校验，非法值 BAD_REQUEST）。
 * op=ANNOTATE 时必带 {@code boxes}（≤8 框，service 层逐框校验归一化坐标+颜色白名单）。
 */
@Data
public class ImageTransformRequest {

    /** 变换类型（枚举字符串，白名单校验）。 */
    @NotBlank(message = "变换类型缺失")
    private String op;

    /** 标注框列表（仅 op=ANNOTATE 使用；其余 op 忽略该字段）。 */
    private List<AnnotateBoxDTO> boxes;
}
