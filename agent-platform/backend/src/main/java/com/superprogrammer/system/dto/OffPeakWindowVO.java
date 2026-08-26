package com.superprogrammer.system.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * D8（V160）：闲时时段窗口。"HH:mm"；end&le;start 视为跨零点窗口（如 22:00-08:00）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OffPeakWindowVO {
    private String start;
    private String end;
}
