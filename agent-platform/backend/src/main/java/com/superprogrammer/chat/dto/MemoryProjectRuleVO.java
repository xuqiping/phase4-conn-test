package com.superprogrammer.chat.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 收录规则视图（记忆二期 P1 · FR-001）。
 * <p>
 * 可见性分层：ruleText/positiveExamples/enabled 项目成员可见；
 * negativeExamples 仅 owner/admin 可见（service 按角色裁剪，成员恒 null）。
 * anchorReady=false 表示 embed 失败规则未生效（enabled 强制 false）。
 */
@Data
@Builder
public class MemoryProjectRuleVO {

    private Long id;
    private Long projectId;
    private String ruleText;
    private List<String> positiveExamples;
    private List<String> negativeExamples;   // 仅 owner/admin；成员恒 null
    private List<String> filenamePatterns;   // 文件名硬规则（成员可见）
    private Boolean enabled;
    private Boolean anchorReady;             // anchor 是否就绪（false=embed 失败降级）
    private OffsetDateTime updatedAt;
}
