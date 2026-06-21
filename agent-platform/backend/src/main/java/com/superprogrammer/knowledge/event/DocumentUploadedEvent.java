package com.superprogrammer.knowledge.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 文档上传事件（ApplicationEvent 载体）。
 * 在 KnowledgeDocumentService.upload 事务内发布；监听器 AFTER_COMMIT 触发，确保解析只见已提交的 PENDING 行。
 */
@Getter
@RequiredArgsConstructor
public class DocumentUploadedEvent {

    private final Long documentId;

    private final Long operatorId;
}
