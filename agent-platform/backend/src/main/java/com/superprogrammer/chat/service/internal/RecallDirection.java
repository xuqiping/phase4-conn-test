package com.superprogrammer.chat.service.internal;

/**
 * 计划12 · D · 召回方向（总体设计 §3.3 参数表）。
 * <ul>
 *   <li>{@link #INPUT} —— 仅召回用户提问侧流水账。</li>
 *   <li>{@link #OUTPUT} —— 仅召回助手回答侧流水账。</li>
 *   <li>{@link #BOTH} —— 双侧并集（默认）。</li>
 * </ul>
 */
public enum RecallDirection {
    INPUT,
    OUTPUT,
    BOTH;

    /** 字符串兜底解析：null/空/非法 → {@link #BOTH}（设计默认值）。 */
    public static RecallDirection fromString(String s) {
        if (s == null) {
            return BOTH;
        }
        return switch (s.trim().toUpperCase()) {
            case "INPUT" -> INPUT;
            case "OUTPUT" -> OUTPUT;
            default -> BOTH;
        };
    }
}
