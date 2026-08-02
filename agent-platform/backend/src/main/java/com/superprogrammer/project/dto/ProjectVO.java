package com.superprogrammer.project.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectVO {

    private Long id;
    private String name;
    private String description;
    private String icon;
    private Integer sortOrder;
    private Long ownerId;
    private OffsetDateTime createdAt;

    /** 当前用户在此项目的角色（OWNER/EDITOR/VIEWER），owner 自身=OWNER。 */
    private String myRole;

    private Long memberCount;
}
