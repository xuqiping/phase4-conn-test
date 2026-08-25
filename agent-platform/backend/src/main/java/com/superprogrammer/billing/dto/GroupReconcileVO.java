package com.superprogrammer.billing.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 组池对账总览（D4 · 20x-3，Q9=A：顶卡 + 仅异常组明细）。
 * <p>balanced=全部组恒等式与双账本交叉均平（abnormalGroups 空）；
 * totals=全量组 Σ（含正常组），diff/crossDiff 合计理论上恒 0——非 0 即有组不平。
 */
public record GroupReconcileVO(boolean balanced,
                               Totals totals,
                               List<GroupReconcileRowVO> abnormalGroups) {

    /** 顶卡合计（全量组口径）。 */
    public record Totals(BigDecimal netAllocated,
                         BigDecimal consumed,
                         BigDecimal refunded,
                         BigDecimal balance,
                         BigDecimal diff,
                         BigDecimal crossDiff) {
    }
}
