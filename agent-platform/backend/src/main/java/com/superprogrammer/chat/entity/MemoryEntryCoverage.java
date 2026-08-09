package com.superprogrammer.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 条目级记忆覆盖表（V70 记忆二期 P4，FR-305）。无 deleted/version——随 entry/summary 级联清
 * （承 V47 memory_summary_coverage 范式：覆盖表不软删）。
 * <p>
 * allCovered 判定依据（条目侧）：某条目在某 (tag, 总结scope项目, 主体) 下已被总结吃进。
 * user_id NULL = 项目共享总结覆盖行；非空 = 成员个人压缩通道（FR-302）各自幂等覆盖行。
 * UNIQUE(entry_id, tag_id, project_id, user_id) NULLS NOT DISTINCT——共享行（user_id=NULL）约束生效。
 * project_id 是<b>总结 scope 项目</b>（非条目来源项目）：嵌套取数（FR-303）时条目可来自 ACTIVE child。
 */
@Data
@TableName("memory_entry_coverage")
public class MemoryEntryCoverage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long entryId;
    private Long summaryId;
    private Long projectId;          // 总结 scope 项目
    private Long tagId;
    private Long userId;             // NULL=项目共享总结覆盖；非空=成员个人压缩
    private OffsetDateTime createdAt;
}
