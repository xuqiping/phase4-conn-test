package com.superprogrammer.knowledge.service.internal;

import java.util.List;

/**
 * Excel sheet 抽取结果：文档（plainText + sections）+ 非致命告警（截断/降级）。
 * 告警由 DocumentParserService 写入 knowledge_documents.parse_warning（V39，前端黄色徽章）。
 */
public record ExcelExtractResult(ExtractedDocument document, List<String> warnings) {
}
