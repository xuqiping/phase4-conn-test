package com.superprogrammer.media.config;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 单个生图模型的能力清单（参数白名单 + 特性开关）——前端动态表单的数据驱动来源。
 *
 * <p>与 {@link MediaModelCapability}（视频：ratio/resolution/duration/audio）刻意分离：
 * 生图参数集完全不同（size 枚举 / 组图 sequential / 联网 web_search / 引导尺度 guidance_scale /
 * 输出格式 output_format / 提示词优化模式），混进一个 DTO 会语义打架。
 *
 * <p>「选不同模型→页面按该模型实际参数决定展示内容，枚举值用下拉框」硬约束由此清单驱动：
 * 前端读本对象，按各 supportsXxx 显隐控件、按各 List 枚举填下拉。
 *
 * <p>默认值由 {@link MediaModelCapabilityService#resolveImage} 按模型名前缀给出
 * （seedream+lite / seedream+pro），可在 provider config JSON 里按 modelId 精确覆盖。
 * 来源：lite 官方文档 82379/1541523（权威）；pro 用户提供官方参数表（权威）。
 */
@Data
@Builder
public class ImageModelCapability {

    /** 参考图上限（lite=14，pro=10；0=不支持参考图）。 */
    private int refImageMax;

    /** 参考图允许的格式（lite 含 webp/bmp/tiff/gif/heic/heif；pro 仅 jpeg/png）。 */
    private List<String> refImageFormats;

    /** size 预设枚举（下拉候选）：lite=[2K,3K,4K]，pro=[2K,3K]。 */
    private List<String> sizePresets;

    /** 是否支持自定义「宽x高」size 模式（lite+pro 均支持）。 */
    private boolean supportsWhSize;

    /** 是否支持组图 sequential_image_generation（lite 独有）。 */
    private boolean supportsSequential;

    /** 组图最大生成数（仅 supportsSequential=true 有意义，lite=15）。 */
    private int maxSequentialImages;

    /** 是否支持联网搜索 tools.web_search（lite 独有）。 */
    private boolean supportsWebSearch;

    /** 是否支持流式 stream（lite 独有；MVP 固定 stream=false，此标志仅驱动 UI 显隐）。 */
    private boolean supportsStream;

    /** 输出格式枚举（下拉候选）：lite+pro=[jpeg,png]。 */
    private List<String> outputFormats;

    /** 提示词优化模式枚举（下拉候选）：lite=[standard]，pro=[standard,fast]。 */
    private List<String> optimizeModes;

    /** 是否支持 guidance_scale 引导尺度（pro 独有）。 */
    private boolean supportsGuidanceScale;

    /** guidance_scale 下限（pro=1）。 */
    private double guidanceMin;

    /** guidance_scale 上限（pro=10）。 */
    private double guidanceMax;

    /** 水印默认值（lite+pro 均 true）。 */
    private boolean watermarkDefault;
}
