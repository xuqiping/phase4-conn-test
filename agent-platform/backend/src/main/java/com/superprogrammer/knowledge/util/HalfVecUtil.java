package com.superprogrammer.knowledge.util;

import java.util.Locale;

/**
 * pgvector halfvec 文本序列化：float[] → '[v0,v1,...]'。
 * 当前向量表维度为 2048；具体调用模型由管理员配置决定。
 * 用 Locale.US 保证小数点为 '.'，避免某些 JVM 默认 ',' 导致 PG 解析失败。
 */
public final class HalfVecUtil {

    public static final int DIM = 2048;

    private HalfVecUtil() {
    }

    /** 2048 维 float[] → halfvec 文本字面量。精度 6 位小数（halfvec 存 f32，足够）。 */
    public static String toHalfVec(float[] vector) {
        if (vector == null) {
            throw new IllegalArgumentException("embedding 向量为 null");
        }
        StringBuilder sb = new StringBuilder(vector.length * 9 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.US, "%.6f", vector[i]));
        }
        sb.append(']');
        return sb.toString();
    }
}
