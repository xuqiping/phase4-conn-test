// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/UserVO.java
package com.superprogrammer.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserVO {

    private Long id;
    private String username;
    private String name;
    private String primaryDepartmentName;
    private String email;
    private String avatar;
    private String status;
    private OffsetDateTime lastLoginAt;
    private OffsetDateTime createdAt;
    private List<String> roles;
    private List<String> permissions;
}
