// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/UpdateProfileRequest.java
package com.superprogrammer.auth.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 17x + D1（12x-1）：本人修改个人信息（昵称/姓名 users.name、备注 users.remark）。
 *
 * <p>name/remark 可空/纯空白 = 清除（name 清除后项目组/账单等展示回落 username）。
 */
@Data
public class UpdateProfileRequest {

    /** 昵称/姓名（去空白落库；空=清除）。 */
    @Size(max = 32, message = "昵称/姓名最长 32 字")
    private String name;

    /** 账号备注（去空白落库；空=清除；管理列表 keyword 可筛）。 */
    @Size(max = 128, message = "备注最长 128 字")
    private String remark;
}
