package com.superprogrammer.projectgroup.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import com.superprogrammer.common.typehandler.JsonbStringTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * 项目组（project_groups，V133；V138 加共享化列）。
 * <p>组长建组拉成员，个人积分划入组池共用（7x#3）。软删前置校验组池 balance=0。
 * <p>V138（17x）：成员产出可见性（OWN/ALL + 按模块稀疏覆盖）+ 公共池招募开关。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "project_groups", autoResultMap = true)
public class ProjectGroupEntity extends BaseEntity {

    /** 成员产出可见性：成员仅看自己。 */
    public static final String VIS_OWN = "OWN";
    /** 成员产出可见性：成员互见全组。 */
    public static final String VIS_ALL = "ALL";

    /** 组名（≤64）。 */
    private String name;

    /** 组长（唯一管理权：划拨/回收/成员增删/限额调整/可见性/公共池）。 */
    private Long ownerUserId;

    /** 描述（≤500）。 */
    private String description;

    /** 成员产出可见性（V138，17x#2）：OWN=成员仅看自己（默认）；ALL=成员互见全组。组长/admin 恒全量。 */
    private String memberOutputVisibility;

    /**
     * 按模块稀疏覆盖 JSONB（V138，17x#2）：{"CHAT":"ALL","IMAGE":"OWN"}；
     * 模块缺省回落 {@link #memberOutputVisibility}。String↔jsonb 同 MediaGenTask.requestConfig 先例。
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String moduleVisibilityOverrides;

    /** 公共池招募开关（V138，17x#4）：true=全平台可见可申请加入。 */
    private Boolean publicPool;

    /** 推入公共池操作人（留痕）。 */
    private Long publicPublishedBy;

    /** 推入公共池时间。 */
    private OffsetDateTime publicPublishedAt;
}
