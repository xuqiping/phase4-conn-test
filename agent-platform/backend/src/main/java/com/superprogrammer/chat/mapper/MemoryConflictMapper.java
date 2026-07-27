package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.entity.MemoryConflict;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * memory_conflicts mapper。
 * 特型列（jsonb/halfvec/bigint[]）写入走自定义 @Insert（casts）；
 * 读取特型列走 ::text / array_to_string 专法（避免 MyBatis 对 PG 特型列的 typehandler 缺失）。
 * BaseMapper 给 selectById/updateById（状态等标量字段更新）。
 * findActivePending 只选标量列（不碰特型列 → 实体特型字段保持 null，由专法按需读）。
 */
@Mapper
public interface MemoryConflictMapper extends BaseMapper<MemoryConflict> {

    /** 自定义插入（halfvec + bigint[] + jsonb casts）。id 回填。
     *  实体须 @Param("c")——MyBatis 混用实体+@Param 时，属性名须带前缀。 */
    @Insert("""
            INSERT INTO memory_conflicts
                (user_id, session_id, block_label, new_memory, new_embedding, existing_memory_ids,
                 ask_text, status, expires_at, created_at)
            VALUES
                (#{c.userId}, #{c.sessionId}, #{c.blockLabel}, #{c.newMemory}::jsonb, #{c.newEmbedding}::halfvec,
                 #{existingIds}::bigint[], #{c.askText}, #{c.status}, #{c.expiresAt}, now())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "c.id")
    void insertConflict(@Param("c") MemoryConflict c, @Param("existingIds") String existingIdsJson);

    /** 会话活跃 PENDING（只选标量列；含已过期，由 service 判 expires_at 懒 flag）。 */
    @Select("SELECT id, user_id, session_id, block_label, ask_text, status, expires_at, created_at " +
            "FROM memory_conflicts WHERE session_id=#{sessionId} AND user_id=#{userId} AND status='PENDING' " +
            "ORDER BY created_at DESC LIMIT 1")
    MemoryConflict findActivePending(@Param("sessionId") Long sessionId, @Param("userId") Long userId);

    /** 用户全部 FLAGGED（只选标量列）。 */
    @Select("SELECT id, user_id, session_id, block_label, ask_text, status, expires_at, created_at " +
            "FROM memory_conflicts WHERE user_id=#{userId} AND status='FLAGGED' ORDER BY created_at DESC")

    /** V47 计划12：新模型冲突（带 tag_id + summary_id），强制 user_id scope（向量 6）。XML 实现。 */
    List<MemoryConflict> findByUser(@Param("userId") Long userId);
    List<MemoryConflict> findFlaggedByUser(@Param("userId") Long userId);

    /** 用户全部 PENDING+FLAGGED（待处理，面板可见）。 */
    @Select("SELECT id, user_id, session_id, block_label, ask_text, status, expires_at, created_at " +
            "FROM memory_conflicts WHERE user_id=#{userId} AND status IN ('PENDING','FLAGGED') ORDER BY created_at DESC")
    List<MemoryConflict> findActiveByUser(@Param("userId") Long userId);

    /** 用户待处理冲突计数（PENDING+FLAGGED），状态条/角标轮询用（省去拉全量 list）。 */
    @Select("SELECT COUNT(*) FROM memory_conflicts WHERE user_id=#{userId} AND status IN ('PENDING','FLAGGED')")
    int countActiveByUser(@Param("userId") Long userId);

    /** 按 id 读标量列（避免 selectById 碰 bigint[]/jsonb/halfvec 特型列）。 */
    @Select("SELECT id, user_id, session_id, block_label, ask_text, status, expires_at, created_at " +
            "FROM memory_conflicts WHERE id=#{id}")
    MemoryConflict findByIdScalars(@Param("id") Long id);

    /** 定向更新状态（避免 updateById 把 new_memory/new_embedding/existing_memory_ids 覆盖为 null）。 */
    @Update("UPDATE memory_conflicts SET status=#{status}, resolution=#{resolution}, resolved_at=now() WHERE id=#{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status, @Param("resolution") String resolution);

    /** 读 existing_memory_ids 为 CSV（"7,8"）。 */
    @Select("SELECT COALESCE(array_to_string(existing_memory_ids, ','), '') FROM memory_conflicts WHERE id=#{id}")
    String getExistingIdsCsv(@Param("id") Long id);

    /** 读 new_memory 为 JSON 文本。 */
    @Select("SELECT new_memory::text FROM memory_conflicts WHERE id=#{id}")
    String getNewMemoryText(@Param("id") Long id);

    /** 读 new_embedding 为 halfvec 文本（'[..]'）。 */
    @Select("SELECT new_embedding::text FROM memory_conflicts WHERE id=#{id}")
    String getNewEmbeddingText(@Param("id") Long id);
}
