package com.superprogrammer.knowledge.util;

import com.huaban.analysis.jieba.JiebaSegmenter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * jieba 中文分词封装（RAG BM25 词法兜底，Phase2）。
 * 单例 segmenter（线程安全）。写时：node.content → 空格拼串存 content_tokens（PG 'simple' 按空格切即分好的词）。
 * 读时：query → 空格拼串喂 plainto_tsquery('simple', ...)。
 */
public final class JiebaTokenizer {

    private static final JiebaSegmenter SEG = new JiebaSegmenter();

    private JiebaTokenizer() {
    }

    /** 分词 → 空格拼串（滤空 token）。 */
    public static String tokenize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return SEG.sentenceProcess(text).stream()
                .filter(w -> w != null && !w.isBlank())
                .collect(Collectors.joining(" "));
    }

    /** 取 token 列表（测试/调试用）。 */
    public static List<String> tokens(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return SEG.sentenceProcess(text).stream()
                .filter(w -> w != null && !w.isBlank())
                .toList();
    }
}
