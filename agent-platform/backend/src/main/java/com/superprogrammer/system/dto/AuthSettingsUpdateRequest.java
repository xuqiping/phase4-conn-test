package com.superprogrammer.system.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AuthSettingsUpdateRequest {
    @NotNull
    @Min(60000)
    @Max(604800000)
    private Long accessTokenExpirationMs;

    /** A8 单点登录开关；null=不改动（部分更新）。 */
    private Boolean singleSessionEnabled;
}
