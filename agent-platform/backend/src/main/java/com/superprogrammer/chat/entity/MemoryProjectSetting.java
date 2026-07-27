package com.superprogrammer.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 项目级 gen 开关（V47 计划12，owner 维度）。无 deleted——项目删 CASCADE 清。
 * L0/L1/L2 同开同关；与会员覆写 AND 方可生成。默认开。
 */
@Data
@TableName("memory_project_settings")
public class MemoryProjectSetting {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;
    private Boolean genEnabled;      // 项目级 gen 开关，默认 true
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
