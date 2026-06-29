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
public class ProjectMemberVO {

    private Long id;
    private Long projectId;
    private Long userId;
    private String username;
    /** OWNER / EDITOR / VIEWER */
    private String role;
    private OffsetDateTime createdAt;
}
