package com.superprogrammer.media.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 模型能力解析：内置前缀默认 + provider config JSON 精确覆盖。
 *
 * <p>合并顺序：保守兜底 → 前缀默认（seedance-2* / seedance-1*）→ provider config
 * {@code capabilities: {"<modelId>": {...}}} 精确覆盖。未来接新模型只需在 config 里加一条
 * 覆盖（或在此加前缀默认），无需建表/发版。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaModelCapabilityService {

    private static final List<String> ALL_RATIOS =
            List.of("21:9", "16:9", "4:3", "1:1", "3:4", "9:16", "adaptive");
    private static final List<String> RES_UPTO_4K = List.of("480p", "720p", "1080p", "4K");
    private static final List<String> RES_UPTO_1080 = List.of("480p", "720p", "1080p");

    private final ObjectMapper objectMapper;

    /**
     * 解析某模型的能力画像。
     *
     * @param modelId        模型 id（如 doubao-seedance-2-0-260128）
     * @param providerConfig 所属 provider 的 config JSON（可空）
     */
    public MediaModelCapability resolve(String modelId, String providerConfig) {
        MediaModelCapability cap = defaultsFor(modelId);
        if (providerConfig == null || providerConfig.isBlank()) {
            return cap;
        }
        try {
            JsonNode root = objectMapper.readTree(providerConfig);
            JsonNode override = root.path("capabilities").path(modelId);
            if (override.isMissingNode() || !override.isObject()) {
                return cap;
            }
            return applyOverride(cap, override);
        } catch (Exception e) {
            log.warn("解析 provider config capabilities 失败（model={}），使用默认能力: {}", modelId, e.getMessage());
            return cap;
        }
    }

    /**
     * 解析某<b>生图</b>模型的能力清单（lite/pro 参数集）。
     *
     * <p>合并顺序同 {@link #resolve}：保守兜底 → 前缀默认（seedream+lite / seedream+pro）
     * → provider config {@code capabilities: {"<modelId>": {...}}} 精确覆盖。
     *
     * @param modelId        模型 id（Doubao-Seedream-5.0-lite / doubao-seedream-5.0-pro-0724 等）
     * @param providerConfig 所属 IMAGE provider 的 config JSON（可空）
     */
    public ImageModelCapability resolveImage(String modelId, String providerConfig) {
        ImageModelCapability cap = defaultsForImage(modelId);
        if (providerConfig == null || providerConfig.isBlank()) {
            return cap;
        }
        try {
            JsonNode root = objectMapper.readTree(providerConfig);
            JsonNode override = root.path("capabilities").path(modelId);
            if (override.isMissingNode() || !override.isObject()) {
                return cap;
            }
            return applyImageOverride(cap, override);
        } catch (Exception e) {
            log.warn("解析 provider config capabilities 失败（image model={}），使用默认能力: {}", modelId, e.getMessage());
            return cap;
        }
    }

    /**
     * 生图前缀默认：seedream+lite（参数丰富）/ seedream+pro（精准编辑，不同参数集）；
     * 未知走保守兜底 + WARN。
     *
     * <p>来源：lite 官方文档 82379/1541523（权威）；pro 用户提供官方参数表（权威）。
     */
    private ImageModelCapability defaultsForImage(String modelId) {
        String id = modelId == null ? "" : modelId.toLowerCase(Locale.ROOT);
        List<String> jpegPng = List.of("jpeg", "png");
        if (id.contains("seedream") && id.contains("lite")) {
            // Seedream 5.0 lite：≤14 参考图、组图 sequential(max15)、联网、流式、4K、optimize 仅 standard
            return ImageModelCapability.builder()
                    .refImageMax(14)
                    .refImageFormats(List.of("jpeg", "png", "webp", "bmp", "tiff", "gif", "heic", "heif"))
                    .sizePresets(List.of("2K", "3K", "4K"))
                    .supportsWhSize(true)
                    .supportsSequential(true)
                    .maxSequentialImages(15)
                    .supportsWebSearch(true)
                    .supportsStream(true)
                    .outputFormats(jpegPng)
                    .optimizeModes(List.of("standard"))
                    .supportsGuidanceScale(false)
                    .watermarkDefault(true)
                    .build();
        }
        if (id.contains("seedream") && id.contains("pro")) {
            // Seedream 5.0 pro：≤10 参考图、无组图/联网/流式、1K/1.5K/2K、optimize standard+fast。
            // 注1：ctaigw 的 pro-0724 实测不支持 guidance_scale（官网参数表标注支持，但网关 400 拒绝——
            //     「guidance_scale is not supported by the current model」），故关闭该控件，避免误发参数。
            // 注2：size 实测仅支持 1K/1.5K/2K（人工 E2E 反馈；原按官网参数表写的 2K/3K 中 3K 网关不支持）。
            return ImageModelCapability.builder()
                    .refImageMax(10)
                    .refImageFormats(jpegPng)
                    .sizePresets(List.of("1K", "1.5K", "2K"))
                    .supportsWhSize(true)
                    .supportsSequential(false)
                    .maxSequentialImages(0)
                    .supportsWebSearch(false)
                    .supportsStream(false)
                    .outputFormats(jpegPng)
                    .optimizeModes(List.of("standard", "fast"))
                    .supportsGuidanceScale(false)
                    .watermarkDefault(true)
                    .build();
        }
        log.warn("未知生图模型 {}，使用保守能力兜底（1 参考图/2K/无组图联网/guidance）", modelId);
        return ImageModelCapability.builder()
                .refImageMax(1)
                .refImageFormats(jpegPng)
                .sizePresets(List.of("2K"))
                .supportsWhSize(true)
                .supportsSequential(false)
                .maxSequentialImages(0)
                .supportsWebSearch(false)
                .supportsStream(false)
                .outputFormats(jpegPng)
                .optimizeModes(List.of("standard"))
                .supportsGuidanceScale(false)
                .watermarkDefault(true)
                .build();
    }

    /** config JSON 覆盖（生图字段）：只覆盖出现的字段，未出现的保留默认。 */
    private ImageModelCapability applyImageOverride(ImageModelCapability base, JsonNode o) {
        return ImageModelCapability.builder()
                .refImageMax(intOr(o, "refImageMax", base.getRefImageMax()))
                .refImageFormats(listOr(o, "refImageFormats", base.getRefImageFormats()))
                .sizePresets(listOr(o, "sizePresets", base.getSizePresets()))
                .supportsWhSize(boolOr(o, "supportsWhSize", base.isSupportsWhSize()))
                .supportsSequential(boolOr(o, "supportsSequential", base.isSupportsSequential()))
                .maxSequentialImages(intOr(o, "maxSequentialImages", base.getMaxSequentialImages()))
                .supportsWebSearch(boolOr(o, "supportsWebSearch", base.isSupportsWebSearch()))
                .supportsStream(boolOr(o, "supportsStream", base.isSupportsStream()))
                .outputFormats(listOr(o, "outputFormats", base.getOutputFormats()))
                .optimizeModes(listOr(o, "optimizeModes", base.getOptimizeModes()))
                .supportsGuidanceScale(boolOr(o, "supportsGuidanceScale", base.isSupportsGuidanceScale()))
                .guidanceMin(doubleOr(o, "guidanceMin", base.getGuidanceMin()))
                .guidanceMax(doubleOr(o, "guidanceMax", base.getGuidanceMax()))
                .watermarkDefault(boolOr(o, "watermarkDefault", base.isWatermarkDefault()))
                .build();
    }

    private double doubleOr(JsonNode o, String field, double dft) {
        JsonNode n = o.get(field);
        return n != null && n.isNumber() ? n.asDouble() : dft;
    }

    /** 前缀默认值：2.0 系多模态全开；1.0 系仅首帧图；未知模型走保守兜底 + WARN。 */
    private MediaModelCapability defaultsFor(String modelId) {
        String id = modelId == null ? "" : modelId.toLowerCase(Locale.ROOT);
        if (id.contains("seedance-2")) {
            // SeedDance 2.0 全系（standard/fast/mini）：9图/3视频/3音频/总12
            return MediaModelCapability.builder()
                    .maxImages(9).maxVideos(3).maxAudios(3).maxAttachments(12)
                    .supportedRatios(ALL_RATIOS)
                    .supportedResolutions(id.contains("fast") || id.contains("mini") ? RES_UPTO_1080 : RES_UPTO_4K)
                    .minDuration(4).maxDuration(15)
                    .supportsGenerateAudio(true)
                    .videoDataUri(true)
                    .build();
        }
        if (id.contains("seedance-1")) {
            // 1.0 系：仅首帧/参考图（lite-i2v 放宽到 4 张），无视频/音频参考
            boolean liteI2v = id.contains("lite-i2v");
            return MediaModelCapability.builder()
                    .maxImages(liteI2v ? 4 : 1).maxVideos(0).maxAudios(0)
                    .maxAttachments(liteI2v ? 4 : 1)
                    .supportedRatios(ALL_RATIOS)
                    .supportedResolutions(RES_UPTO_1080)
                    .minDuration(4).maxDuration(12)
                    .supportsGenerateAudio(false)
                    .videoDataUri(false)
                    .build();
        }
        log.warn("未知视频模型 {}，使用保守能力兜底（1图/无音视频参考）", modelId);
        return MediaModelCapability.builder()
                .maxImages(1).maxVideos(0).maxAudios(0).maxAttachments(1)
                .supportedRatios(ALL_RATIOS)
                .supportedResolutions(RES_UPTO_1080)
                .minDuration(4).maxDuration(15)
                .supportsGenerateAudio(false)
                .videoDataUri(false)
                .build();
    }

    /** config JSON 覆盖：只覆盖出现的字段，未出现的保留默认。 */
    private MediaModelCapability applyOverride(MediaModelCapability base, JsonNode o) {
        MediaModelCapability.MediaModelCapabilityBuilder b = MediaModelCapability.builder()
                .maxImages(intOr(o, "maxImages", base.getMaxImages()))
                .maxVideos(intOr(o, "maxVideos", base.getMaxVideos()))
                .maxAudios(intOr(o, "maxAudios", base.getMaxAudios()))
                .maxAttachments(intOr(o, "maxAttachments", base.getMaxAttachments()))
                .supportedRatios(listOr(o, "supportedRatios", base.getSupportedRatios()))
                .supportedResolutions(listOr(o, "supportedResolutions", base.getSupportedResolutions()))
                .minDuration(intOr(o, "minDuration", base.getMinDuration()))
                .maxDuration(intOr(o, "maxDuration", base.getMaxDuration()))
                .supportsGenerateAudio(boolOr(o, "supportsGenerateAudio", base.isSupportsGenerateAudio()))
                .videoDataUri(boolOr(o, "videoDataUri", base.isVideoDataUri()));
        return b.build();
    }

    private int intOr(JsonNode o, String field, int dft) {
        JsonNode n = o.get(field);
        return n != null && n.isInt() ? n.asInt() : dft;
    }

    private boolean boolOr(JsonNode o, String field, boolean dft) {
        JsonNode n = o.get(field);
        return n != null && n.isBoolean() ? n.asBoolean() : dft;
    }

    @SuppressWarnings("unchecked")
    private List<String> listOr(JsonNode o, String field, List<String> dft) {
        JsonNode n = o.get(field);
        if (n == null || !n.isArray()) {
            return dft;
        }
        try {
            return objectMapper.readValue(n.toString(), List.class);
        } catch (Exception e) {
            return dft;
        }
    }
}
