package com.superprogrammer.canvas.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 图片翻转/旋转请求（2x 四轮 S6 / spec §6.1）。
 *
 * <p>op ∈ {@code FLIP_H | FLIP_V | ROTATE_90 | ROTATE_180 | ROTATE_270}（白名单枚举，
 * service 层 {@code VideoFrameService.TransformOp.parse} 校验，非法值 BAD_REQUEST）。
 */
@Data
public class ImageTransformRequest {

    /** 变换类型（枚举字符串，白名单校验）。 */
    @NotBlank(message = "变换类型缺失")
    private String op;
}
