package com.superprogrammer.asset.dto;

import lombok.Data;

/**
 * 项目设置更新（2x第三轮C6，PATCH /api/assets/projects/{id}/settings）。
 *
 * <p>局部更新语义：null = 不改。仅 OWNER（requireManage）。
 * contentMode 枚举受控 SHARED/PERSONAL（决策 D1）；切 PERSONAL 的二次确认由前端弹窗承担，后端直接生效。
 */
@Data
public class ProjectSettingsRequest {

    /** 是否开放成员打分（关=仅 OWNER 可评）。null 不改。 */
    private Boolean memberScoringEnabled;

    /** 内容模式 SHARED/PERSONAL。null 不改；非受控值 400。 */
    private String contentMode;
}
