package com.superprogrammer.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ColPali 页面级视觉嵌入实验通道配置（WP5 Step4 / spec §7.2 路线 B，prefix {@code rag.visual.colpali}）。
 * 仿 {@link RagShadowProperties}：默认全关——sidecar 不在本版交付（接口预留），部署另立运维项。
 *
 * <p>全局开关（本类 enabled）+ KB 级开关（knowledge_bases.colpali_enabled，V174）双闸串联：
 * 仅两者同时开启的库才可能走 ColPali 第 4 检索通道；任一关 → 检索行为与本通道不存在时一致。
 */
@Component
@ConfigurationProperties(prefix = "rag.visual.colpali")
public class ColpaliProperties {
    /** 实验通道总开关（默认关；开 = 允许探活 sidecar，配合 KB 级开关生效）。 */
    private boolean enabled = false;
    /** sidecar 基址（自托管 ColPali 推理服务；默认本机占位端口）。 */
    private String baseUrl = "http://127.0.0.1:8399";
    /** 探活超时（ms）。超时/拒连/非 200 一律视为不可用。 */
    private long probeTimeoutMs = 2000;
    /** 探活结果冷却（ms）：冷却期内直接用缓存判定，不重复打 sidecar；到期自动重探（故障自愈）。 */
    private long reprobeIntervalMs = 60_000;

    public ColpaliProperties() {}

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public long getProbeTimeoutMs() { return probeTimeoutMs; }
    public void setProbeTimeoutMs(long value) { this.probeTimeoutMs = value; }
    public long getReprobeIntervalMs() { return reprobeIntervalMs; }
    public void setReprobeIntervalMs(long value) { this.reprobeIntervalMs = value; }
}
