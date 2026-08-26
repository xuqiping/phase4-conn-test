package com.superprogrammer.billing.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 组池对账总览（D4 · 20x-3，Q9=A：顶卡 + 仅异常组明细；7x-1 增下钻）。
 * <p>balanced=本响应口径内全部组恒等式与双账本交叉均平；
 * totals=本响应口径内 Σ（diff/crossDiff 合计理论上恒 0——非 0 即有组不平）。
 * <p><b>两明细字段语义（7x-1 参数决定）</b>：
 * 默认（无参）→ groups=null，abnormalGroups=仅异常组（Q9=A 原状）；
 * groupId 选中 / includeAll=true → groups=口径内全组行（含平组，前端
 * {@code groups ?? abnormalGroups} 单数据源），abnormalGroups 保留兼容。
 */
public record GroupReconcileVO(boolean balanced,
                               Totals totals,
                               List<GroupReconcileRowVO> abnormalGroups,
                               List<GroupReconcileRowVO> groups) {

    /** 顶卡合计（跟随本响应口径：全平台/单组）。 */
    public record Totals(BigDecimal netAllocated,
                         BigDecimal consumed,
                         BigDecimal refunded,
                         BigDecimal balance,
                         BigDecimal diff,
                         BigDecimal crossDiff) {
    }
}
