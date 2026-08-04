package com.superprogrammer.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 会员个人 gen 覆写开关（V47 计划12）。无 deleted——项目删 CASCADE 清。
 * 默认开，会员自控；owner 项目级 AND 会员覆写皆开才生成。
 */
@Data
@TableName("memory_project_user_settings")
public class MemoryProjectUserSetting {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;
    private Long userId;
    private Boolean genEnabled;      // 会员个人覆写，默认 true
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
