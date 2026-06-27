package com.superprogrammer.knowledge.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JiebaTokenizer 单测（Phase2 V35 词法兜底）。
 * 验中文分词：换说法 query 能分出关键实词，喂 BM25 'simple' tsvector 可命中同义表达。
 */
class JiebaTokenizerTest {

    @Test
    void tokenize_chineseSentence_splitsIntoSpaceJoinedTokens() {
        String s = JiebaTokenizer.tokenize("如何安装部署我的系统");
        assertNotNull(s);
        assertTrue(s.contains("安装"), "应分出「安装」：" + s);
        assertTrue(s.contains("部署"), "应分出「部署」：" + s);
        assertTrue(s.contains("系统"), "应分出「系统」：" + s);
    }

    @Test
    void tokens_returnsListOfNonBlankWords() {
        List<String> ts = JiebaTokenizer.tokens("安装部署系统");
        assertTrue(ts.contains("安装"));
        assertTrue(ts.contains("部署"));
        assertTrue(ts.contains("系统"));
    }

    @Test
    void tokenize_nullOrBlank_returnsEmpty() {
        assertEquals("", JiebaTokenizer.tokenize(null));
        assertEquals("", JiebaTokenizer.tokenize("   "));
        assertTrue(JiebaTokenizer.tokens(null).isEmpty());
    }
}
