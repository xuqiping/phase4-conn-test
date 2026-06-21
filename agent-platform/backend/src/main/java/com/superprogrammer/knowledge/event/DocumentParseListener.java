package com.superprogrammer.knowledge.event;

import com.superprogrammer.knowledge.service.DocumentParserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 文档上传 → 解析 监听器。
 * AFTER_COMMIT：只在 upload 事务提交后触发（PG read-committed 下异步线程才读得到 PENDING 行）。
 * @Async：移出请求线程，上传立即返回。
 * 宽 catch：解析失败已在 DocumentParserService.parse 内 markFailed，此处兜底防异常逃逸到线程池。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentParseListener {

    private final DocumentParserService documentParserService;

    @Async("knowledgeTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentUploaded(DocumentUploadedEvent event) {
        try {
            documentParserService.parse(event.getDocumentId(), event.getOperatorId());
        } catch (Exception e) {
            log.error("文档解析未捕获异常 docId={}: {}", event.getDocumentId(), e.getMessage(), e);
        }
    }
}
