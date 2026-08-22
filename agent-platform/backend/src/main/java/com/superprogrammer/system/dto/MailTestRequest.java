package com.superprogrammer.system.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 邮件通道测试发信请求（12x 认证通道页「发送测试邮件」按钮）。 */
@Data
public class MailTestRequest {
    @NotBlank(message = "收件邮箱不能为空")
    @Email(message = "收件邮箱格式不正确")
    private String to;
}
