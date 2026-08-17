package com.superprogrammer.asset.service;

import java.util.List;
import java.util.Map;

/**
 * 资产评分等级（2x#7）——分数→等级映射的唯一真相源。
 *
 * <p>等级不入库，展示/筛选现场派生：≥95 A+ / 90-94 A / 80-89 B / 70-79 C / &lt;70 D；
 * null=未评。成员均分须先四舍五入到整分再映射（与展示口径一致，防 94.5 类小数档位抖动）。
 * 前端 {@code constants/assetGrade.ts} 硬编码同一常量，并以对齐单测防双份漂移。
 */
public final class AssetGrade {

    public static final String GRADE_A_PLUS = "A+";
    public static final String GRADE_A = "A";
    public static final String GRADE_B = "B";
    public static final String GRADE_C = "C";
    public static final String GRADE_D = "D";

    /** 等级→分数区间 [min,max]（含边界），等级快捷筛选换算 scoreMin/scoreMax 用。 */
    private static final Map<String, int[]> RANGES = Map.of(
            GRADE_A_PLUS, new int[]{95, 100},
            GRADE_A, new int[]{90, 94},
            GRADE_B, new int[]{80, 89},
            GRADE_C, new int[]{70, 79},
            GRADE_D, new int[]{0, 69});

    /** 全部等级（筛选下拉顺序：高→低）。 */
    public static final List<String> ALL = List.of(GRADE_A_PLUS, GRADE_A, GRADE_B, GRADE_C, GRADE_D);

    /**
     * 分数→等级；null（未评）→ null。
     * 分数越界（&lt;0/&gt;100）由评分写入校验拦截，此处按 D/A+ 兜底映射。
     */
    public static String fromScore(Integer score) {
        if (score == null) {
            return null;
        }
        int s = score;
        if (s >= 95) {
            return GRADE_A_PLUS;
        }
        if (s >= 90) {
            return GRADE_A;
        }
        if (s >= 80) {
            return GRADE_B;
        }
        if (s >= 70) {
            return GRADE_C;
        }
        return GRADE_D;
    }

    /** 等级→[min,max] 区间副本；未知/空等级抛 IllegalArgumentException（Map.of 不收 null key，先判空）。 */
    public static int[] rangeOf(String grade) {
        if (grade == null) {
            throw new IllegalArgumentException("未知资产等级: null");
        }
        int[] range = RANGES.get(grade);
        if (range == null) {
            throw new IllegalArgumentException("未知资产等级: " + grade);
        }
        return range.clone();
    }

    private AssetGrade() {
    }
}
