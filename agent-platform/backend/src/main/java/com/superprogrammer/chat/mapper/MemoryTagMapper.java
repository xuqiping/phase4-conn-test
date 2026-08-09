package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.dto.RecallTagMeta;
import com.superprogrammer.chat.entity.MemoryTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
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

    /** V77：清 needs_review（owner 改名/补别名/接受为新大类后调用）。 */
    int clearNeedsReview(@Param("id") Long id);

    /** V77：用户已批准（needs_review=false）的存量 topic，拼有效大类词表（base vocab ∪ 此）。 */
    List<String> findDistinctApprovedTopics(@Param("userId") Long userId);

    // ============================ 计划12 · D-2 召回聚合 ② ============================

    /** 个人 scope 召回标签聚合：本人 turns 的 tag_ids ∪ 本人个人总结（project_id IS NULL）的 tag_id
     *  → join memory_tags 去重 by tag_id，按 usage_count 倒序。{@code gen_done=false} 的 raw 不参与（向量 1）。
     *  二期 P1（V67）：turns 纯个人域，born_personal 限定随列 DROP 移除。
     *  <p>timeWindow：{@code relativeDays} 优先（近 N 天），否则 {@code twStart/twEnd} 绝对时段；全 null = 不限。 */
    List<RecallTagMeta> findPersonalRecallTags(@Param("userId") Long userId,
                                               @Param("direction") String direction,
                                               @Param("twStart") OffsetDateTime twStart,
                                               @Param("twEnd") OffsetDateTime twEnd,
                                               @Param("relativeDays") Integer relativeDays);

    // ============================ 计划12 · D-3 LLM 选标签 ③ RRF 粗筛 ============================

    /** anchor halfvec 距离排序（路 A）：限定 tagIds 集内防 scope 外标签混入（向量 3），按 anchor_embedding &lt;=&gt; queryVec 升序。
     *  queryVec 为 halfvec 文本 '[..]'。返 id 有序列表（rank 1 = 最近）。 */
    List<Long> rankByAnchorHalfvec(@Param("tagIds") List<Long> tagIds,
                                   @Param("queryVec") String queryVec,
                                   @Param("limit") int limit);

    /** anchor BM25 tsv 排序（路 B）：限定 tagIds 集内，按 ts_rank(anchor_tokens_tsv, to_tsquery) 降序。
     *  orQuery = {@code TsQueryUtil.toOrQuery(jieba 空格串)}（OR 串，复活多 token 命中）。返 id 有序列表。 */
    List<Long> rankByAnchorTsv(@Param("tagIds") List<Long> tagIds,
                               @Param("orQuery") String orQuery,
                               @Param("limit") int limit);
}
