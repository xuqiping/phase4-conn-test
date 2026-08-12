package com.superprogrammer.media.dto;

import com.superprogrammer.media.config.ImageModelCapability;
import lombok.Builder;
import lombok.Data;

/**
 * 生图模型目录视图（GET /api/media/image-models）。
 *
 * <p>前端 ImageGenView 据此渲染模型下拉 + 按能力清单动态渲染参数表单：
 * size/outputFormat/optimizeMode 下拉（枚举）、sequential/webSearch/stream/guidanceScale
 * 开关/滑块（supportsXxx 显隐）、参考图上传区（refImageMax 计数上限）。
 * 满足「选不同模型→页面按实际参数决定展示内容 + 固定枚举值用下拉框」硬约束。
 */
@Data
@Builder
public class ImageModelVO {

    /** 模型 id（提交时回传 model 字段）。 */
    private String modelId;
    /** 展示名（provider displayName + modelId）。 */
    private String displayName;
    /** 所属 provider name（分组显示用）。 */
    private String providerName;

    /** 该模型的能力清单（驱动动态表单）。 */
    private ImageModelCapability capability;
}
