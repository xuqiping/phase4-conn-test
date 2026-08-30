package com.superprogrammer.media.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.llm.service.LlmProviderService;
import com.superprogrammer.system.service.SystemSettingService;
import com.superprogrammer.media.config.MediaGenProperties;
import com.superprogrammer.media.config.MediaModelCapability;
import com.superprogrammer.media.config.MediaModelCapabilityService;
import com.superprogrammer.media.config.ImageModelCapability;
import com.superprogrammer.media.dto.ImageModelVO;
import com.superprogrammer.media.dto.MediaModelVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 视频模型目录：从 llm_providers 表 category=VIDEO 的 ACTIVE provider 聚合可选模型，
 * 并合并每个模型的能力画像（附件上限/比例/分辨率/时长），供前端动态渲染表单。
 *
 * <p>多 provider 支持：所有 ACTIVE VIDEO provider 的模型都会出现在目录里——
 * 后续接入其他视频模型只需在「全局模型供应商」加一条 VIDEO provider 并配 models。
 * IMAGE（生图预留）不进本目录。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaModelService {

    private final LlmProviderService llmProviderService;
    private final MediaModelCapabilityService capabilityService;
    private final MediaGenProperties properties;
    private final ObjectMapper objectMapper;
    private final SystemSettingService systemSettingService;

    /**
     * 列出全部可选视频模型（跨所有 ACTIVE VIDEO provider，按 sortOrder 顺序）。
     */
    public List<MediaModelVO> listModels() {
        // 全局默认视频模型标记（配置失效/未配 → null，无命中项，前端回落列表第一个）
        String defaultVideoModel = systemSettingService.getDefaultVideoModel();
        List<MediaModelVO> result = new ArrayList<>();
        for (LlmProviderEntity provider : listMediaProviders()) {
            for (String model : parseModels(provider.getModels())) {
                MediaModelCapability cap = capabilityService.resolve(model, provider.getConfig());
                result.add(MediaModelVO.builder()
                        .modelId(model)
                        .displayName(buildDisplayName(provider, model))
                        .providerName(provider.getName())
                        .defaultModel(model.equals(defaultVideoModel))
                        .maxImages(cap.getMaxImages())
                        .maxVideos(cap.getMaxVideos())
                        .maxAudios(cap.getMaxAudios())
                        .maxAttachments(cap.getMaxAttachments())
                        .supportedRatios(cap.getSupportedRatios())
                        .supportedResolutions(cap.getSupportedResolutions())
                        .minDuration(cap.getMinDuration())
                        .maxDuration(cap.getMaxDuration())
                        .supportsGenerateAudio(cap.isSupportsGenerateAudio())
                        .videoDataUri(false)
                        .referenceVideoEnabled(cap.getMaxVideos() > 0 && properties.isReferenceVideoConfigured())
                        .build());
            }
        }
        return result;
    }

    /**
     * 按模型 id 反查所属 VIDEO provider（sortOrder 最小者优先，命中多个记 INFO）。
     * 找不到返回 null。
     */
    public LlmProviderEntity resolveProviderByModel(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        LlmProviderEntity hit = null;
        for (LlmProviderEntity provider : listMediaProviders()) {
            if (parseModels(provider.getModels()).contains(model)) {
                if (hit == null) {
                    hit = provider;
                } else {
                    log.info("模型 {} 同时存在于 provider {} 与 {}，取 sortOrder 最小者 {}",
                            model, hit.getName(), provider.getName(), hit.getName());
                }
            }
        }
        return hit;
    }

    /**
     * 默认 provider + 默认模型（未指定 model 时的回退路径，保持旧行为：
     * 取 media.provider-name 指定的 provider 及其 models[0]）。
     */
    public LlmProviderEntity defaultProvider() {
        return llmProviderService.getByName(properties.getProviderName());
    }

    public String firstModelOf(LlmProviderEntity provider) {
        List<String> models = parseModels(provider.getModels());
        return models.isEmpty() ? null : models.get(0);
    }

    private List<LlmProviderEntity> listMediaProviders() {
        List<LlmProviderEntity> result = new ArrayList<>();
        for (LlmProviderEntity p : llmProviderService.listActive()) {
            if (LlmProviderService.CATEGORY_VIDEO.equalsIgnoreCase(p.getCategory())) {
                result.add(p);
            }
        }
        return result;
    }

    // ---------- 图片模型目录（Seedream lite/pro，与视频目录平行，零回归） ----------

    /**
     * 列出全部可选生图模型（跨所有 ACTIVE IMAGE provider），含能力清单（驱动前端动态表单）。
     * 新增生图模型只需在「全局模型供应商」加一条 IMAGE provider 并配 models。
     */
    public List<ImageModelVO> listImageModels() {
        // 修复III C2（2x-2）：管理员默认生图模型标记（配置失效/未配 → null，无命中项，前端回落第一个）
        String defaultImageModel = systemSettingService.getDefaultImageModel();
        List<ImageModelVO> result = new ArrayList<>();
        for (LlmProviderEntity provider : listImageProviders()) {
            for (String model : parseModels(provider.getModels())) {
                ImageModelCapability cap = capabilityService.resolveImage(model, provider.getConfig());
                result.add(ImageModelVO.builder()
                        .modelId(model)
                        .displayName(buildDisplayName(provider, model))
                        .providerName(provider.getName())
                        .defaultModel(model.equals(defaultImageModel))
                        .capability(cap)
                        .build());
            }
        }
        return result;
    }

    /**
     * 按模型 id 反查所属 IMAGE provider（生图提交路由用）。sortOrder 最小者优先，找不到返 null。
     */
    public LlmProviderEntity resolveImageProviderByModel(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        LlmProviderEntity hit = null;
        for (LlmProviderEntity provider : listImageProviders()) {
            if (parseModels(provider.getModels()).contains(model)) {
                if (hit == null) {
                    hit = provider;
                } else {
                    log.info("图片模型 {} 同时存在于 provider {} 与 {}，取首个 {}",
                            model, hit.getName(), provider.getName(), hit.getName());
                }
            }
        }
        return hit;
    }

    private List<LlmProviderEntity> listImageProviders() {
        List<LlmProviderEntity> result = new ArrayList<>();
        for (LlmProviderEntity p : llmProviderService.listActive()) {
            if (LlmProviderService.CATEGORY_IMAGE.equalsIgnoreCase(p.getCategory())) {
                result.add(p);
            }
        }
        return result;
    }

    private List<String> parseModels(String modelsJson) {
        if (modelsJson == null || modelsJson.isBlank()) {
            return List.of();
        }
        try {
            List<?> raw = objectMapper.readValue(modelsJson, List.class);
            List<String> models = new ArrayList<>(raw.size());
            for (Object m : raw) {
                models.add(String.valueOf(m));
            }
            return models;
        } catch (Exception e) {
            log.warn("解析 provider models JSON 失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildDisplayName(LlmProviderEntity provider, String model) {
        String pd = provider.getDisplayName();
        return (pd == null || pd.isBlank()) ? model : pd + " · " + model;
    }
}
