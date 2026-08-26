package com.superprogrammer.system.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * D8（V160）：闲时时段配置（键 billing.off-peak.schedule，JSON 存储）。
 * <p>读侧（PricingService.isOffPeak）每请求实时查：enabled=false/配置缺失/非法 → 恒忙时
 * （宁多收不少收）。周末未配窗口时沿用工作日窗口。timezone 当前固定 Asia/Shanghai（只读回显）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OffPeakScheduleVO {
    private Boolean enabled;
    /** 只读回显；当前固定 Asia/Shanghai（写入侧强制，提交其他值被覆盖） */
    private String timezone;
    /** 工作日闲时窗口；最多 4 段 */
    private List<OffPeakWindowVO> weekday;
    /** 周末闲时窗口；最多 4 段；空=沿用工作日窗口 */
    private List<OffPeakWindowVO> weekend;
}
