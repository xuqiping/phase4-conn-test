package com.superprogrammer.asset.dto;

import lombok.Data;

import java.util.List;

/**
 * 分镜保存请求（S18，5 字段流水线字段 1/2/4 可编辑；3/5 占位不入本请求）。
 *
 * <p>字段1 {@link #prompt}：镜头提示词描述。
 * 字段2 {@link #entityRefs}：实体→@资产键值对（人物/道具/场景图片资产）。
 * 字段4 {@link #videoInputs}：生视频输入（音频/图片参考资产）。
 *
 * <p>安全：entityRefs/videoInputs 的 assetId 须 ∈ 同项目可读资产，非法剔除不抛错（保痕迹）。
 * 服务端富化 name/mediaType（取自目录非客户端，防越权伪造）。
 */
@Data
public class StoryboardSaveRequest {

    /** 字段1：镜头提示词（≤8000）。null=不改。 */
    private String prompt;

    /** 字段2：实体→@资产键值对。null=不改。 */
    private List<EntityRef> entityRefs;

    /** 字段4：生视频输入键值对。null=不改。 */
    private VideoInputs videoInputs;

    /** 实体引用键值对（字段 2/4 复用）。key 必填（≤32）；assetId 非法时服务端置 null。 */
    @Data
    public static class EntityRef {
        /** 实体键名（如「主角」「道具·剑」）。 */
        private String key;
        /** 被引资产 id；非法/跨项目/已删 → 服务端剔除。 */
        private Long assetId;
    }

    /** 字段4 生视频输入：音频参考 + 图片参考两组。 */
    @Data
    public static class VideoInputs {
        private List<EntityRef> audioRefs;
        private List<EntityRef> imageRefs;
    }
}
