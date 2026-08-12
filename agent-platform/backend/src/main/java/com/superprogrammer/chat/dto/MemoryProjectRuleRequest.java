package com.superprogrammer.chat.dto;

import lombok.Data;

import java.util.List;

/**
 * 收录规则保存请求（记忆二期 P1 · FR-001）。
 * rule_text ≤2000 字；正/负例各 ≤5 条、单条 ≤500 字（service 校验）。
 */
@Data
public class MemoryProjectRuleRequest {

    private String ruleText;
    private List<String> positiveExamples;
    private List<String> negativeExamples;
    /** 文件名硬规则（v1 子串包含，大小写不敏感，≤10 条、单条 ≤100 字）。 */
    private List<String> filenamePatterns;
    private Boolean enabled;
}
