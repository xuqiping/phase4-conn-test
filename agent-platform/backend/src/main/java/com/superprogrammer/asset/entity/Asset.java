package com.superprogrammer.asset.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import com.superprogrammer.common.typehandler.JsonbStringTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 项目资产库·资产主表（assets，V57）。
 *
 * <p>双轴矩阵主记录：
 * <ul>
 *   <li>轴A · {@link #mediaType} 媒体类型标签（项目受控词汇 key，可自定义）+ {@link #mediaCategory}
 *       处理类别（系统固定 TEXT/IMAGE/VIDEO/AUDIO，决定编辑器/mime/预览链路）——两层设计（V60/§C1b）</li>
 *   <li>轴B · 叙事角色：通过 asset_role_links 多对多挂载（一资产可挂多角色，不查 JSONB）</li>
 * </ul>
 *
 * <p>状态机 {@link #status}：DRAFT(草稿)→LOCKED(已定稿)→ARCHIVED(归档)。
 * 定稿后被引用=锁版本快照（asset_versions），资产升级不影响已引用方（设计方案 §六）。
 *
 * <p>注意：{@link #currentVersion} 是域版本号（最新版本，乐观锁并发建版用），
 * 与 BaseEntity.version（MyBatis-Plus 乐观锁行版本）不同。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "assets", autoResultMap = true)
public class Asset extends BaseEntity {

    /** 媒介类型枚举常量（轴A 标签，默认五类，项目可扩展自定义 key）。 */
    public static final String MEDIA_PROMPT = "PROMPT";
    public static final String MEDIA_SCRIPT = "SCRIPT";
    public static final String MEDIA_IMAGE = "IMAGE";
    public static final String MEDIA_VIDEO = "VIDEO";
    public static final String MEDIA_AUDIO = "AUDIO";

    /** 处理类别常量（系统固定四类，V60，决定编辑器/mime/预览/gen_meta/画布映射链路）。 */
    public static final String CATEGORY_TEXT = "TEXT";
    public static final String CATEGORY_IMAGE = "IMAGE";
    public static final String CATEGORY_VIDEO = "VIDEO";
    public static final String CATEGORY_AUDIO = "AUDIO";

    /** 状态机枚举常量。 */
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_LOCKED = "LOCKED";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    /** 所属项目（授权边界，FK asset_projects）。 */
    private Long projectId;

    /** 轴A 媒体类型标签（项目受控词汇 key，可自定义如「地图」；仅分类）。 */
    private String mediaType;

    /** 处理类别（系统固定 TEXT/IMAGE/VIDEO/AUDIO，V60；决定编辑器形态/mime 校验/预览/gen_meta 提取）。 */
    private String mediaCategory;

    /** 资产名（≤100，安全清单）。 */
    private String name;

    /** 描述层·用户可编辑。 */
    private String description;

    /** 标签数组 JSON（第三自由层，临时/探索性标记）。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String tags;

    /** 生命周期状态机：DRAFT/LOCKED/ARCHIVED。 */
    private String status;

    /**
     * 非文件类资产正文 JSON：提示词正文/剧本分场结构/一致性包字段。
     * 一致性包字段（人物/道具/场景类）：主参考图 id / 多角度图集 / 标准描述片段 / 生成参数基线（设计方案 §五）。
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String content;

    /** 生成谱系 JSON：prompt/model/seed/参考资产id[]/来源画布节点（AI 复现性关键）。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String genMeta;

    /** 域版本号（最新版本号，乐观锁并发建版 WHERE current_version=?，区别于乐观锁行版本）。 */
    private Integer currentVersion;
}
