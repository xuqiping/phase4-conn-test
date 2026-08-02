package com.superprogrammer.knowledge.util;

/**
 * Token 估算（chars/4 启发式）。
 * doubao tokenizer 非 jtokkit，精确分词无可用库；v6 各 token 预算均为软上限，启发式足够。
 * 用于 section 200-800 tok 切分、L2 ≤1024 tok 上限、L0 摘要计数。
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }
}
