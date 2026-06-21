package com.superprogrammer.knowledge.service.internal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** L1 文档元数据，序列化为 knowledge_documents.l1_metadata（背景/目录定位，不参与召回）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class L1Metadata {

    private String summary;

    private List<String> outline;

    private List<String> importantRules;
}
