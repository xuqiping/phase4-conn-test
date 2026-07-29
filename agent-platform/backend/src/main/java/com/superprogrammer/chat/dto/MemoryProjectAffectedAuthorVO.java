package com.superprogrammer.chat.dto;

import lombok.Data;

/**
 * 计划12 · 生命周期写侧 hook · 项目删除波及作者（总体设计 §3.7 M1）。
 * <p>
 * 「曾写记忆的每个成员」= 该项目下仍有未软删 turn 的作者（project_ids @> [P]），
 * turnCount 供 {@code PROJECT_DELETED_AFFECTED} 通知文案（「你在其中的 N 条记忆…」）。
 */
@Data
public class MemoryProjectAffectedAuthorVO {

    private Long userId;

    private Long turnCount;
}
