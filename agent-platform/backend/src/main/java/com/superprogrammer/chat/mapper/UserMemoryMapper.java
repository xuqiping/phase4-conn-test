package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.dto.MemoryBlockHit;
import com.superprogrammer.chat.entity.UserMemory;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * user_memories mapper。
 * embedding halfvec 列不映射为实体字段 → 自定义 @Insert/@Update 走 #{halfvec}::halfvec（同 KnowledgeEmbeddingMapper）。
 * BaseMapper.selectList/selectById 会忽略 embedding 列（无对应字段）。
 */
@Mapper
public interface UserMemoryMapper extends BaseMapper<UserMemory> {

    /** 带 embedding 的插入（halfvec 走 ::halfvec）。clean 记忆 conflict_id 传 null。
     *  实体须 @Param("m")——MyBatis 混用实体+@Param 时，属性名须带前缀。 */
    @Insert("""
            INSERT INTO user_memories
                (user_id, category, memory_key, memory_value, source, confidence, block_label, embedding, conflict_id, created_at, updated_at)
            VALUES
                (#{m.userId}, #{m.category}, #{m.memoryKey}, #{m.memoryValue}, #{m.source}, #{m.confidence},
                 #{m.blockLabel}, #{halfvec}::halfvec, #{m.conflictId}, now(), now())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "m.id")
    void insertMemory(@Param("m") UserMemory m, @Param("halfvec") String halfvec);

    /** 更新 embedding + block（重抽/改块时）。 */
    @Update("UPDATE user_memories SET embedding=#{halfvec}::halfvec, block_label=#{blockLabel}, updated_at=now() WHERE id=#{id}")
    void updateEmbeddingBlock(@Param("id") Long id, @Param("halfvec") String halfvec, @Param("blockLabel") String blockLabel);

    /** 最近邻块匹配：返回最近一行的 block_label + 余弦距离（embedding <=>）。 */
    @Select("SELECT block_label, embedding <=> #{halfvec}::halfvec AS distance " +
            "FROM user_memories WHERE user_id=#{userId} AND embedding IS NOT NULL " +
            "ORDER BY embedding <=> #{halfvec}::halfvec LIMIT 1")
    MemoryBlockHit findNearestBlock(@Param("userId") Long userId, @Param("halfvec") String halfvec);

    /** 批量把若干记忆的 conflict_id 指向同一冲突（FLAGGED 分组）。 */
    @Update({"<script>",
            "UPDATE user_memories SET conflict_id=#{conflictId} WHERE id IN ",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "</script>"})
    int setConflictId(@Param("ids") List<Long> ids, @Param("conflictId") Long conflictId);

    /** 取同块全部 clean 成员（conflict_id IS NULL）—— 冲突判定输入。 */
    @Select("SELECT * FROM user_memories WHERE user_id=#{userId} AND block_label=#{blockLabel} AND conflict_id IS NULL")
    List<UserMemory> findCleanByBlock(@Param("userId") Long userId, @Param("blockLabel") String blockLabel);

    /** 取某冲突组的全部行（FLAGGED resolve 用）。 */
    @Select("SELECT * FROM user_memories WHERE conflict_id=#{conflictId}")
    List<UserMemory> findByConflictId(@Param("conflictId") Long conflictId);
}
