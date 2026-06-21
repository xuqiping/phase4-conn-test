package com.superprogrammer.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DepartmentRequest {

    @NotBlank
    private String name;

    private String code;

    private Long parentId;

    private String description;

    private Integer sortOrder;
}
