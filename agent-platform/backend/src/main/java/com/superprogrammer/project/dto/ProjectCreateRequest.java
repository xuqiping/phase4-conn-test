package com.superprogrammer.project.dto;

import lombok.Data;

@Data
public class ProjectCreateRequest {

    private String name;
    private String description;
    private String icon;
    private Integer sortOrder;
}
