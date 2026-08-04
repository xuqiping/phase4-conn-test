package com.superprogrammer.chat.dto;

import lombok.Data;

/**
 * 计划12 · D-2 · 召回聚合标签元（总体设计 §3.3 ② + §6 向量 3/4）。
 * <p>
 * scope 内流水账 {@code tag_ids} + 总结 {@code tag_id} 经 {@code memory_tags} join 后的去重产物。
 * <b>对外只露 label + subject + topic</b>（向量 4）；{@code aliases/anchor_*} 不外露。
 * {@code ownerUserId} 保留供 D-6 ⑦装配按 subject 聚合打 owner 前缀（【张三·爱好】）。
 * <p>
 * <b>去重粒度 = tag_id</b>（同 tag 一条，天然含 owner 维度）；跨 user 的 (subject,topic) 合并是装配展示层（D-6），
 * 非聚合层——保留每 owner 一条防「通过标签清单组合推断他人隐私」的同时不丢 owner。
 */
@Data
public class RecallTagMeta {
    /** 标签 id（个人 scope 内唯一；项目 scope 代表行）。 */
    private Long id;
    /** L0 主体（默认「我」/表哥/配偶…）。 */
    private String subject;
    /** L0 主题（居住/爱好/工作…）。 */
    private String topic;
    /** 对外展示名（同义归一后规范名）。 */
    private String label;
    /** 标签所属作者（装配打前缀用）。 */
    private Long ownerUserId;
    /** 复用次数（排序+展示用）。 */
    private Integer usageCount;
}
