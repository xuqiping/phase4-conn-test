package com.superprogrammer.chat.dto;

import lombok.Data;

/**
 * 计划12 · D-7 · 召回 preview 请求（调试界面用，总体设计 §3.3 + 运维 preview 透出）。
 * <p>
 * {@code query} 必填（召回 + 选标签 + reflect 判据）；{@code scope} 可 null（默认 {个人} 或读上次偏好）。
 */
@Data
public class MemoryRecallPreviewRequest {

    /** 用户当前问题（召回 query，必填，非空）。 */
    private String query;

    /** 本次 scope 勾选；null → 默认 {个人}（pipeline resolver 兜底）。 */
    private MemoryRecallScopeRequest scope;
}
