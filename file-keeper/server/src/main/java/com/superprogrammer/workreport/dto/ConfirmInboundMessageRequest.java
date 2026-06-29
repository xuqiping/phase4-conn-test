package com.superprogrammer.workreport.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

public record ConfirmInboundMessageRequest(
        @NotNull(message = "确认动作不能为空")
        @Pattern(regexp = "CONFIRM|IGNORE", message = "确认动作必须是 CONFIRM 或 IGNORE")
        String action,

        Map<String, Object> correctedPayload
) {
}
