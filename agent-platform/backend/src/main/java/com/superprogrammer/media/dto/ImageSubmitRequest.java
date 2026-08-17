package com.superprogrammer.media.dto;

import lombok.Data;

import java.util.List;

/**
 * 生图任务提交请求（POST /api/media/image）。
 *
 * <p>字段全部可空（除 model 外，由提交侧按 {@link com.superprogrammer.media.config.ImageModelCapability}
 * 逐项校验）；不支持的参数传了值即拒。前端按所选模型的 capability 显隐控件，只发该模型支持的参数。
 *
 * <ul>
 *   <li>{@code refFileIds} 参考图 file_id 列表（资产库选取；纯文生图传空/null）。</li>
 *   <li>{@code size} "2K"/"3K"/"4K" 或自定义"宽x高"。</li>
 *   <li>{@code guidanceScale} 引导尺度（仅 pro）。</li>
 *   <li>{@code sequential}/{@code maxImages} 组图（仅 lite）。</li>
 *   <li>{@code webSearch} 联网搜索（仅 lite）。</li>
 *   <li>{@code optimizeMode} 提示词优化 standard/fast。</li>
 * </ul>
 */
@Data
public class ImageSubmitRequest {

    /** 模型 id（lite/pro，必填）。 */
    private String model;
    /** 提示词。 */
    private String prompt;
    /** 参考图 file_id 列表（资产库选取）。 */
    private List<String> refFileIds;
    /** size 预设或宽x高。 */
    private String size;
    /** 输出格式 jpeg/png。 */
    private String outputFormat;
    /** 水印开关。 */
    private Boolean watermark;
    /** 引导尺度（pro）。 */
    private Double guidanceScale;
    /** 提示词优化模式 standard/fast。 */
    private String optimizeMode;
    /** 组图 auto/disabled（lite）。 */
    private String sequential;
    /** 组图最大生成数（lite）。 */
    private Integer maxImages;
    /** 联网搜索（lite）。 */
    private Boolean webSearch;

    /** 计划5 Step5：组池计费归属（null=个人钱包）；须为本人可见的项目组成员。 */
    private Long projectGroupId;
}
