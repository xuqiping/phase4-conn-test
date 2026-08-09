package com.superprogrammer.chat.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import com.superprogrammer.common.typehandler.LongArrayTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 记忆总结提炼层（V47 计划12；V70 二期 P4 共享化）。表走 BaseEntity 软删。
 * project_id 单值 scope 归属（NULL=个人）；**不可挂数组、不可手动挂载、不可分享**。
 * <p>
 * scope_owner（V70，FR-301）：USER=个人总结（只读自己，一期「总结恒只读自己」语义不变）；
 * PROJECT=项目共享总结（项目资产，全员可读、owner/admin 可写，user_id=NULL）。
 * 读取按 (project_id, scope_owner) 分流，老行 scope_owner=USER 不受影响。
 * <p>
 * status：CLEAN 干净 / PENDING_CONFLICT 时序互斥挂起 / STALE 源 turn/条目被删待重生。
 * source_turn_ids + source_entry_ids（V70，FR-305）双 flat provenance（@> [T]/[E] 查波及 summary）；
 * source_summary_id 链式（防膨胀再压缩）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "memory_summaries", autoResultMap = true)
public class MemorySummary extends BaseEntity {

    private Long userId;             // 作者；scope_owner=PROJECT 时 NULL（V70）
    private Long projectId;          // 单值，NULL=个人
    private Long tagId;
    private String l1Summary;
    private String l2Detail;
    private Long sourceSummaryId;    // 链式溯源（防膨胀再压缩指上游）

    /** flat provenance：来自哪些 turn。BIGINT[]。 */
    @TableField(typeHandler = LongArrayTypeHandler.class)
    private List<Long> sourceTurnIds;

    /** flat provenance：来自哪些项目条目（V70，FR-305）。BIGINT[]。 */
    @TableField(typeHandler = LongArrayTypeHandler.class)
    private List<Long> sourceEntryIds;

    private String scopeOwner;       // V70：USER（默认，老行）/ PROJECT（项目共享总结）

    private String status;           // CLEAN / PENDING_CONFLICT / STALE
    private OffsetDateTime summarizedAt;
}
