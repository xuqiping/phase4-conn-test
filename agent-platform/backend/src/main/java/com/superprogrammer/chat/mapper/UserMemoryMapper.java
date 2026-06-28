package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.dto.MemoryBlockHit;
import com.superprogrammer.chat.dto.MemoryProjectRow;
import com.superprogrammer.chat.entity.UserMemory;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * user_memories mapper（V33 加项目记忆 scope）。
 * embedding halfvec 列不映射为实体字段 → 自定义 @Insert/@Update 走 #{halfvec}::halfvec（同 KnowledgeEmbeddingMapper）。
 * BaseMapper.selectList/selectById 会忽略 embedding 列（无对应字段）。
 * <p>
 * scope 过滤（V33）：读查询带 {@code includeGlobal + projectIds} 两参，命中 iff
 *   (includeGlobal AND is_global) OR id 在 user_memory_projects 挂在 projectIds 任一项目。
 *   两参全关 → 1=0 → 0 行（「全关→不注入」）。写侧查询传写目标 scope（单 scope）。
 */
@Mapper
public interface UserMemoryMapper extends BaseMapper<UserMemory> {

    /** scope 过滤片段（须先别名表为 m）。AND (1=0 OR is_global OR 挂 projectIds)。全关→0 行。 */
    String SCOPE_FILTER = " AND (1=0 "
            + "<if test=\"includeGlobal\">OR m.is_global</if>"
            + "<if test=\"projectIds != null and !projectIds.isEmpty()\">"
            + " OR m.id IN (SELECT memory_id FROM user_memory_projects WHERE project_id IN "
            + "<foreach collection=\"projectIds\" item=\"p\" open=\"(\" separator=\",\" close=\")\">#{p}</foreach>)"
            + "</if>)";

