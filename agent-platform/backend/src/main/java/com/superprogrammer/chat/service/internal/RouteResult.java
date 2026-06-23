package com.superprogrammer.chat.service.internal;

/**
 * 冲突答复路由判定结果（Jackson 解析，取代旧 string-contains）。
 *
 * @param isAnswer 用户这条消息是否在回答冲突提问
 * @param keep     A=保留旧 / B=保留新 / BOTH=都留 / NONE=都删 / UNCLEAR=不清；isAnswer=false 时恒 UNCLEAR
 */
public record RouteResult(boolean isAnswer, String keep) {
    /** 映射为 resolve 决策常量；非 KEEP_* 时返 UNCLEAR。 */
    public String toDecision() {
        return switch (keep) {
            case "A" -> "KEEP_OLD";
            case "B" -> "KEEP_NEW";
            case "BOTH" -> "KEEP_BOTH";
            case "NONE" -> "DISCARD";
            default -> "UNCLEAR";
        };
    }
}
