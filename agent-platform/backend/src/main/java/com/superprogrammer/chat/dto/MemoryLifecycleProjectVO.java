package com.superprogrammer.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 计划12 · F-4b 前置 · 生命周期折叠板列表行（总体设计 §3.7）。
 * <p>
 * 已离开项目（membership DEPARTED）/ 已删除项目（turns.deleted_project_ids 引用）各一行。
 * {@code departedAt} 仅 departed 列表有值；{@code turnCount} = 本人在该项目可拉取的流水账条数
 * （departed：project_ids 含该项目；deleted：deleted_project_ids 含该项目），0 条时前端可不展示。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryLifecycleProjectVO {

    private Long projectId;
    private String projectName;
    private OffsetDateTime departedAt;
    private Integer turnCount;
}
