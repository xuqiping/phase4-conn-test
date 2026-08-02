package com.superprogrammer.knowledge.service.internal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Tika 抽取 + 切分结果。sections 为 L0 召回单元（每 section 一个 L0）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractedDocument {

    private String plainText;

    private List<Section> sections;
}
