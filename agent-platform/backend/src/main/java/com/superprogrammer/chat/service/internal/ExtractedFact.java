package com.superprogrammer.chat.service.internal;

import java.math.BigDecimal;
import java.util.List;

/** 抽取单条事实（含 block 候选名 + 中文标签 + 实体召回词袋）。
 *  keyZh：memory_key 的中文主标签（如"女儿"），落 user_memories.memory_key_zh，
 *         供前端「名称」列显示 + VECTOR_KEYWORD 关键词召回锚点（治英文 key 丢失中文桥接词）。
 *  entities：召回词袋 = 中文标签 + 同义变体（女儿→孩子/小孩）+ value 专名，落 entities JSONB，
 *            供 VECTOR_KEYWORD 关键词召回。null/empty = 无词袋。 */
public record ExtractedFact(String category, String key, String keyZh, String value,
                            BigDecimal confidence, String block,
                            List<String> entities) {

    /** 旧调用点兼容（无 keyZh / 无实体）。 */
    public ExtractedFact(String category, String key, String value,
                         BigDecimal confidence, String block) {
        this(category, key, null, value, confidence, block, List.of());
    }
}
