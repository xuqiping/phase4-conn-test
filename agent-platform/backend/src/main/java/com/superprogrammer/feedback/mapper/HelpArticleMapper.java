package com.superprogrammer.feedback.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.feedback.entity.HelpArticleEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 说明台文章 Mapper。拍板硬删（释放 slug 占坑）：{@link #physicalDeleteById} 真 DELETE，
 * 不走 @TableLogic 软删（实体 deleted 列仅为 BaseEntity 统一占位）。
 */
@Mapper
public interface HelpArticleMapper extends BaseMapper<HelpArticleEntity> {

    @Select("SELECT * FROM help_articles WHERE slug = #{slug} AND deleted = 0")
    HelpArticleEntity selectBySlug(@Param("slug") String slug);

    /** 发布/下架（无条件抢态需求——重复发布幂等返回 1 也无妨，内容以最后写入为准）。 */
    @Update("UPDATE help_articles SET published = #{published}, "
            + "published_at = CASE WHEN #{published} THEN NOW() ELSE published_at END "
            + "WHERE id = #{id} AND deleted = 0")
    int setPublished(@Param("id") Long id, @Param("published") boolean published);

    /** 硬删（拍板：释放 slug；审计留痕由 service @AuditLog 负责）。 */
    @Delete("DELETE FROM help_articles WHERE id = #{id}")
    int physicalDeleteById(@Param("id") Long id);
}
