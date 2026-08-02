package com.superprogrammer.auth.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class DepartmentVO {

    private Long id;
    private Long tenantId;
    private String name;
    private String code;
    private Long parentId;
    private String description;
    private Integer sortOrder;
    private String status;
    private OffsetDateTime createdAt;
}
