package com.superprogrammer.chat.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import com.superprogrammer.common.typehandler.StringArrayTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 记忆标签（V47 计划12）。表走 BaseEntity 软删。
 * 写时归一主体：同 user 同 (subject,topic) 唯一；禁手动归并/拆分（误并不可逆，已生成 summary 的 tag_id 会漂移）。
 * 仅 owner 改 label/补 aliases（tag_id 不变）。
 * 对外只露 label + subject + topic；aliases/anchor_* 不外露（向量 4）。
 * anchor_embedding(halfvec) + anchor_tokens_tsv(生成列) 不映射为字段——走自定义 SQL（同 UserMemory embedding 模式）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "memory_tags", autoResultMap = true)
public class MemoryTag extends BaseEntity {

    private Long userId;
    private String subject;          // L0 主体，默认「我」
    private String topic;            // L0 主题（居住/爱好/工作）
    private String label;            // 对外展示名（同义归一后规范名）
    private Integer usageCount;      // 复用次数，归一命中自增

    /** 同义别名集（归一命中滚进）。TEXT[] 走 StringArrayTypeHandler。 */
    @TableField(typeHandler = StringArrayTypeHandler.class)
    private List<String> aliases;

    /** BM25 词法串（jieba 分词空格拼接），写入后由 DB 生成 anchor_tokens_tsv。 */
    private String anchorTokens;
}
