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

    /** 2x第三轮C5：内容模式 SHARED（成员可删改所有内容，存量行为）/ PERSONAL（EDITOR 仅能删改自己上传的）。 */
    public static final String CONTENT_MODE_SHARED = "SHARED";
    public static final String CONTENT_MODE_PERSONAL = "PERSONAL";

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

    /**
     * 2x 待决策项（V100）：发布时 OWNER/admin 自选「是否允许公共用户复制资产」。
     * FALSE = 公共 VIEWER 仅可引用不可复制（copy 接口 ASSET_COPY_FORBIDDEN）；
     * 项目成员/OWNER/admin 不受限。跨 unpublish→再发布保留（发布弹窗回显）。
     */
    private Boolean allowPublicCopy;

    /**
     * 2x第三轮C5（V124）：OWNER 是否开放成员打分（默认 FALSE 关）。
     * 关 = 只有 OWNER 能评分；开 = 被授权成员也可评（百分制，参与均分）。
     */
    private Boolean memberScoringEnabled;

    /**
     * 2x第三轮C5（V124）：内容模式（决策 D1）——
     * SHARED=成员可删改项目内所有内容（存量默认，升级零行为变化）；
     * PERSONAL=EDITOR 仅能删改 assets.created_by=自己 的内容。
     */
    private String contentMode;
}
