package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.entity.MemoryAssetMemory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 文件记忆 mapper（V69 记忆二期 P3）。
 * <p>
 * CRUD 走 BaseMapper + LambdaQueryWrapper（tag_ids BIGINT[] 由实体
 * {@code @TableField(typeHandler=LongArrayTypeHandler)} 处理）。
 * worker 认领先条件 UPDATE 占位（P2 状态机同款防并发，影响行数=0 即被他节点抢走），
 * 再按 locked_until 取回本节点批次——无需 FOR UPDATE SKIP LOCKED。
 */
@Mapper
public interface MemoryAssetMemoryMapper extends BaseMapper<MemoryAssetMemory> {

    /** worker 认领候选：PROCESSING 且锁过期（含崩溃自愈的 locked_until 过期行），按 id 升序封顶。 */
    @Select("SELECT id FROM memory_asset_memories WHERE deleted = 0 AND ingest_status = 'PROCESSING' "
            + "AND (locked_until IS NULL OR locked_until < #{now}) ORDER BY id LIMIT #{batch}")
    List<Long> findClaimCandidates(@Param("now") OffsetDateTime now, @Param("batch") int batch);

    /** 条件 UPDATE 认领占位：仅当仍 PROCESSING 且锁过期才置锁，返影响行数（0=被抢/已处理）。 */
    @Update("UPDATE memory_asset_memories SET locked_until = #{until} "
            + "WHERE id = #{id} AND deleted = 0 AND ingest_status = 'PROCESSING' "
            + "AND (locked_until IS NULL OR locked_until < #{now})")
    int claim(@Param("id") Long id, @Param("now") OffsetDateTime now, @Param("until") OffsetDateTime until);

    /** 完成/失败释放锁并前移状态（条件：锁仍归本节点批次，防迟到的旧 worker 覆盖新状态）。 */
    @Update("UPDATE memory_asset_memories SET ingest_status = #{status}, ingest_error = #{error}, "
            + "l1_summary = #{l1}, l2_detail = #{l2}, weak_memory = #{weak}, retry_count = #{retryCount}, "
            + "locked_until = NULL, updated_at = NOW() WHERE id = #{id} AND deleted = 0")
    int finishIngest(@Param("id") Long id, @Param("status") String status, @Param("error") String error,
                     @Param("l1") String l1, @Param("l2") String l2, @Param("weak") boolean weak,
                     @Param("retryCount") int retryCount);

    /** FAILED 手动重试：置回 PROCESSING 清锁（条件 UPDATE 防并发重复触发）。 */
    @Update("UPDATE memory_asset_memories SET ingest_status = 'PROCESSING', ingest_error = NULL, "
            + "locked_until = NULL, updated_at = NOW() "
            + "WHERE id = #{id} AND deleted = 0 AND ingest_status = 'FAILED'")
    int requeue(@Param("id") Long id);

    /** tag_ids 写回（归一后；BIGINT[] 显式 typeHandler 绕 LambdaUpdateWrapper 不吃 typeHandler 的坑，V33 旧教训）。 */
    @Update("UPDATE memory_asset_memories SET tag_ids = #{tagIds,typeHandler=com.superprogrammer.common.typehandler.LongArrayTypeHandler}, "
            + "updated_at = NOW() WHERE id = #{id} AND deleted = 0")
    int updateTagIds(@Param("id") Long id, @Param("tagIds") List<Long> tagIds);

    /**
     * Step 3 召回（FR-203）：本人 READY 文件记忆按标签重叠命中（PG {@code &&} 数组重叠）。
     * tagIds 走 LongArrayTypeHandler 成真 ARRAY 参数（createArrayOf），无需 ::bigint[] 强转。
     */
    @Select("SELECT * FROM memory_asset_memories WHERE deleted = 0 AND owner_user_id = #{userId} "
            + "AND ingest_status = 'READY' "
            + "AND tag_ids && #{tagIds,typeHandler=com.superprogrammer.common.typehandler.LongArrayTypeHandler} "
            + "ORDER BY updated_at DESC LIMIT #{limit}")
    List<MemoryAssetMemory> findReadyByTagOverlap(@Param("userId") Long userId,
                                                  @Param("tagIds") List<Long> tagIds,
                                                  @Param("limit") int limit);

    /**
     * 项目收录附件的元数据回查（记忆二期 P3 扩展）：按 file_id 批量取文件记忆行
     * （original_name/file_kind）——<b>不限 owner</b>，因项目 FILE 条目已被收录进项目，
     * 下载鉴权走 {@code MemoryFileEntryAccessGrantor}「成员可读」咽喉（非 owner 身份）。
     * 仅取元数据，不分块深读（分块浏览仅作者本人）。
     */
    @Select("<script>"
            + "SELECT * FROM memory_asset_memories WHERE deleted = 0 AND file_id IN "
            + "<foreach collection='fileIds' item='fid' open='(' separator=',' close=')'>#{fid}</foreach>"
            + "</script>")
    List<MemoryAssetMemory> findReadyByFileIds(@Param("fileIds") List<String> fileIds);
}
