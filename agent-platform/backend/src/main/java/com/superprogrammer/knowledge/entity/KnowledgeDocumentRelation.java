package com.superprogrammer.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * C1 文档关联边（人工声明）。表无 deleted/version/updated 审计列（仅 created_by + created_at），
 * 不继承 BaseEntity，撤销 = 硬删（对齐 knowledge_permissions 惯例）。
 */
@Data
@TableName("knowledge_document_relations")
public class KnowledgeDocumentRelation {

    /** 命中主动方时 B 强制进上下文（硬绑定/打包召回） */
    public static final String TYPE_MUST_CITE = "MUST_CITE";
    /** 命中主动方时 B 作为候选进 rerank，过阈才进 */
    public static final String TYPE_MAY_CITE = "MAY_CITE";
    /** B 被召回时主动方必须跟着出现（反向硬绑定，读作 MUST_CITE(B→A)） */
    public static final String TYPE_MUST_BE_CITED = "MUST_BE_CITED";
    /** B 被召回时主动方仅作「相关文档」推荐，不进主上下文 */
    public static final String TYPE_MAY_BE_CITED = "MAY_BE_CITED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    /** 关联仅限同库（首版边界：跨库边涉及跨库权限矩阵不做） */
    private Long kbId;

    /** 主动方文档 */
    private Long docId;

    /** 被动方文档 */
    private Long relatedDocId;

    /** MUST_CITE / MAY_CITE / MUST_BE_CITED / MAY_BE_CITED */
    private String relationType;

    /** 可选备注（为什么关联） */
    private String note;

    private Long createdBy;

    private OffsetDateTime createdAt;

    /**
     * 语义等价反向类型：MUST_CITE ↔ MUST_BE_CITED、MAY_CITE ↔ MAY_BE_CITED。
     * 建边查重用——已存在 (B,A,MUST_CITE) 时再建 (A,B,MUST_BE_CITED) 属语义重复，须拒绝
     * （唯一约束拦不住方向颠倒的重复，服务层必须拦）。
     */
    public static String equivalentReverseType(String type) {
        return switch (type) {
            case TYPE_MUST_CITE -> TYPE_MUST_BE_CITED;
            case TYPE_MUST_BE_CITED -> TYPE_MUST_CITE;
            case TYPE_MAY_CITE -> TYPE_MAY_BE_CITED;
            case TYPE_MAY_BE_CITED -> TYPE_MAY_CITE;
            default -> throw new IllegalArgumentException("未知关系类型: " + type);
        };
    }
}
