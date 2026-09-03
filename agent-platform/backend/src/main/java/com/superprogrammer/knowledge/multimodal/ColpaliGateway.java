package com.superprogrammer.knowledge.multimodal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.knowledge.config.ColpaliProperties;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * ColPali 页面级视觉嵌入 sidecar 网关（WP5 Step4 / spec §7.2 路线 B）——**实验通道接口预留**：
 * 本版只交付接口与开关，不部署 sidecar、不接检索管线；通道真正接入等 sidecar 部署另立运维项。
 *
 * <p>适用场景：扫描版 PDF/图文混排报表——页图逐 patch 向量化（late interaction），检索时
 * MaxSim 逐块打分，表格图表的版面信息不靠 OCR 转写。
 *
 * <p>三重启用闸（全过才可用，见 {@link #availableFor}）：
 * ①全局 {@code rag.visual.colpali.enabled}；②KB 级 {@code knowledge_bases.colpali_enabled}（V174）；
 * ③{@link #healthy()} 探活（GET {baseUrl}/health）——失败**自动禁用**（WARN 一次，冷却期内不重打），
 * 到期自动重探自愈；检索主链零感知（通道缺席 = 行为与现在一致）。
 *
 * <p>**影子对比接线点预留（V117）**：转正路径走既有影子机制——
 * {@code RagShadowCoordinator.afterChampion(...)} 以 challenger 形式并行跑 ColPali 第 4 通道
 * （与 L0/L1/IMAGE 同参 RRF k=60），结果落 {@code rag_shadow_comparisons} 只记录不生效，
 * 增益验证后再切正。多向量 MaxSim 打分放 sidecar 内置重排端点或 OpenSearch script score，
 * pgvector 不承载多向量。
 */
@Slf4j
@Component
public class ColpaliGateway {

    private final ColpaliProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 探活缓存（volatile 双检 + synchronized 探测段，读多写一）。 */
    private volatile boolean probeHealthy;
    private volatile long probeAtMillis;
    /** 探活失败→自动禁用只 WARN 一次；恢复成功重置（下次再失败重告警，不刷屏也不哑）。 */
    private volatile boolean warnedDown;

    @Autowired
    public ColpaliGateway(ColpaliProperties properties) {
        this(properties, HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getProbeTimeoutMs())).build());
    }

    /** 测试可注入 mock HttpClient（同 SidecarHealthIndicator 先例）。 */
    ColpaliGateway(ColpaliProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    /** sidecar 是否可用：全局开关关 → 恒 false 且**零出站调用**；开 → 探活结果冷却缓存。 */
    public boolean healthy() {
        if (!properties.isEnabled()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (probeAtMillis > 0 && now - probeAtMillis < properties.getReprobeIntervalMs()) {
            return probeHealthy;
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            if (probeAtMillis > 0 && now - probeAtMillis < properties.getReprobeIntervalMs()) {
                return probeHealthy;
            }
            probeAtMillis = now;
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(properties.getBaseUrl() + "/health"))
                        .timeout(Duration.ofMillis(properties.getProbeTimeoutMs()))
                        .GET().build();
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                probeHealthy = response.statusCode() == 200;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                probeHealthy = false;
            } catch (Exception e) {
                // 超时/拒连/IO 异常一律 DOWN：sidecar 未部署时即此常态
                probeHealthy = false;
            }
            if (probeHealthy) {
                if (warnedDown) {
                    log.info("ColPali sidecar 探活恢复 baseUrl={} —— 实验通道自动重新可用", properties.getBaseUrl());
                }
                warnedDown = false;
            } else if (!warnedDown) {
                log.warn("ColPali sidecar 探活失败 baseUrl={} —— 实验通道自动禁用（{}ms 后自动重探；检索主链不受影响）",
                        properties.getBaseUrl(), properties.getReprobeIntervalMs());
                warnedDown = true;
            }
            return probeHealthy;
        }
    }

    /** 该 KB 是否可走 ColPali 通道：全局开 ∧ KB 级开 ∧ sidecar 活（调用方唯一闸门）。 */
    public boolean availableFor(KnowledgeBase kb) {
        return healthy() && kb != null && Boolean.TRUE.equals(kb.getColpaliEnabled());
    }

    /**
     * 页面图 → 页级多向量（骨架，sidecar 部署后启用）。请求 POST {baseUrl}/embed
     * {@code {"image":"<base64 png>"}}，响应 {@code {"patchVectors":[[...],...]}}。
     * 当前 sidecar 未部署，{@link #healthy()} 恒 false，本方法实际不可达（防御性抛 IllegalStateException）。
     */
    public PageEmbedding embedPage(byte[] pageImagePng) {
        if (!properties.isEnabled() || !healthy()) {
            throw new IllegalStateException("ColPali 通道未启用或 sidecar 不可用");
        }
        try {
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "image", Base64.getEncoder().encodeToString(pageImagePng)));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getBaseUrl() + "/embed"))
                    .timeout(Duration.ofMillis(properties.getProbeTimeoutMs() * 10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("ColPali embed HTTP " + response.statusCode());
            }
            JsonNode vectors = objectMapper.readTree(response.body()).path("patchVectors");
            float[][] patchVectors = new float[vectors.size()][];
            for (int i = 0; i < vectors.size(); i++) {
                JsonNode vec = vectors.get(i);
                float[] floats = new float[vec.size()];
                for (int j = 0; j < vec.size(); j++) {
                    floats[j] = (float) vec.get(j).asDouble();
                }
                patchVectors[i] = floats;
            }
            return new PageEmbedding("colpali", patchVectors);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ColPali embed 被中断", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("ColPali embed 失败: " + e.getMessage(), e);
        }
    }

    /** 页级多向量结果：patch 级向量组（MaxSim 打分在 sidecar/OpenSearch 侧，不进 pgvector）。 */
    public record PageEmbedding(String model, float[][] patchVectors) {}
}
