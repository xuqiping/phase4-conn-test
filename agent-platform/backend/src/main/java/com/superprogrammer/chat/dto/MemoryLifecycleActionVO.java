package com.superprogrammer.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 计划12 · F-4b 前置 · copy-to / restore 结果（总体设计 §3.7）。
 * <p>
 * 返自建新项目 id/名 + 实际拉取（重挂）的流水账条数，前端折叠板刷新列表 + badge。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryLifecycleActionVO {

    private Long newProjectId;
    private String newProjectName;
    private Integer affectedTurns;
}
