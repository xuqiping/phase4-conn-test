package com.superprogrammer.asset.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

import java.util.List;

/**
 * 项目更新请求（FR-001 + 叙事角色受控词汇维护，设计方案 §二/§七）。
 *
 * <p>name/description/coverFileId 普通更新；narrativeRoles 维护两级受控词汇（editor+，L10）：
 * 移除子类归父级、移除一级归「通用」（修复XI 两级口径）；入参双容错（string|object 元素同判）。
 */
@Data
public class ProjectUpdateRequest {

    private String name;
    private String description;
    private String coverFileId;

    /** 叙事角色两级受控词汇（传则整体覆盖，移除项触发 L10 重指派；string/object 元素双容错）。 */
    @JsonDeserialize(using = RoleVocabDeserializer.class)
    private List<RoleVocab> narrativeRoles;

    /** 媒体类型受控词汇桶（V60，传则整体覆盖；移除项触发资产归同 category 首项）。 */
    private List<MediaTypeDef> mediaTypes;
}
