package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.entity.MemoryKeyMeta;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * memory_key_meta mapper(M2 时间线记忆):per-user per-key 时序事实标记。
 * <p>
 * 仅 merge/resolve 读 + panel 改标写。BaseMapper 的 insert/updateById 够用;
 * unique (user_id, memory_key) 由 DB 索约 {@code uk_memory_key_meta_user_key} 兜底。
 */
@Mapper
public interface MemoryKeyMetaMapper extends BaseMapper<MemoryKeyMeta> {

    /** 按 (user, key) 读标;无则 null。 */
    @Select("SELECT * FROM memory_key_meta WHERE user_id = #{userId} AND memory_key = #{memoryKey}")
    MemoryKeyMeta findByUserKey(@Param("userId") Long userId, @Param("memoryKey") String memoryKey);
}
