package com.superprogrammer.media.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.llm.service.LlmProviderService;
import com.superprogrammer.media.dto.MediaImageRequest;
import com.superprogrammer.media.dto.MediaImageResult;
import io.netty.channel.ChannelOption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 火山方舟（经 ctaigw 网关）Seedream 生图 provider（OpenAI 兼容 /v1/images/generations）。
 *
 * <p>协议：<b>同步</b>——POST {endpoint} 直接返 {@code data[].url[]} + {@code usage.generated_images}，
 * 无建任务/轮询。故<b>不实现</b> {@link MediaGenProvider}（create/poll 形，那是视频异步任务协议），
 * 而以 {@link #generate} 一次返全量 {@link MediaImageResult}。worker 按 task_type 分流到本路径。
 *
 * <p>endpoint 即完整图片生成 URL（同 FR-001 全 URL 语义）：官方 Ark
 * {@code https://ark.cn-beijing.volces.com/api/v3/images/generations}，第三方网关（ctaigw）
 * {@code https://ai.ctaigw.cn/v1/images/generations}。运行时取 IMAGE provider 的 apiEndpoint 原样 POST。
 *
 * <p>provider 独立：生图用 IMAGE 类 provider（admin 在「全局模型供应商」建，配 endpoint/key/模型），
 * 与视频 seedance / chat doubao 解耦。每次调用前解析 {@code llm_providers}（按任务落库 providerId）
 * 的 endpoint + AES 解密 key；WebClient 按 (endpoint, apiKeyEnc) 指纹缓存，key 轮换自动重建。
 *
 * <p>安全：密钥只进 Authorization header，不落日志；失败原因走固定脱敏话术。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArkImageProvider {

    public static final String ID = "ark-image";

    /** 连接/响应超时。同步生图（尤其 4K/组图）耗时较长，response 给 90s（高于视频 30s）。 */
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(90);

    private final LlmProviderService llmProviderService;
    private final ObjectMapper objectMapper;

    /** WebClient 缓存（key=指纹 providerId|endpoint|密文），key/URL 改后自动换槽。 */
    private final Map<String, WebClient> clientCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 同步生图：POST /v1/images/generations → data[].url + usage.generated_images。
     *
     * @return {@link MediaImageResult}（success=false 时 errorMsg 带脱敏原因）
     */
    public MediaImageResult generate(MediaImageRequest request) {
        ResolvedArk ark = resolveArk(request.getProviderId());
        Map<String, Object> body = buildBody(request);
        try {
            String resp = ark.client.post()
                    .uri(ark.endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(RESPONSE_TIMEOUT);
            MediaImageResult result = parseResult(resp);
            if (result.isSuccess()) {
                log.info("生图成功 model={} 张数={} outputTokens={}",
                        request.getModel(), result.getGeneratedImages(), result.getOutputTokens());
            }
            return result;
        } catch (Exception e) {
            // 网关 4xx/5xx：提取响应体作用户可读原因（同 ArkSeedanceProvider 处理）。
            if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException wce) {
                String respBody = wce.getResponseBodyAsString();
                log.error("生图被网关拒绝 model={} status={} body={}",
                        request.getModel(), wce.getStatusCode().value(), truncate(respBody, 800), wce);
                return MediaImageResult.builder()
                        .success(false)
                        .errorMsg("生图失败 (HTTP " + wce.getStatusCode().value() + "): " + truncate(respBody, 500))
                        .build();
            }
            log.error("生图调用失败 model={}: {}", request.getModel(), e.getMessage(), e);
            return MediaImageResult.builder()
                    .success(false)
                    .errorMsg("生图调用失败: " + rootMessage(e))
                    .build();
        }
    }

    // ---------- 请求体构建 ----------

    /**
     * 按模型实际支持参数构建 body（不支持的参数不入参——提交侧已按 manifest 校验）。
     * package-private：单测直验 body 结构。
     */
    Map<String, Object> buildBody(MediaImageRequest r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", r.getModel());
        body.put("prompt", r.getPrompt() == null ? "" : r.getPrompt());
        // 参考图：多图融合用数组；OpenAI 兼容接口接受 string/array。
        if (r.getRefImageUrls() != null && !r.getRefImageUrls().isEmpty()) {
            body.put("image", r.getRefImageUrls());
        }
        if (r.getSize() != null && !r.getSize().isBlank()) {
            body.put("size", r.getSize());
        }
        if (r.getOutputFormat() != null && !r.getOutputFormat().isBlank()) {
            body.put("output_format", r.getOutputFormat());
        }
        if (r.getWatermark() != null) {
            body.put("watermark", r.getWatermark());
        }
        // response_format 固定 url（24h 临时链接，worker 即时下载落盘；MVP 不走 b64_json）。
        body.put("response_format", "url");
        // stream 固定 false（MVP 不做流式，IM-8 留无限画布阶段）。
        body.put("stream", false);
        // pro 独有：引导尺度。
        if (r.getGuidanceScale() != null) {
            body.put("guidance_scale", r.getGuidanceScale());
        }
        // optimize_prompt_options.mode（lite=standard / pro=standard+fast）。
        if (r.getOptimizeMode() != null && !r.getOptimizeMode().isBlank()) {
            body.put("optimize_prompt_options", Map.of("mode", r.getOptimizeMode()));
        }
        // lite 独有：组图 sequential_image_generation。
        if (r.getSequential() != null && !r.getSequential().isBlank()) {
            body.put("sequential_image_generation", r.getSequential());
            if ("auto".equalsIgnoreCase(r.getSequential()) && r.getMaxImages() != null) {
                body.put("sequential_image_generation_options", Map.of("max_images", r.getMaxImages()));
            }
        }
        // lite 独有：联网搜索 tools。
        if (Boolean.TRUE.equals(r.getWebSearch())) {
            body.put("tools", List.of(Map.of("type", "web_search")));
        }
        return body;
    }

    // ---------- 响应解析 ----------

    /** package-private：单测直验解析（data[].url 收集 + usage 计费张数）。 */
    MediaImageResult parseResult(String resp) {
        try {
            JsonNode root = objectMapper.readTree(resp);
            JsonNode data = root.path("data");
            List<String> urls = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode item : data) {
                    // 单张可能返 error（部分失败）：跳过 error 项，只收成功 url。
                    String url = item.path("url").asText(null);
                    if (url != null && !url.isBlank()) {
                        urls.add(url);
                    } else {
                        JsonNode err = item.path("error");
                        if (!err.isMissingNode()) {
                            log.warn("生图部分失败: {}", truncate(err.toString(), 200));
                        }
                    }
                }
            }
            JsonNode usage = root.path("usage");
            int generatedImages = usage.path("generated_images").asInt(urls.size());
            long outputTokens = usage.path("output_tokens").asLong(0L);
            if (urls.isEmpty()) {
                // data 全空或全 error：顶层可能带 error 字段。
                String topErr = root.path("error").path("message").asText("");
                return MediaImageResult.builder()
                        .success(false)
                        .errorMsg(topErr.isBlank() ? "生图未返回任何图片" : truncate(topErr, 256))
                        .generatedImages(0)
                        .build();
            }
            return MediaImageResult.builder()
                    .success(true)
                    .imageUrls(urls)
                    .generatedImages(generatedImages)
                    .outputTokens(outputTokens)
                    .build();
        } catch (Exception e) {
            return MediaImageResult.builder()
                    .success(false)
                    .errorMsg("生图响应解析失败: " + truncate(resp, 200))
                    .build();
        }
    }

    // ---------- IMAGE provider 解析 + WebClient 缓存 ----------

    /**
     * 解析生图 provider：按任务落库的 providerId 直连（图片任务提交时已由 model 反查落 providerId）。
     * providerId 为空 → 抛错（图片任务必有 providerId，无默认 provider 回退）。
     */
    private ResolvedArk resolveArk(Long providerId) {
        if (providerId == null) {
            throw new IllegalStateException("生图任务缺少 providerId，无法路由 IMAGE provider");
        }
        LlmProviderEntity provider = llmProviderService.getById(providerId);
        if (provider == null) {
            throw new IllegalStateException("生图 provider 已停用或删除（id=" + providerId + "），任务无法执行");
        }
        if (provider.getApiEndpoint() == null || provider.getApiEndpoint().isBlank()) {
            throw new IllegalStateException("生图 provider(id=" + providerId + ") 未配置 API 端点");
        }
        String apiKey = llmProviderService.getDecryptedApiKey(provider.getId());
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("生图 provider(id=" + providerId + ") 未配置 API Key");
        }
        String fingerprint = provider.getId() + "|" + provider.getApiEndpoint() + "|" + provider.getApiKeyEnc();
        WebClient client = clientCache.computeIfAbsent(fingerprint, k -> buildClient(apiKey));
        return new ResolvedArk(client, provider.getApiEndpoint().replaceAll("/+$", ""));
    }

    private WebClient buildClient(String apiKey) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(RESPONSE_TIMEOUT);
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        return m == null ? c.getClass().getSimpleName() : truncate(m, 200);
    }

    /** 解析后的生图调用上下文（WebClient + 图片端点完整 URL，key 已注入 header）。 */
    private record ResolvedArk(WebClient client, String endpoint) {}
}
