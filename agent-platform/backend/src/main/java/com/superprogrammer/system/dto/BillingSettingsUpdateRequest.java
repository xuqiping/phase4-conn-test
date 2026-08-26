package com.superprogrammer.system.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class BillingSettingsUpdateRequest {
    /** L7 低余额阈值；null=不改动（部分更新）。 */
    @Min(0)
    @Max(1000000)
    private Long lowBalanceThreshold;

    /** L7 低余额最大在途任务数；null=不改动。 */
    @Min(1)
    @Max(100)
    private Long lowBalanceMaxInflight;

    /** D8（V160）：闲时时段；null=不改动；非法（非 HH:mm / 超 4 段）拒绝保存 */
    private OffPeakScheduleVO offPeak;
}
