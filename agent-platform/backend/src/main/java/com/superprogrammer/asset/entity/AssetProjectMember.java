package com.superprogrammer.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 项目资产库·成员授权（asset_project_members，V58）。
 *
 * <p>项目数据权限（第二层）：非 owner 的项目成员（viewer/editor）。
 * owner 不落本表（owner_id 即所有者）；admin 平台旁路全量。
 *
 * <p>双层授权：被授权用户**同样需要 asset:write 平台权限**（第一层），两层都过才可见项目（设计方案 §七 7.1）。
 * 离开授权即失访（L1）：移除后项目从其列表消失；画布已引用快照不受影响。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "asset_project_members", autoResultMap = true)
public class AssetProjectMember extends BaseEntity {

    /** 项目角色枚举常量。 */
    public static final String ROLE_VIEWER = "VIEWER";
    public static final String ROLE_EDITOR = "EDITOR";

    /** 所属项目（FK asset_projects）。 */
    private Long projectId;

    /** 被授权用户。 */
    private Long userId;

    /** 项目角色：VIEWER(只读引用)/EDITOR(可写)。owner 不落表。 */
    private String role;

    /** 授权人 userId（审计用）。 */
    private Long grantedBy;
}
