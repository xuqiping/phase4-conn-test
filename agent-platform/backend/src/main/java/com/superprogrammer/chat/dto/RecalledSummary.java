package com.superprogrammer.chat.dto;

import com.superprogrammer.chat.entity.MemorySummary;

/**
 * 计划12 · D-4 · 召回读总结产物（总体设计 §3.3 ④⑤）。
 * <p>
 * {@code includeL2} = 是否展开 L2 详述（{@code false} = 只读 L1 概要，省篇幅）。
 * <ul>
 *   <li>总结 ≤ {@code 5} 条 → 全 {@code includeL2=true}（跳 reflect 省一次 LLM）。</li>
 *   <li>> {@code 5} 条 → reflect LLM 选深读子集，命中者 {@code true}，其余 {@code false}。</li>
 *   <li>reflect 失败 → 全 {@code false}（降级只读 L1）。</li>
 * </ul>
 * 供 D-6 装配按 includeL2 决定拼 L1 还是 L1+L2。
 */
public record RecalledSummary(MemorySummary summary, boolean includeL2) {
}
