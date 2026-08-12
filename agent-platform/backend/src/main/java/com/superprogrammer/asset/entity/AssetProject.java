package com.superprogrammer.asset.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import com.superprogrammer.common.typehandler.JsonbStringTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * 项目资产库·项目（asset_projects，V56）。
 *
 * <p>项目是资产的唯一命名空间与授权边界（设计方案 §二）。双轴矩阵的容器：
 * 媒体类型（{@link #mediaTypes} 受控词汇 {key,category}）× 叙事角色（{@link #narrativeRoles} 受控词汇桶）。
 *
 * <p>授权：{@link #ownerId} = 唯一所有者；非 owner 成员走 asset_project_members（V58）。
 * 权限咽喉点 {@code AssetAclService.loadAccessible} 三判（owner/member/admin）。
 *
 * <p>{@link #narrativeRoles} 默认五桶 [人物,道具,场景,风格,通用]，由 owner/editor 维护（防标签腐烂）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "asset_projects", autoResultMap = true)
public class AssetProject extends BaseEntity {

    public static final String PUBLIC_ACCESS_OPEN = "OPEN";
    public static final String PUBLIC_ACCESS_APPROVAL_REQUIRED = "APPROVAL_REQUIRED";

    /** 项目所有者（唯一所有者，与 createdBy 解耦便于转让）。 */
    private Long ownerId;

    /** 项目名（≤100，安全清单）。 */
    private String name;

    /** 项目描述（可选）。 */
    private String description;

    /** 封面图 stored_files.file_id（可空，前端展示用）。 */
    private String coverFileId;

    /**
     * 叙事角色受控词汇桶 JSON 数组，默认五桶（双轴矩阵 轴B）。
     * 用 {@link JsonbStringTypeHandler} 做 String↔jsonb 转换（同 Canvas.snapshot）。
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String narrativeRoles;

    /**
     * 媒体类型受控词汇桶 JSON 数组（V60，默认 5 项 {key,category}），由 owner/editor 维护。
     * key=类型标签（可自定义），category=TEXT/IMAGE/VIDEO/AUDIO 决定处理链路。防标签腐烂（同 narrativeRoles）。
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String mediaTypes;

    /** 是否已发布到公众池；旧项目由 V87 默认 false。 */
    private Boolean publicPool;

    /** OPEN / APPROVAL_REQUIRED；未发布时为空。 */
    private String publicAccessMode;

    /** 本次发布人快照；转让项目不会改变。 */
    private Long publishedBy;

    /** 本次发布时间快照。 */
    private OffsetDateTime publishedAt;

    /** 本次是否由管理员发布，决定“官方发布”标记。 */
    private Boolean publishedByAdmin;
}
