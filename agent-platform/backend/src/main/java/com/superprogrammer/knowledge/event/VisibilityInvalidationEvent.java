package com.superprogrammer.knowledge.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 可见集失效事件（v6 §5.2，阶段4-A）。
 * grant/revoke/doc-delete 同事务发布，AFTER_COMMIT 异步删该 KB 所有 `vis:*:*:{kbId}` Redis key。
 * 普通 POJO（不继承 ApplicationEvent），沿用 {@link DocumentUploadedEvent} 模式。
 */
@Getter
@RequiredArgsConstructor
public class VisibilityInvalidationEvent {

    private final Long kbId;
}
