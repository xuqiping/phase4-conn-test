package com.superprogrammer.media.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 生图请求（provider 层消费，worker 从 requestConfig 解析后构建）。
 *
 * <p>对齐火山方舟 /v1/images/generations（OpenAI 兼容）契约。仅含「该模型支持」的字段
 * （提交侧已按 {@link com.superprogrammer.media.config.ImageModelCapability} 校验，
 * 不支持的参数不会出现在请求里）。
 *
 * <ul>
 *   <li>{@code refImageUrls} 参考图 data URI 列表（图生图/多图融合；纯文生图为空）。</li>
 *   <li>{@code size} {@code "2K"}/"3K"/"4K" 或自定义 "宽x高"。</li>
 *   <li>{@code sequential}+{@code maxImages} 组图（仅 lite）。</li>
 *   <li>{@code webSearch} 联网搜索（仅 lite）。</li>
 *   <li>{@code guidanceScale} 引导尺度（仅 pro）。</li>
 *   <li>{@code optimizeMode} 提示词优化模式 standard/fast。</li>
 * </ul>
 */
@Data
@Builder
public class MediaImageRequest {

    /** 模型 id（lite/pro）。 */
    private String model;
    /** 提示词（必填）。 */
    private String prompt;
    /** 参考图 data URI 列表（已解析，空=纯文生图）。 */
    private List<String> refImageUrls;
    /** size（预设或宽x高），null 走官方默认。 */
    private String size;
    /** 返回格式 url/b64_json，默认 url。 */
    private String responseFormat;
    /** 输出格式 jpeg/png，默认 jpeg。 */
    private String outputFormat;
    /** 水印开关。 */
    private Boolean watermark;
    /** 引导尺度（pro，[1,10]）。 */
    private Double guidanceScale;
    /** 提示词优化模式 standard/fast。 */
    private String optimizeMode;
    /** 组图 auto/disabled（lite）。 */
    private String sequential;
    /** 组图最大生成数（lite，sequential=auto 时）。 */
    private Integer maxImages;
    /** 联网搜索开关（lite）。 */
    private Boolean webSearch;
    /** 所属 llm_providers.id（IMAGE provider 路由）。 */
    private Long providerId;
}
