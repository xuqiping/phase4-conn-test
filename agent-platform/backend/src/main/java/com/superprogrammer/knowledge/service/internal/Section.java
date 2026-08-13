package com.superprogrammer.knowledge.service.internal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 切分后的章节：L0 摘要的对象 + L2 原文来源。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Section {

    private String sectionId;

    private String parentSectionId;

    private String nodeType;

    private String title;

    private List<String> titlePath;

    private int ordinal;

    private String content;

    private int tokenCount;

    private SectionLocator locator;
}
