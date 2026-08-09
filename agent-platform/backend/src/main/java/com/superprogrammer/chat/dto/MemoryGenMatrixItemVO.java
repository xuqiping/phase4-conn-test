package com.superprogrammer.chat.dto;

import lombok.Data;

/**
 * 计划12 · F · gen 开关矩阵行（总体设计 §3.1 + §5 开关矩阵）。
 * <p>
 * 前端 gen 矩阵 UI（{@code RagMemorySettingsTab}）每行 = 我所在的一个项目：
 * <ul>
 *   <li>{@code ownerEnabled}：owner 项目级开关（null/无行 = 默认开=true）。</li>
 *   <li>{@code memberEnabled}：本人会员覆写开关（null/无行 = 默认开=true）。</li>
 *   <li>{@code effective}：两者 AND（实际是否生成 L0/L1/L2；false 时仅写 raw turn）。</li>
 * </ul>
 * {@code role} 决定前端是否给 owner 开关可编辑（仅 OWNER 可改 owner 开关）。
 */
@Data
public class MemoryGenMatrixItemVO {
    private Long projectId;
    private String projectName;
    /** 当前用户在该项目的角色（OWNER/ADMIN/MEMBER），决定 owner 开关可否编辑。 */
    private String role;
    private Boolean ownerEnabled;
    private Boolean memberEnabled;
    private Boolean effective;
    /** 第二轮 #5：是否已推入记忆公共池（前端授权面板 pool 开关初值）。 */
    private Boolean memoryPoolPublic;
}
