package com.superprogrammer.asset.dto;

import com.superprogrammer.asset.enums.AssetRole;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 项目视图（列表/详情）。
 *
 * <p>{@link #role} = 当前用户在本项目的角色（OWNER/EDITOR/VIEWER），前端据此分「我的项目/共享给我」Tab +
 * 控制操作按钮显隐（设计方案 §十/§七 7.2）。
 */
@Data
@Builder
public class ProjectVO {

    private Long id;
    private String name;
    private String description;
    private String coverFileId;
    private Long ownerId;
    /** 叙事角色受控词汇桶（解析后的数组，前端矩阵渲染）。 */
    private List<String> narrativeRoles;
    /** 媒体类型受控词汇桶（V60，{key,category}；前端顶栏/上传/新建下拉同源）。 */
    private List<MediaTypeDef> mediaTypes;
    /** 当前用户在本项目的角色。 */
    private AssetRole role;
    /** 公众池发布快照；详情接口仅对已有内容读权用户返回完整项目。 */
    private Boolean publicPool;
    private String publicAccessMode;
    private Long publishedBy;
    private OffsetDateTime publishedAt;
    private Boolean publishedByAdmin;
    /** 2x第三轮C6：OWNER 是否开放成员打分（V124）。 */
    private Boolean memberScoringEnabled;
    /** 2x第三轮C6：内容模式 SHARED/PERSONAL（V124，决策 D1）。 */
    private String contentMode;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
