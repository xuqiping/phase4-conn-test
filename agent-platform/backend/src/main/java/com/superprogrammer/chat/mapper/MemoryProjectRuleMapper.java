package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.entity.MemoryProjectRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 项目收录规则 mapper（V65 记忆二期 P1）。
 * <p>
 * anchor 两列（halfvec/tsv 生成列）不映射实体字段，写入走 {@link #insertWithAnchor} /
 * {@link #updateWithAnchor} 自定义 SQL（同 MemoryTag 模式）；路由粗筛走
 * {@link #rankByAnchorHalfvec} / {@link #rankByAnchorTsv}（向量+BM25 双路 RRF 原料）。
 * <p>
 * halfvec 算子 {@code <=>} 在 XML 转义（V33 教训）。
 */
@Mapper
public interface MemoryProjectRuleMapper extends BaseMapper<MemoryProjectRule> {

    /** 新建规则（anchor 走自定义 SQL）。实体须 @Param("m")——混用实体+@Param 时属性名带前缀。 */
    void insertWithAnchor(@Param("m") MemoryProjectRule m,
                          @Param("anchorHalfvec") String anchorHalfvec);

    /** 更新规则文本/正负例/enabled + anchor 重生。anchorHalfvec null 时保留旧 anchor 且 enabled 强制 false 由调用方语义控制。 */
    int updateWithAnchor(@Param("m") MemoryProjectRule m,
                         @Param("anchorHalfvec") String anchorHalfvec);

    /** 路由候选：一批项目内 enabled=true 且 anchor 就绪的活规则（粗筛输入集）。 */
    List<MemoryProjectRule> findRoutingCandidates(@Param("projectIds") List<Long> projectIds);

    /** 粗筛路 A：anchor halfvec 距离升序（限定候选规则集内）。queryVec 为 halfvec 文本 '[..]'。 */
    List<Long> rankByAnchorHalfvec(@Param("ruleIds") List<Long> ruleIds,
                                   @Param("queryVec") String queryVec,
                                   @Param("limit") int limit);

    /** 粗筛路 A 阈值版：anchor 距离 ≤ threshold 的规则（distance 语义：0=同，2=反），按距离升序。
     *  阈值外零候选 → 零 LLM 调用（FR-002 成本护栏）。 */
    List<Long> findWithinAnchorThreshold(@Param("ruleIds") List<Long> ruleIds,
                                         @Param("queryVec") String queryVec,
                                         @Param("threshold") double threshold,
                                         @Param("limit") int limit);

    /** 粗筛路 B：anchor BM25 tsv 相关度降序（限定候选规则集内）。
     *  orQuery = {@code TsQueryUtil.toOrQuery(jieba 空格串)}（OR 串，复活多 token 命中）。 */
    List<Long> rankByAnchorTsv(@Param("ruleIds") List<Long> ruleIds,
                               @Param("orQuery") String orQuery,
                               @Param("limit") int limit);
}
