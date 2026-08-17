package com.superprogrammer.asset.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/** 未获权也可见的公众池字段白名单；不得加入词汇、资产、版本或文件字段。 */
@Data
@Builder
public class PublicProjectSummaryVO {
    private Long id;
    private String name;
    private String description;
    private String coverFileId;
    private String publicAccessMode;
    private Long publishedBy;
    private String publisherUsername;
    private OffsetDateTime publishedAt;
    private Boolean publishedByAdmin;
    private Long assetCount;
    private String myRequestStatus;
    private Boolean usable;
    /** 2x#4：项目媒体类型受控词汇（jsonb 原样透传），选择器按图片/视频过滤项目用。 */
    private String mediaTypes;
    /** 2x 待决策项（V100）：是否允许公共用户复制资产（公共 VIEWER 复制按钮显隐依据）。 */
    private Boolean allowPublicCopy;
}
