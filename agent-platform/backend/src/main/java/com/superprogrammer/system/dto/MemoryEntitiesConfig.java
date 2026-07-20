package com.superprogrammer.system.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 记忆抽取 entities 词袋计数配置（M3，原待办 #19）。
 * <p>存于 system_settings 单条 JSON key {@code rag.memory.entities-config}，控制 {@code MemoryConflictJudge}
 * 抽取 prompt 的数量指引 + Java 兜底截断阈值。默认值 = V38 硬上限，零行为变更。
 * <p>字段语义（对齐 prompt 四类词）：
 * <ul>
 *   <li>{@code totalMax}：entities 总数硬上限（Java readElements 截断 + prompt "共 ≤ N 个"）。</li>
 *   <li>{@code variantMin/Max}：同义变体词数量区间（角色/称谓近义说法，prompt "变体 min~max 个"）。</li>
 *   <li>{@code properNounMin/Max}：value 专有名词数量区间（人名/地名/品牌原文字面词）。</li>
 *   <li>{@code hypernymMin/Max}：所属类别上位词数量区间（决定泛问召回，如 query「带家人出去玩」召回配偶/孩子）。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryEntitiesConfig {
    /** 默认值（= V38 硬上限）。 */
    public static final int DEFAULT_TOTAL_MAX = 20;
    public static final int DEFAULT_VARIANT_MIN = 1;
    public static final int DEFAULT_VARIANT_MAX = 3;
    public static final int DEFAULT_PROPER_NOUN_MIN = 1;
    public static final int DEFAULT_PROPER_NOUN_MAX = 5;
    public static final int DEFAULT_HYPERNYM_MIN = 5;
    public static final int DEFAULT_HYPERNYM_MAX = 10;

    public static MemoryEntitiesConfig defaults() {
        return MemoryEntitiesConfig.builder()
                .totalMax(DEFAULT_TOTAL_MAX)
                .variantMin(DEFAULT_VARIANT_MIN)
                .variantMax(DEFAULT_VARIANT_MAX)
                .properNounMin(DEFAULT_PROPER_NOUN_MIN)
                .properNounMax(DEFAULT_PROPER_NOUN_MAX)
                .hypernymMin(DEFAULT_HYPERNYM_MIN)
                .hypernymMax(DEFAULT_HYPERNYM_MAX)
                .build();
    }

    private Integer totalMax;
    private Integer variantMin;
    private Integer variantMax;
    private Integer properNounMin;
    private Integer properNounMax;
    private Integer hypernymMin;
    private Integer hypernymMax;

    /** 兜底归一化：null/非法 → 默认；min>max → 互换；保证 totalMax ≥ 各 max。 */
    public MemoryEntitiesConfig normalized() {
        int t = clamp(totalMax, 1, 50, DEFAULT_TOTAL_MAX);
        int vMin = clamp(variantMin, 0, 20, DEFAULT_VARIANT_MIN);
        int vMax = clamp(variantMax, 0, 20, DEFAULT_VARIANT_MAX);
        int pMin = clamp(properNounMin, 0, 20, DEFAULT_PROPER_NOUN_MIN);
        int pMax = clamp(properNounMax, 0, 20, DEFAULT_PROPER_NOUN_MAX);
        int hMin = clamp(hypernymMin, 0, 20, DEFAULT_HYPERNYM_MIN);
        int hMax = clamp(hypernymMax, 0, 20, DEFAULT_HYPERNYM_MAX);
        if (vMin > vMax) { int tmp = vMin; vMin = vMax; vMax = tmp; }
        if (pMin > pMax) { int tmp = pMin; pMin = pMax; pMax = tmp; }
        if (hMin > hMax) { int tmp = hMin; hMin = hMax; hMax = tmp; }
        // 各类 max 合计不超过 totalMax，否则 totalMax 上调到合计（保 prompt 指引自洽）。
        int sumMax = vMax + pMax + hMax + 1; // +1 = key_zh 中文标签必含
        if (sumMax > t) t = Math.min(sumMax, 50);
        return MemoryEntitiesConfig.builder()
                .totalMax(t).variantMin(vMin).variantMax(vMax)
                .properNounMin(pMin).properNounMax(pMax)
                .hypernymMin(hMin).hypernymMax(hMax).build();
    }

    private static int clamp(Integer v, int lo, int hi, int def) {
        if (v == null) return def;
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
