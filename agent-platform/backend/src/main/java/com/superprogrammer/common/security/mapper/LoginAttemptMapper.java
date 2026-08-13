// agent-platform/backend/src/main/java/com/superprogrammer/common/security/mapper/LoginAttemptMapper.java
package com.superprogrammer.common.security.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.common.security.entity.LoginAttempt;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;

/** 登录尝试 Mapper（11x 加固）。 */
@Mapper
public interface LoginAttemptMapper extends BaseMapper<LoginAttempt> {

    /** 30 天滚动清理（定时任务物理删）。 */
    @Delete("DELETE FROM login_attempts WHERE created_at < #{cutoff}")
    int deleteOlderThan(@Param("cutoff") OffsetDateTime cutoff);
}
