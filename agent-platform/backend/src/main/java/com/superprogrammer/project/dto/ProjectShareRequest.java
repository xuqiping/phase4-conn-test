package com.superprogrammer.project.dto;

import lombok.Data;

@Data
public class ProjectShareRequest {

    private Long userId;

    /** EDITOR / VIEWER（OWNER 仅 owner 自身，不可手动授予） */
    private String role;
}
