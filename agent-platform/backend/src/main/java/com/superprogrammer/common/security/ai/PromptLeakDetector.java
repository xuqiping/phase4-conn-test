// agent-platform/backend/src/main/java/com/superprogrammer/common/security/ai/PromptLeakDetector.java
package com.superprogrammer.common.security.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.agent.entity.SkillStep;
import com.superprogrammer.agent.mapper.SkillStepMapper;
import com.superprogrammer.workflow.entity.WorkflowNode;
import com.superprogrammer.workflow.mapper.WorkflowNodeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * System Prompt 泄露指纹检测（安全体系 S3 · SEC-FR-053 / LLM07②）：
 * 只指纹<b>静态 prompt 资产</b>——SkillStep.config.systemPrompt + WorkflowNode.config.systemPrompt
 * （LLM 节点）。<b>不</b>按请求内动态 system 消息算指纹（记忆/RAG 证据是用户可复述的自己的数据，
 * 按请求算会把「用户背诵自己记忆」误判成泄露——plan 自 critique 修正点）。
 *
 * <p>算法：资产文本切 32 字符 shingle（步进 8）取 hash 进程内缓存（10min TTL，
 * Skill/Workflow 更新钩子调 {@link #evict()} 主动失效）；响应文本同窗口滑扫，
 * <b>连续命中 ≥2</b> 才判泄露（单窗 32 字符 hash 碰撞不可忽略，相邻双窗同中极大压误报），
 * 命中区段替换 {@code [系统提示词内容已遮蔽]}。
 *
 * <p>纯检测组件不落事件——事件/指标/开关在 {@link OutputSanitizer} 统一收口（网关只认一个出口）。
 * 本类任何异常由调用方吞掉透传原文（检测层不自残）。
 */
@Slf4j
@Component
public class PromptLeakDetector {

    /** shingle 窗口与步进：32 字符窗、8 步进（资产与响应同口径）。 */
    static final int WINDOW = 32;
    static final int STEP = 8;
    /** 连续命中阈值：≥2 个相邻窗口同中才判泄露。 */
    static final int MIN_CONSECUTIVE = 2;
    static final String LEAK_MASK = "[系统提示词内容已遮蔽]";
    private static final long TTL_NANOS = java.time.Duration.ofMinutes(10).toNanos();

    /** 指纹快照：hash 集 + 加载时刻；引用整体换（读路径无锁，重建 synchronized）。 */
    private record Snapshot(Set<Integer> shingles, long loadedAtNanos) {
    }

    private final SkillStepMapper skillStepMapper;
    private final WorkflowNodeMapper workflowNodeMapper;
    private final ObjectMapper objectMapper;
    private final AtomicReference<Snapshot> cache = new AtomicReference<>();

    public PromptLeakDetector(SkillStepMapper skillStepMapper,
                              WorkflowNodeMapper workflowNodeMapper,
                              ObjectMapper objectMapper) {
        this.skillStepMapper = skillStepMapper;
        this.workflowNodeMapper = workflowNodeMapper;
        this.objectMapper = objectMapper;
    }

    /** Skill 步骤 / Workflow 节点保存后调用：指纹立即失效，下次检测全量重建。 */
    public void evict() {
        cache.set(null);
    }

    /**
     * 检测并遮蔽。返回 null = 无泄露（调用方用原串）；非 null = 遮蔽后文本。
     * 异常向上抛，由 OutputSanitizer 统一吞（本类不自残）。
     */
    public String maskIfLeaked(String text) {
        if (text == null || text.length() < WINDOW) {
            return null;
        }
        Set<Integer> shingles = shingles();
        if (shingles.isEmpty()) {
            return null;
        }
        // 单遍滑窗：收集「连续命中 ≥2」的区段 [start, end)（end=末命中窗尾）
        List<int[]> regions = new ArrayList<>();
        int runStart = -1;
        int runEnd = -1;
        int runHits = 0;
        int prevHitIdx = -2;
        int idx = 0;
        for (int off = 0; off + WINDOW <= text.length(); off += STEP, idx++) {
            if (shingles.contains(hash(text, off, WINDOW))) {
                if (idx == prevHitIdx + 1) {
                    runHits++;
                    runEnd = off + WINDOW;
                } else {
                    closeRun(regions, runStart, runEnd, runHits);
                    runStart = off;
                    runEnd = off + WINDOW;
                    runHits = 1;
                }
                prevHitIdx = idx;
            }
        }
        closeRun(regions, runStart, runEnd, runHits);
        if (regions.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(text.length());
        int copied = 0;
        for (int[] r : regions) {
            sb.append(text, copied, r[0]).append(LEAK_MASK);
            copied = Math.max(copied, r[1]);
        }
        sb.append(text, copied, text.length());
        return sb.toString();
    }

    /** run 收尾：连续命中达阈值才进遮蔽区段列表。 */
    private static void closeRun(List<int[]> regions, int start, int end, int hits) {
        if (start >= 0 && hits >= MIN_CONSECUTIVE) {
            regions.add(new int[]{start, end});
        }
    }

    /** [off, off+len) 窗口 hash（String.hashCode 口径，双窗连续已压碰撞误报）。 */
    private static int hash(String text, int off, int len) {
        return text.substring(off, off + len).hashCode();
    }

    /** 指纹集：TTL 内直用快照；过期/为空 synchronized 全量重建（SkillStep+WorkflowNode 库存级，量小）。 */
    private Set<Integer> shingles() {
        Snapshot snap = cache.get();
        long now = System.nanoTime();
        if (snap != null && now - snap.loadedAtNanos() < TTL_NANOS) {
            return snap.shingles();
        }
        synchronized (this) {
            snap = cache.get();
            now = System.nanoTime();
            if (snap != null && now - snap.loadedAtNanos() < TTL_NANOS) {
                return snap.shingles();
            }
            Set<Integer> rebuilt = new HashSet<>();
            for (SkillStep step : skillStepMapper.selectList(null)) {
                addAsset(rebuilt, extractSystemPrompt(step.getConfig()));
            }
            for (WorkflowNode node : workflowNodeMapper.selectList(null)) {
                addAsset(rebuilt, extractSystemPrompt(node.getConfig()));
            }
            Snapshot fresh = new Snapshot(Set.copyOf(rebuilt), System.nanoTime());
            cache.set(fresh);
            log.info("Prompt指纹重建 assets.shingles={} sources=(skillSteps, workflowNodes)", rebuilt.size());
            return fresh.shingles();
        }
    }

    /** config JSON 取 systemPrompt 字段；非法 JSON/缺失 → null。 */
    private String extractSystemPrompt(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(configJson).at("/systemPrompt");
            return node.isTextual() ? node.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 单资产切 shingle 入集；短于一个窗的资产跳过（无指纹意义）。 */
    private static void addAsset(Set<Integer> sink, String asset) {
        if (asset == null || asset.length() < WINDOW) {
            return;
        }
        for (int off = 0; off + WINDOW <= asset.length(); off += STEP) {
            sink.add(hash(asset, off, WINDOW));
        }
    }
}
