package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.entity.MemoryConflict;
import org.apache.ibatis.annotations.*;

/**
 * memory_conflicts mapper。
 * 特型列写入走自定义 @Insert（jsonb/halfvec/bigint[] casts）。
 * BaseMapper 给 selectById/updateById（状态字段更新）。
 */
@Mapper
public interface MemoryConflictMapper extends BaseMapper<MemoryConflict> {

    /** 自定义插入（halfvec + bigint[] + jsonb casts）。id 回填。 */
    @Insert("""
            INSERT INTO memory_conflicts
                (user_id, session_id, block_label, new_memory, new_embedding, existing_memory_ids,
                 ask_text, status, expires_at, created_at)
            VALUES
                (#{userId}, #{sessionId}, #{blockLabel}, #{newMemory}::jsonb, #{newEmbedding}::halfvec,
                 #{existingIds}::bigint[], #{askText}, #{status}, #{expiresAt}, now())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertConflict(MemoryConflict c, @Param("existingIds") String existingIdsJson);

    /** 会话活跃 PENDING（含已过期，由 service 判 expires_at 决定懒 flag）。 */
    @Select("SELECT * FROM memory_conflicts WHERE session_id=#{sessionId} AND user_id=#{userId} AND status='PENDING' ORDER BY created_at DESC LIMIT 1")
    MemoryConflict findActivePending(@Param("sessionId") Long sessionId, @Param("userId") Long userId);
}
