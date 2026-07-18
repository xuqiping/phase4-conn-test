package com.superprogrammer.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * M2 时间线记忆:per-user per-key 时序事实标记。
 * <p>
 * 首次某 memory_key 走到 pending 冲突时,LLM 问用户「这类记忆按时间线记?(住址=是,孩子数量=否)」,
 * 答案落本表;后续 KEEP_BOTH merge 按本标走:temporal=true → value 各段带 ISO 日期前缀按序拼 {@code ;},
 * temporal=false → 维持中文逗号 join(现状)。直到用户在 panel 显式改标({@code USER_OVERRIDE})。
 * <p>
 * 与 {@code user_memories} 正交:key 级元数据,不参与日常召回,仅 merge/resolve 读。
 * 同 user_memories 域约定:不走 BaseEntity 软删,无向量列。
 */
@Data
@TableName("memory_key_meta")
public class MemoryKeyMeta {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String memoryKey;
    /** true=时序事实(value 带日期段),false=非时序(中文逗号 join 现状)。 */
    private Boolean isTemporal;
    /** LLM_ASK=首次询问用户答;USER_OVERRIDE=panel 手改。 */
    private String source;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
