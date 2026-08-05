package com.superprogrammer.asset.dto;

import lombok.Data;

import java.util.List;

/**
 * 项目更新请求（FR-001 + 叙事角色受控词汇维护，设计方案 §二/§七）。
 *
 * <p>name/description/coverFileId 普通更新；narrativeRoles 维护受控词汇桶（editor+，L10）：
 * 移除某角色桶时，挂该桶的资产自动归入「通用」（二次确认在前端，后端幂等执行）。
 */
@Data
public class ProjectUpdateRequest {

    private String name;
    private String description;
    private String coverFileId;

    /** 叙事角色受控词汇桶（传则整体覆盖，移除项触发 L10 资产归「通用」）。 */
    private List<String> narrativeRoles;
}
