package com.superprogrammer.knowledge.service;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.knowledge.entity.KnowledgeBase;

/**
 * 14x#3 · 库级保密内容咽喉（spec §5.3）。
 *
 * 保密判定 = kb.confidential=TRUE 且非 owner/admin。受限成员的内容面收紧：
 *   文档列表/单查 → 保留元数据、剔除 fileRef；asset/nodes/retrieve（调试）→ 403；
 *   RAG 问答（/ask、chat kbIds）= 唯一内容出口，不走本 Guard。
 * 与 KnowledgeBaseService.isOwnerOrAdmin 同口径（admin ‖ createdBy），独立成静态类避免循环依赖。
 */
public final class KnowledgeConfidentialGuard {

    private KnowledgeConfidentialGuard() {
    }

    /** 是否受保密限制（不抛异常版，供列表剔除 fileRef 等软收紧分支用）。 */
    public static boolean isRestricted(KnowledgeBase kb, Long userId, boolean admin) {
        if (kb == null || !Boolean.TRUE.equals(kb.getConfidential())) {
            return false;
        }
        return !(admin || (userId != null && userId.equals(kb.getCreatedBy())));
    }

    /** 内容咽喉：受限即抛 403（固定话术，不泄漏库主/授权细节）。 */
    public static void assertCanViewContent(KnowledgeBase kb, Long userId, boolean admin) {
        if (isRestricted(kb, userId, admin)) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_CONFIDENTIAL_DENIED,
                    "保密知识库：内容仅可通过 RAG 问答召回，如需全文请联系库管理员");
        }
    }
}
