package com.superprogrammer.feedback.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * 说明台·帮助文章（help_articles，V141）。
 * <p>slug 唯一短链（uk_help_article_slug）；拍板<strong>硬删</strong>（释放 slug 占坑），
 * mapper 提供显式 physicalDelete——实体仍继承 BaseEntity（@TableLogic 占位不影响硬删路径）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("help_articles")
public class HelpArticleEntity extends BaseEntity {

    /** 英文短链名（^[a-z0-9-]+$，DB CHECK 双卡）。 */
    private String slug;

    private String title;

    private String category;

    private Integer sortOrder;

    /** markdown 原文（渲染侧 html:false）。 */
    private String contentMd;

    private Boolean published;

    private OffsetDateTime publishedAt;

    /**
     * 可见性权限码（V149）：NULL=全体登录用户；否则须持有该码或 ROLE_admin。
     * 用户侧目录与 slug 直链同闸过滤（直链按 404 处理，不泄露存在性）。
     */
    private String requiredPermission;
}
