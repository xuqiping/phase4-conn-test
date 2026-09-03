package com.superprogrammer.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * C6 增量同步账本（WP6 Step1，V175）：源端条目 ↔ 本地文档映射。
 * (connector_id, external_id) 唯一；etag 指纹驱动增量；手工删除标记防复活。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_connector_docs")
public class KnowledgeConnectorDoc extends BaseEntity {

    private Long tenantId;

    private Long connectorId;

    /** URL / S3 key / WebDAV path——统一存原始未编码路径（下载时再编码，中文路径坑）。 */
    private String externalId;

    /** 内容指纹（ETag/Last-Modified/hash）；null=尚未拉取。 */
    private String etag;

    /** 映射到 knowledge_documents。 */
    private Long docId;

    /** 手工删除同步文档→true：下轮跳过不复活（联动点表）。 */
    private Boolean manualDeleted;

    private OffsetDateTime syncedAt;
}
