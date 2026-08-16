package com.superprogrammer.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 5x #7 收录确认点选请求（只传选择，不传内容——原文在服务端消息 metadata，防前端篡改注入）。
 */
@Data
public class InclusionConfirmRequest {

    /** ANSWER=需要回答（携服务端存原文走全量生成）；DECLINE=不用了（收尾消息，不调 LLM）。 */
    @NotBlank(message = "choice 不能为空")
    @Pattern(regexp = "ANSWER|DECLINE", message = "choice 只能是 ANSWER 或 DECLINE")
    private String choice;
}
