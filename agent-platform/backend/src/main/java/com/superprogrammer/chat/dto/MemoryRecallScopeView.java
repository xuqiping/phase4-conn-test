package com.superprogrammer.chat.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 计划12 · D · 召回 scope 回显（当前生效选择 + 用户可勾选项目集）。
 * <p>
 * controller GET 端点返此视图：前端据此渲染复选框（个人 + 可选项目），并回显上次持久化选择。
 */
@Data
@Builder
public class MemoryRecallScopeView {
    private boolean personalOn;
    private List<Long> projectIds;
    private String direction;
    private Integer relativeDays;
    private OffsetDateTime start;
    private OffsetDateTime end;
    private boolean includeDeparted;
    /** 用户可勾选的项目集（经 listAccessibleProjectIds 权限过滤后的本人可用项目）。 */
    private List<ProjectOption> availableProjects;

    /** 可勾选项目项（id + 展示名）。 */
    @Data
    @Builder
    public static class ProjectOption {
        private Long projectId;
        private String name;
    }
}
