package com.superprogrammer.chat.dto;

import lombok.Data;

/** 记忆行内编辑请求（M1）：改 memory_key / memory_key_zh / memory_value / block_label。
 *  updated_at 由 mapper 自定义 SQL now() 刷新（自定义 @Update 不走 MetaObjectHandler）。
 *  改 memory_key 须过 home-aware 重复检查；改 key/key_zh/block_label 重算 anchor，改 value 重算 value embedding。 */
@Data
public class MemoryEditRequest {

    /** 英文 key（dedup/召回锚点）。 */
    private String memoryKey;
    /** 中文标签（「名称」列 + 关键词/anchor 召回）。 */
    private String memoryKeyZh;
    /** 记忆值。 */
    private String memoryValue;
    /** 信息块标签（embed 聚类）。 */
    private String blockLabel;
}
