package com.superprogrammer.llm.dto;

/**
 * C5 多模态 embed 内容段（WP5 Step1）：DashScope multimodal-embedding 的 contents 元素——
 * text/image 二选一或同段共存；多段时 provider 侧 enable_fusion 融合为单向量。
 * image 取值=公网 URL 或 Base64 串（协议原样透传，由调用方决定形态）。
 */
public record EmbedContentPart(String text, String image) {

    public static EmbedContentPart ofText(String text) {
        return new EmbedContentPart(text, null);
    }

    /** 图片段：URL 或 Base64（不带 data: 前缀，DashScope multimodal-embedding 协议口径）。 */
    public static EmbedContentPart ofImage(String imageRef) {
        return new EmbedContentPart(null, imageRef);
    }
}
