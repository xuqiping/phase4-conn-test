package com.superprogrammer.chat.dto;

import lombok.Builder;
import lombok.Data;

/** 记忆处理状态（GET /chat/memories/status，状态条 3s 轮询用）。
 *  processingCount = 该用户当前进行中的抽取任务数（>0 显「记忆记录中…」）；
 *  conflictCount  = PENDING+FLAGGED 冲突数（动态 +1/-1，归零隐）。 */
@Data
@Builder
public class MemoryStatusVO {
    /** 进行中的记忆抽取任务数（per-user in-flight）。 */
    private Integer processingCount;
    /** 待处理冲突数（PENDING+FLAGGED）。 */
    private Integer conflictCount;
}
