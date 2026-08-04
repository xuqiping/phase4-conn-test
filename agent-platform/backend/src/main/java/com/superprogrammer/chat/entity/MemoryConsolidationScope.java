package com.superprogrammer.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 自动总结 scope 勾选集（V47 计划12）。无 deleted——项目删 CASCADE 清。
 * UNIQUE(user_id, scope_kind, project_id) NULLS NOT DISTINCT。
 * 新用户默认插 (PERSONAL, NULL, auto_enabled=true)——由 V47 trigger 自动建。
 * 定时跑前判该 scope 该周期有无新增未总结 turn，无则空跳过（低频用户不耗 token）。
 */
@Data
@TableName("memory_consolidation_scopes")
public class MemoryConsolidationScope {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String scopeKind;        // PERSONAL / PROJECT
    private Long projectId;          // PERSONAL=NULL
    private Boolean autoEnabled;     // 是否加入自动定时总结
    /** V51 worker 任务锁（认领置 now+LOCK_MINUTES，完成清 NULL）。 */
    private OffsetDateTime lockedUntil;
    /** V51 上次成功总结时刻（周期内 last_run_at>=periodStart 则跳过，幂等防重复压缩）。 */
    private OffsetDateTime lastRunAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
