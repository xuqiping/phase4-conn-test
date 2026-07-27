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
 * 记忆总结提炼层（V47 计划12）。表走 BaseEntity 软删。
 * project_id 单值 scope 归属（NULL=个人）；**不可挂数组、不可手动挂载、不可分享**。
 * 只读自己（user_id=作者）；他人总结不可见（防污染）。
 * status：CLEAN 干净 / PENDING_CONFLICT 时序互斥挂起 / STALE 源 turn 被删待重生。
 * source_turn_ids flat provenance（@> [T] 查受影响 summary）；source_summary_id 链式（防膨胀再压缩）。
 * summarized_at = 12h 规则时间基准（他人引用方 summary 的 summarized_at）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "memory_summaries", autoResultMap = true)
public class MemorySummary extends BaseEntity {

    private Long userId;
    private Long projectId;          // 单值，NULL=个人
    private Long tagId;
    private String l1Summary;
    private String l2Detail;
    private Long sourceSummaryId;    // 链式溯源（防膨胀再压缩指上游）

    /** flat provenance：来自哪些 turn。BIGINT[]。 */
    @TableField(typeHandler = LongArrayTypeHandler.class)
    private List<Long> sourceTurnIds;

    private String status;           // CLEAN / PENDING_CONFLICT / STALE
    private OffsetDateTime summarizedAt;
}
