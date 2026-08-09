package com.superprogrammer.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.billing.entity.IdempotencyKeyEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 幂等键 Mapper（安全体系 S1 · SEC-FR-121）。
 */
@Mapper
public interface IdempotencyKeyMapper extends BaseMapper<IdempotencyKeyEntity> {

    /**
     * 占位：1=首次（调用方继续执行业务写）；0=撞键（重复提交，回查首次结果）。
     */
    @Insert("INSERT INTO idempotency_keys (idem_key, user_id, scope) "
            + "VALUES (#{key}, #{userId}, #{scope}) ON CONFLICT (idem_key) DO NOTHING")
    int tryOccupy(@Param("key") String key, @Param("userId") Long userId, @Param("scope") String scope);

    @Select("SELECT * FROM idempotency_keys WHERE idem_key = #{key}")
    IdempotencyKeyEntity selectByKey(@Param("key") String key);

    /** 业务写成功后回填首次流水引用（与业务写同事务）。 */
    @Update("UPDATE idempotency_keys SET result_ref = #{resultRef} WHERE idem_key = #{key}")
    int updateResultRef(@Param("key") String key, @Param("resultRef") String resultRef);
}
