package com.superprogrammer.media.service;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.media.config.ImageModelCapability;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * C3（6x/Q5）：图片「比例+档位」→ 宽x高 推导器。
 *
 * <p>平台层（Seedream 等）只收 {@code size}（档位枚举或显式宽x高），不直接收比例；
 * 前端选比例时由本类按<b>档位像素预算</b>推导等面积（±舍入）的 WxH：
 * {@code P = 档位边长²；W = round(sqrt(P×r))；H = round(W/r)}。
 *
 * <p>校验：比例须在模型 {@code ratios} 白名单（默认 7 个）；总像素须 ∈ [minTotalPixels,
 * maxTotalPixels]（默认 368.64 万~1677.7216 万，即 1.5K²~4K²）；宽高比须 ∈ [1/16,16]。
 * 1K/1.5K 档位像素预算低于下限 → 明确报错（该档位不支持比例模式，请用自定义宽x高）。
 */
public final class ImageSizeDeriver {

    /** 档位像素预算：边长 → 边长²（2K=2048² …；1K/1.5K 预算低于默认下限，配比例必拒）。 */
    private static final Map<String, Long> TIER_BUDGET = Map.of(
            "1K", 1024L * 1024,
            "1.5K", 1536L * 1536,
            "2K", 2048L * 2048,
            "3K", 3072L * 3072,
            "4K", 4096L * 4096);

    /** 比例白名单缺省（Q5：不拆自定义比例，7 个预设够用）。 */
    public static final List<String> DEFAULT_RATIOS =
            List.of("1:1", "4:3", "3:4", "16:9", "9:16", "3:2", "2:3");

    public static final long DEFAULT_MIN_PIXELS = 3_686_400L;      // ≈1.92K²（1920×1920）
    public static final long DEFAULT_MAX_PIXELS = 16_777_216L;     // 4096²（4K 上限）

    private static final BigDecimal MAX_ASPECT = BigDecimal.valueOf(16);

    private ImageSizeDeriver() {
    }

    /**
     * 推导宽x高。
     *
     * @param ratio    比例（如 "16:9"，须在白名单内）
     * @param tierSize 档位 size（"2K"/"3K"/…；null/空/非档位值 → 默认 "2K"）
     * @param cap      模型能力（ratios/min/max 为空用默认；可空）
     * @return "WxH"（如 "2731x1536"）
     * @throws BusinessException 比例不在白名单 / 档位未知 / 像素或宽高比越界（BAD_REQUEST，含指引）
     */
    public static String derive(String ratio, String tierSize, ImageModelCapability cap) {
        List<String> whitelist = cap != null && cap.getRatios() != null && !cap.getRatios().isEmpty()
                ? cap.getRatios() : DEFAULT_RATIOS;
        String r = ratio == null ? "" : ratio.trim();
        if (!whitelist.contains(r)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "比例非法: " + ratio + "（可选 " + whitelist + "）");
        }
        String tier = tierSize == null || tierSize.isBlank() ? "2K" : tierSize.trim();
        Long budget = TIER_BUDGET.get(tier);
        if (budget == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "档位非法: " + tierSize + "（比例模式档位可选 " + TIER_BUDGET.keySet() + "，或改用自定义宽x高）");
        }
        long minPixels = cap != null && cap.getMinTotalPixels() != null ? cap.getMinTotalPixels() : DEFAULT_MIN_PIXELS;
        long maxPixels = cap != null && cap.getMaxTotalPixels() != null ? cap.getMaxTotalPixels() : DEFAULT_MAX_PIXELS;

        // rVal = w/h（"16:9" → 16/9）
        String[] parts = r.split(":");
        BigDecimal rVal = new BigDecimal(parts[0]).divide(new BigDecimal(parts[1]), 10, RoundingMode.HALF_UP);

        long w = BigDecimal.valueOf(budget).multiply(rVal).sqrt(java.math.MathContext.DECIMAL64)
                .setScale(0, RoundingMode.HALF_UP).longValue();
        long h = BigDecimal.valueOf(w).divide(rVal, 0, RoundingMode.HALF_UP).longValue();
        // 舍入可能把总像素顶破上限（如 4K+16:9 → 5461x3072 超 4096²）→ 高度回落一步（面积近似守恒）
        while (w * h > maxPixels && h > 1) {
            h--;
        }

        if (w * h < minPixels) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "该清晰度档位不支持比例模式：推导 " + w + "x" + h + " 总像素 " + (w * h)
                            + " 低于下限 " + minPixels + "（请改用 2K 及以上档位，或用自定义宽x高）");
        }
        if (w * h > maxPixels) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "该清晰度档位不支持比例模式：推导 " + w + "x" + h + " 总像素 " + (w * h)
                            + " 超过上限 " + maxPixels + "（请降低档位或改用自定义宽x高）");
        }
        BigDecimal aspect = BigDecimal.valueOf(w).divide(BigDecimal.valueOf(h), 10, RoundingMode.HALF_UP);
        if (aspect.compareTo(MAX_ASPECT) > 0 || aspect.compareTo(BigDecimal.ONE.divide(MAX_ASPECT, 10, RoundingMode.HALF_UP)) < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "推导宽高比 " + w + ":" + h + " 超出 [1:16, 16:1] 允许范围");
        }
        return w + "x" + h;
    }
}
