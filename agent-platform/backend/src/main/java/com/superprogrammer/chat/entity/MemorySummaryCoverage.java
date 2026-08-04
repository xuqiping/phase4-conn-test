package com.superprogrammer.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 记忆覆盖表（V47 计划12）。无 deleted/version——随 summary/turn 级联清。
 * allCovered 判定依据：某作者某 turn 某 tag 某 scope 被其总结吃进。
 * 召回恒只认 user_id=召回者自己的行。
 * UNIQUE(turn_id, tag_id, project_id, user_id) NULLS NOT DISTINCT——个人 scope(project_id=NULL) 约束生效。
 */
@Data
@TableName("memory_summary_coverage")
public class MemorySummaryCoverage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long turnId;
    private Long tagId;
    private Long summaryId;
    private Long projectId;          // NULL=个人 scope
    private Long userId;             // 作者（召回按 user_id=self 判覆盖）
    private OffsetDateTime createdAt;
}
