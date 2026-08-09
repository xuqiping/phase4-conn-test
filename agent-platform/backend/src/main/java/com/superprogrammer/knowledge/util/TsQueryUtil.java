package com.superprogrammer.knowledge.util;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PG {@code to_tsquery} OR 串构造（记忆/RAG BM25 词法兜底复活）。
 * <p>
 * <b>坑</b>：{@code plainto_tsquery('simple', 'a b c')} 把空格分词当 <b>AND</b>，多 token 永不命中
 * （记忆路由日志每行 bm25Hit=0 的根因）。改用 {@code to_tsquery('simple', 'a | b | c')} 走 OR：
 * 任一 token 命中即召回，再由 ts_rank 排序，向量路失效时词法兜底才真正生效。
 * <p>
 * 入参 = jieba 分词后的空格串（{@link JiebaTokenizer#tokenize}）。转义 to_tsquery 特殊字符
 * （{@code ! : & | ( )}）防注入/语法崩，丢空 token，单 token 直接返回（无需 OR）。
 */
public final class TsQueryUtil {

    private TsQueryUtil() {
    }

    /** 把 jieba 空格串转成 to_tsquery OR 串（已转义）。空输入 → 空串（调用方应跳过查询）。 */
    public static String toOrQuery(String spaceJoinedTokens) {
        if (spaceJoinedTokens == null || spaceJoinedTokens.isBlank()) {
            return "";
        }
        List<String> escaped = Arrays.stream(spaceJoinedTokens.split("\\s+"))
                .filter(t -> t != null && !t.isEmpty())
                .map(TsQueryUtil::escapeLexeme)
                .filter(t -> !t.isEmpty())
                .toList();
        if (escaped.isEmpty()) {
            return "";
        }
        if (escaped.size() == 1) {
            return escaped.get(0);
        }
        return escaped.stream().collect(Collectors.joining(" | "));
    }

    /** 转义 to_tsquery 词法特殊字符（保留 CJK/字母数字）。 */
    private static String escapeLexeme(String token) {
        StringBuilder sb = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '!' || c == ':' || c == '&' || c == '|' || c == '(' || c == ')' || c == '\'' || c == '\\') {
                continue;  // 丢特殊字符（jieba 词基本不含，防御）
            }
            if (Character.isWhitespace(c)) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
