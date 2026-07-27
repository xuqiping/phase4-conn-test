package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.entity.MemoryTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 记忆标签 mapper（V47 计划12）。
 * <p>
 * 写时归一四路径的数据出口：
 * <ul>
 *   <li>{@link #findByUserSubjectTopic} 精确 (user,subject,topic) 命中（路径①，UNIQUE 兜底）。</li>
 *   <li>{@link #findByLabelInAliases} label ∈ 某标签 aliases（路径②，同义别名命中）。</li>
 *   <li>{@link #findNearestByAnchor} anchor 半向量近邻（路径③粗筛，D 迭代召回复用）。</li>
 *   <li>{@link #insertWithAnchor} 全 miss 新建（路径④）。</li>
 * </ul>
 * 复用侧：{@link #incrementUsage}（usage_count++）、{@link #appendAlias}（别名滚进去重）、
 * {@link #updateLabelAndAnchor}（owner 改 label，anchor 重生）。
 * <p>
 * halfvec 算子 {@code <=>} 在 XML 转义（V33 教训）；aliases TEXT[] 走 StringArrayTypeHandler。
 */
@Mapper
public interface MemoryTagMapper extends BaseMapper<MemoryTag> {

    /** 精确 (user, subject, topic) 命中——写时归一首查路径（UNIQUE 兜底）。 */
    MemoryTag findByUserSubjectTopic(@Param("userId") Long userId,
                                     @Param("subject") String subject,
                                     @Param("topic") String topic);

    /** label ∈ 某标签 aliases（路径②，同义别名命中）。返最近一条（按 usage_count 倒序）。 */
    MemoryTag findByLabelInAliases(@Param("userId") Long userId,
                                   @Param("label") String label);

    /** anchor 半向量近邻（路径③粗筛主通道，D 迭代召回用；此处复用做归一候选）。
     *  queryVec 为 halfvec 文本 '[..]'。 */
    List<MemoryTag> findNearestByAnchor(@Param("userId") Long userId,
                                        @Param("queryVec") String queryVec,
                                        @Param("limit") int limit);

    /** 路径③归一专用：anchor 距离 ≤ 阈值的候选（distance 语义：0=同，2=反）。
     *  阈值内才送二次 LLM 批判，防误并。queryVec 为 halfvec 文本 '[..]'。 */
    List<MemoryTag> findWithinAnchorThreshold(@Param("userId") Long userId,
                                              @Param("queryVec") String queryVec,
                                              @Param("threshold") double threshold,
                                              @Param("limit") int limit);

    /** 全 miss 新建（路径④）。anchor 两列 + aliases 走自定义 SQL（实体不映射 halfvec/tsv 生成列）。
     *  实体须 @Param("m")——MyBatis 混用实体+@Param 时属性名带前缀。 */
    void insertWithAnchor(@Param("m") MemoryTag m,
                          @Param("anchorHalfvec") String anchorHalfvec,
                          @Param("anchorTokens") String anchorTokens);

    /** 复用时 usage_count++（归一命中自增，L12）。 */
    int incrementUsage(@Param("id") Long id);

    /** 同义别名滚进 aliases（去重：alias 已在集内则不动）。 */
    int appendAlias(@Param("id") Long id, @Param("alias") String alias);

    /** owner 改 label（anchor 随之重生，预期纠错）。anchor 两列 null 时 COALESCE 保留旧值。 */
    int updateLabelAndAnchor(@Param("id") Long id,
                             @Param("label") String label,
                             @Param("anchorHalfvec") String anchorHalfvec,
                             @Param("anchorTokens") String anchorTokens);
}
