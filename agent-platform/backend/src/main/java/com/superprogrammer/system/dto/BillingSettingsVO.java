package com.superprogrammer.system.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingSettingsVO {
    /** L7 低余额并行闸门阈值（SEC-FR-126）：余额低于此值禁多任务并行，默认 100。 */
    private Long lowBalanceThreshold;

    /** L7 低余额最大在途任务数（SEC-FR-126），默认 1。 */
    private Long lowBalanceMaxInflight;

    /** D8（V160）：闲时时段（价表闲时列的生效窗口）；null=从未配置（等价 disabled） */
    private OffPeakScheduleVO offPeak;
}
