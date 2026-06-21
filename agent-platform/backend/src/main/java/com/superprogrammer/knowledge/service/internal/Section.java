package com.superprogrammer.knowledge.service.internal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 切分后的章节：L0 摘要的对象 + L2 原文来源。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Section {

    private String title;

    private String content;

    private int tokenCount;
}
