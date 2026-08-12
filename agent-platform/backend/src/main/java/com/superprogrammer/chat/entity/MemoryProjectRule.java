package com.superprogrammer.chat.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import com.superprogrammer.common.typehandler.StringArrayTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 项目收录规则（V65 记忆二期 P1）。表走 BaseEntity 软删。
 * <p>
 * 创建者/admin 声明「聊天中涉及什么内容，记忆自动进本项目」。v1 每项目一条活规则
 * （DB 部分唯一索引 {@code uk_memory_project_rules_project WHERE deleted=0} 兜底）。
 * <p>
 * 可见性：rule_text/positive_examples 项目成员可见（透明化）；negative_examples 仅 owner/admin（防规避）。
 * anchor_embedding(halfvec) + anchor_tokens_tsv(生成列) 不映射为字段——走自定义 SQL（同 MemoryTag 模式）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "memory_project_rules", autoResultMap = true)
public class MemoryProjectRule extends BaseEntity {

    private Long projectId;
    private String ruleText;         // 自然语言规则
    private Boolean enabled;         // 收录开关；anchor 计算失败时强制 false

    /** 正例（≤5，成员可见）。TEXT[] 走 StringArrayTypeHandler。 */
    @TableField(typeHandler = StringArrayTypeHandler.class)
    private List<String> positiveExamples;

    /** 负例（≤5 滚动，仅 owner/admin 可见）。TEXT[] 走 StringArrayTypeHandler。 */
    @TableField(typeHandler = StringArrayTypeHandler.class)
    private List<String> negativeExamples;

    /** 文件名硬规则（v1 子串包含，大小写不敏感，≤10 条）。FILE 路由短路：文件名命中 → 直接 ACTIVE 一定进。
     *  TEXT[] 走 StringArrayTypeHandler。 */
    @TableField(typeHandler = StringArrayTypeHandler.class)
    private List<String> filenamePatterns;

    /** BM25 词法串（jieba 分词空格拼接），写入后由 DB 生成 anchor_tokens_tsv。 */
    private String anchorTokens;
}
