package com.superprogrammer.projectgroup.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 项目组（project_groups，V133）。
 * <p>组长建组拉成员，个人积分划入组池共用（7x#3）。软删前置校验组池 balance=0。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("project_groups")
public class ProjectGroupEntity extends BaseEntity {

    /** 组名（≤64）。 */
    private String name;

    /** 组长（唯一管理权：划拨/回收/成员增删/限额调整）。 */
    private Long ownerUserId;

    /** 描述（≤500）。 */
    private String description;
}
