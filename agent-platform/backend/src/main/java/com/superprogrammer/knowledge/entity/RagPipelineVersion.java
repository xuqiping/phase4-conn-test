package com.superprogrammer.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.typehandler.JsonbStringTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/** RAG Pipeline 的不可变配置快照。 */
@Data
@TableName(value = "rag_pipeline_versions", autoResultMap = true)
public class RagPipelineVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String versionCode;
    private String status;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String configSnapshot;
    private String configHash;
    private Long createdBy;
    private OffsetDateTime createdAt;
}