    /** 带 embedding 的插入（halfvec 走 ::halfvec）。clean 记忆 conflict_id 传 null。
     *  实体须 @Param("m")——MyBatis 混用实体+@Param 时，属性名须带前缀。is_global 落实体字段。
     *  V34：home_project_id 落实体字段（NULL=global home）。 */
    @Insert("""
            INSERT INTO user_memories
                (user_id, category, memory_key, memory_key_zh, memory_value, source, confidence, block_label,
                 embedding, anchor_embedding, anchor_tokens, conflict_id, entities, is_global, home_project_id, created_at, updated_at)
            VALUES
                (#{m.userId}, #{m.category}, #{m.memoryKey}, #{m.memoryKeyZh}, #{m.memoryValue}, #{m.source}, #{m.confidence},
                 #{m.blockLabel}, #{halfvec}::halfvec, #{anchorHalfvec}::halfvec, #{anchorTokens},
                 #{m.conflictId}, #{m.entities}::jsonb, COALESCE(#{m.isGlobal}, true), #{m.homeProjectId}, now(), now())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "m.id")
    void insertMemory(@Param("m") UserMemory m, @Param("halfvec") String halfvec,
                      @Param("anchorHalfvec") String anchorHalfvec, @Param("anchorTokens") String anchorTokens);

    /** V34 home-aware dedup：同 user 同 key 同 home 的 clean 行（COALESCE 解 NULL 多值）。
     *  取代旧 scope-filtered findSameKeyClean——home 与唯一索引对齐，避免写目标看不到的老行撞墙。
     *  不带读可见性过滤（dedup 只关心唯一性槽）。 */
    @Select("SELECT * FROM user_memories WHERE user_id=#{userId} AND memory_key=#{key} "
            + "AND conflict_id IS NULL AND COALESCE(home_project_id,-1)=COALESCE(#{homeId},-1)")
    List<UserMemory> findCleanByHomeKey(@Param("userId") Long userId, @Param("key") String key, @Param("homeId") Long homeId);

    /** 更新 embedding + block（重抽/改块时）+ anchor 两列（COALESCE 保留旧值，null 安全）。 */
    @Update("UPDATE user_memories SET embedding=#{halfvec}::halfvec, block_label=#{blockLabel}, "
            + "anchor_embedding=COALESCE(#{anchorHalfvec}::halfvec, anchor_embedding), "
            + "anchor_tokens=COALESCE(#{anchorTokens}, anchor_tokens), updated_at=now() WHERE id=#{id}")
    void updateEmbeddingBlock(@Param("id") Long id, @Param("halfvec") String halfvec, @Param("blockLabel") String blockLabel,
                              @Param("anchorHalfvec") String anchorHalfvec, @Param("anchorTokens") String anchorTokens);

    /** 细化更新：同 key 既有 clean 行，覆盖 value+key_zh+confidence+embedding+block+entities+anchor（refinement，绕唯一约束）。 */
    @Update("UPDATE user_memories SET memory_value=#{value}, memory_key_zh=#{memoryKeyZh}, confidence=#{confidence}, "
            + "embedding=#{halfvec}::halfvec, block_label=#{blockLabel}, entities=#{entities}::jsonb, "
            + "anchor_embedding=#{anchorHalfvec}::halfvec, anchor_tokens=#{anchorTokens}, updated_at=now() WHERE id=#{id}")
    int updateCleanMemory(@Param("id") Long id, @Param("value") String value,
                          @Param("confidence") BigDecimal confidence,
                          @Param("blockLabel") String blockLabel, @Param("halfvec") String halfvec,
                          @Param("entities") String entities, @Param("memoryKeyZh") String memoryKeyZh,
                          @Param("anchorHalfvec") String anchorHalfvec, @Param("anchorTokens") String anchorTokens);

    /** 设置 is_global（scope 编辑用）。 */
    @Update("UPDATE user_memories SET is_global=#{isGlobal}, updated_at=now() WHERE id=#{id}")
    int updateIsGlobal(@Param("id") Long id, @Param("isGlobal") boolean isGlobal);

    /** 最近邻块匹配（限 scope）：返回最近一行的 block_label + 余弦距离（embedding <=>）。 */
    @Select("<script>" +
            "SELECT block_label, embedding &lt;=> #{halfvec}::halfvec AS distance " +
            "FROM user_memories m WHERE user_id=#{userId} AND embedding IS NOT NULL " +
            SCOPE_FILTER + " " +
            "ORDER BY embedding &lt;=> #{halfvec}::halfvec LIMIT 1" +
            "</script>")
    MemoryBlockHit findNearestBlock(@Param("userId") Long userId, @Param("halfvec") String halfvec,
                                    @Param("includeGlobal") boolean includeGlobal,
                                    @Param("projectIds") List<Long> projectIds);

    /** 向量 top-K 检索（限 scope）：余弦相似度 (1 - distance) ≥ threshold，按距离升序取前 k 行。 */
    @Select("<script>" +
            "SELECT * FROM user_memories m WHERE user_id=#{userId} AND embedding IS NOT NULL " +
            "AND (1 - (embedding &lt;=> #{halfvec}::halfvec)) >= #{threshold} " +
            SCOPE_FILTER + " " +
            "ORDER BY embedding &lt;=> #{halfvec}::halfvec LIMIT #{k}" +
            "</script>")
    List<UserMemory> findTopKByVector(@Param("userId") Long userId,
                                      @Param("halfvec") String halfvec,
                                      @Param("threshold") double threshold,
                                      @Param("k") int k,
                                      @Param("includeGlobal") boolean includeGlobal,
                                      @Param("projectIds") List<Long> projectIds);

    /** V38 anchor 向量 top-K 检索（限 scope）：粗筛主通道，仿 findTopKByVector 换 anchor_embedding {@code <=>}。
     *  anchor_embedding = embed(block_label + key_zh + key + entities)，标签+词袋语义，召回率优于 value 向量。
     *  与 findTopKByVector 并列（anchor 不替代 value 向量），LLM_KEY 模式粗筛用。 */
    @Select("<script>" +
            "SELECT * FROM user_memories m WHERE user_id=#{userId} AND anchor_embedding IS NOT NULL " +
            "AND (1 - (anchor_embedding &lt;=> #{anchorHalfvec}::halfvec)) >= #{threshold} " +
            SCOPE_FILTER + " " +
            "ORDER BY anchor_embedding &lt;=> #{anchorHalfvec}::halfvec LIMIT #{k}" +
            "</script>")
    List<UserMemory> findTopKByAnchor(@Param("userId") Long userId,
                                      @Param("anchorHalfvec") String anchorHalfvec,
                                      @Param("threshold") double threshold,
                                      @Param("k") int k,
                                      @Param("includeGlobal") boolean includeGlobal,
                                      @Param("projectIds") List<Long> projectIds);

    /** V38 anchor BM25 召回（限 scope）：粗筛词法通道，仿知识库 bm25HitsJieba。
     *  query 已 jieba 分词为空格串；per-token OR（任一 token 命中 anchor_tokens_tsv 即召回），
     *  rank = 命中 token 的 ts_rank 之和。存量行 anchor_tokens IS NULL → tsv 空 → 不命中（回填后生效）。 */
    @Select("<script>" +
            "SELECT m.* FROM user_memories m WHERE m.user_id=#{userId} " +
            "AND EXISTS (SELECT 1 FROM unnest(string_to_array(#{q}, ' ')) AS tok " +
            "WHERE m.anchor_tokens_tsv @@ plainto_tsquery('simple', tok)) " +
            SCOPE_FILTER + " " +
            "ORDER BY (SELECT COALESCE(SUM(ts_rank(m.anchor_tokens_tsv, plainto_tsquery('simple', tok))), 0) " +
            "FROM unnest(string_to_array(#{q}, ' ')) AS tok) DESC " +
            "LIMIT #{k}" +
            "</script>")
    List<UserMemory> findAnchorBm25(@Param("userId") Long userId,
                                    @Param("q") String tokenizedQuery,
                                    @Param("k") int k,
                                    @Param("includeGlobal") boolean includeGlobal,
                                    @Param("projectIds") List<Long> projectIds);

    /** 关键词召回（限 scope）：任一关键词命中 entities(JSONB::text) / memory_key / memory_value / memory_key_zh / block_label → 返回（五列并查）。
     *  仅 clean 行（conflict_id IS NULL）。per-block_label 阈值筛选取代旧全局 LIMIT 10（见 MemoryService.applyKeywordPerBlockThreshold）。
     *  #{kw} 走 MyBatis 参数绑定，无注入；entities::text ILIKE 让 JSONB 当文本匹配。 */
    @Select("<script>" +
            "SELECT * FROM user_memories m WHERE user_id=#{userId} AND conflict_id IS NULL AND (" +
            "<foreach collection='kws' item='kw' separator=' OR '>" +
            "(entities IS NOT NULL AND entities::text ILIKE CONCAT('%',#{kw},'%')) " +
            "OR memory_key ILIKE CONCAT('%',#{kw},'%') " +
            "OR memory_value ILIKE CONCAT('%',#{kw},'%') " +
            "OR memory_key_zh ILIKE CONCAT('%',#{kw},'%') " +
            "OR block_label ILIKE CONCAT('%',#{kw},'%')" +
            "</foreach>) " +
            SCOPE_FILTER + " " +
            "LIMIT 200" +
            "</script>")
    List<UserMemory> findByKeyword(@Param("userId") Long userId, @Param("kws") List<String> kws,
                                   @Param("includeGlobal") boolean includeGlobal,
                                   @Param("projectIds") List<Long> projectIds);

    /** 批量把若干记忆的 conflict_id 指向同一冲突（FLAGGED 分组）。 */
    @Update({"<script>",
            "UPDATE user_memories SET conflict_id=#{conflictId} WHERE id IN ",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "</script>"})
    int setConflictId(@Param("ids") List<Long> ids, @Param("conflictId") Long conflictId);

    /** 取同块全部 clean 成员（限 scope）—— 冲突判定输入。 */
    @Select("<script>" +
            "SELECT * FROM user_memories m WHERE user_id=#{userId} AND block_label=#{blockLabel} AND conflict_id IS NULL " +
            SCOPE_FILTER +
            "</script>")
    List<UserMemory> findCleanByBlock(@Param("userId") Long userId, @Param("blockLabel") String blockLabel,
                                      @Param("includeGlobal") boolean includeGlobal,
                                      @Param("projectIds") List<Long> projectIds);

    /** 取某冲突组的全部行（FLAGGED resolve 用，scope 无关）。 */
    @Select("SELECT * FROM user_memories WHERE conflict_id=#{conflictId}")
    List<UserMemory> findByConflictId(@Param("conflictId") Long conflictId);

    /** 取该用户 scope 内已存在的 distinct memory_key（extract 时注入 LLM 做 key 复用白名单）。 */
    @Select("<script>" +
            "SELECT DISTINCT memory_key FROM user_memories m WHERE user_id=#{userId} " +
            SCOPE_FILTER + " " +
            "ORDER BY memory_key" +
            "</script>")
    List<String> findDistinctKeys(@Param("userId") Long userId,
                                  @Param("includeGlobal") boolean includeGlobal,
                                  @Param("projectIds") List<Long> projectIds);

    /** 全量召回（LLM_FULL_CONTEXT 模式，限 scope）：confidence≥阈值，按 updated_at 倒序。 */
    @Select("<script>" +
            "SELECT * FROM user_memories m WHERE user_id=#{userId} " +
            "AND confidence >= #{minConfidence} " +
            SCOPE_FILTER + " " +
            "ORDER BY updated_at DESC" +
            "</script>")
    List<UserMemory> findFullContext(@Param("userId") Long userId,
                                     @Param("minConfidence") BigDecimal minConfidence,
                                     @Param("includeGlobal") boolean includeGlobal,
                                     @Param("projectIds") List<Long> projectIds);

    /** 全部 clean 记忆（限 scope）—— hybrid 0命中 LLM-key 兜底 + fullContext 超阈值两阶段输入。 */
    @Select("<script>" +
            "SELECT * FROM user_memories m WHERE user_id=#{userId} AND conflict_id IS NULL " +
            SCOPE_FILTER +
            "</script>")
    List<UserMemory> findAllClean(@Param("userId") Long userId,
                                  @Param("includeGlobal") boolean includeGlobal,
                                  @Param("projectIds") List<Long> projectIds);

    /** scope 内记忆总数（confidence≥0.5）—— preview totalMemories 用。 */
    @Select("<script>" +
            "SELECT COUNT(*) FROM user_memories m WHERE user_id=#{userId} AND confidence >= #{minConfidence} " +
            SCOPE_FILTER +
            "</script>")
    Long countByScope(@Param("userId") Long userId, @Param("minConfidence") BigDecimal minConfidence,
                      @Param("includeGlobal") boolean includeGlobal, @Param("projectIds") List<Long> projectIds);

    /** 回填老记忆召回词袋 + 中文标签（V31/V32 迁移用）：更 entities + memory_key_zh，不 bump updated_at（不扰动记忆列表排序）。 */
    @Update("UPDATE user_memories SET entities=#{entities}::jsonb, memory_key_zh=#{memoryKeyZh} WHERE id=#{id}")
    int updateEntitiesAndKeyZh(@Param("id") Long id, @Param("entities") String entities, @Param("memoryKeyZh") String memoryKeyZh);

    /** KEEP_BOTH 合并：survivor 行 memory_value 改合并值 + 清 conflict_id（变 clean 单行）+ 重 embed。
     *  halfvec 传 null 时 COALESCE 保留旧向量（re-embed 失败不致向量丢失）。
     *  V38：anchor（block+key+entities）合并不变 → COALESCE 保留旧 anchor（null 安全）。 */
    @Update("UPDATE user_memories SET memory_value=#{value}, conflict_id=NULL, "
            + "embedding=COALESCE(#{halfvec}::halfvec, embedding), "
            + "anchor_embedding=COALESCE(#{anchorHalfvec}::halfvec, anchor_embedding), "
            + "anchor_tokens=COALESCE(#{anchorTokens}, anchor_tokens), updated_at=now() WHERE id=#{id}")
    int mergeIntoRow(@Param("id") Long id, @Param("value") String value, @Param("halfvec") String halfvec,
                     @Param("anchorHalfvec") String anchorHalfvec, @Param("anchorTokens") String anchorTokens);

    /** 历史 KEEP_BOTH 脏数据清理：找出 conflict_id 非空但其冲突已 RESOLVED 的残留行
     *  （旧"双行共存"语义遗留：resolve 后未清 conflict_id → 永久带标 + 抽取去重隐身）。
     *  维护端点用，按 (user_id, memory_key) 分组后合并/去标。scope 无关（全局维护）。 */
    @Select("SELECT m.* FROM user_memories m JOIN memory_conflicts c ON m.conflict_id = c.id "
            + "WHERE c.status = 'RESOLVED' ORDER BY m.user_id, m.memory_key, m.id")
    List<UserMemory> findResolvedFlaggedRows();

    // ============================ 记忆↔项目 关联（V33）============================

    /** 批量插入记忆-项目关联（新事实落项目时挂载）。空列表不执行。 */
    @Insert("<script>" +
            "INSERT INTO user_memory_projects (memory_id, project_id) VALUES " +
            "<foreach collection='projectIds' item='pid' separator=','>(#{memoryId}, #{pid})</foreach>" +
            "</script>")
    int insertMemoryProjects(@Param("memoryId") Long memoryId, @Param("projectIds") List<Long> projectIds);

    /** 删除某记忆的全部项目关联（scope 编辑替换前清空）。 */
    @Delete("DELETE FROM user_memory_projects WHERE memory_id=#{memoryId}")
    int deleteMemoryProjects(@Param("memoryId") Long memoryId);

    /** 取某记忆挂载的全部 project id（面板 scope 编辑回显）。 */
    @Select("SELECT project_id FROM user_memory_projects WHERE memory_id=#{memoryId}")
    List<Long> findProjectIdsByMemory(@Param("memoryId") Long memoryId);

    /** 批量取多条记忆的 project id（面板列表「所属项目」列，一次查避免 N+1）。 */
    @Select("<script>" +
            "SELECT memory_id, project_id FROM user_memory_projects WHERE memory_id IN " +
            "<foreach collection='memoryIds' item='mid' open='(' separator=',' close=')'>#{mid}</foreach>" +
            "</script>")
    List<MemoryProjectRow> findProjectIdsByMemories(@Param("memoryIds") List<Long> memoryIds);
}
