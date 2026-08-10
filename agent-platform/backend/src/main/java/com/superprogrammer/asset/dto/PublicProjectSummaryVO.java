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
}
