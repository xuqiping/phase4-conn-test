// agent-platform/backend/src/main/java/com/superprogrammer/common/security/mapper/SecurityEventMapper.java
package com.superprogrammer.common.security.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.common.security.entity.SecurityEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/** 安全事件 Mapper（11x 加固）。 */
@Mapper
public interface SecurityEventMapper extends BaseMapper<SecurityEvent> {

    /** ACK 处置（条件更新幂等：并发 ack 只中一次，返 0=已被他人处理）。 */
    @Update("UPDATE security_events SET handled = TRUE, handled_by = #{operator}, handled_at = NOW() " +
            "WHERE id = #{id} AND handled = FALSE")
    int ackIfUnhandled(@Param("id") Long id, @Param("operator") String operator);

    /** 24h 严重度分布（风险大盘）：[{severity, cnt}]。 */
    @Select("SELECT severity, COUNT(*) AS cnt FROM security_events " +
            "WHERE created_at > NOW() - INTERVAL '24 hours' GROUP BY severity")
    List<Map<String, Object>> countBySeverity24h();

    /** 24h 事件类型 TOP 分布（风险大盘）：[{event_type, cnt}]，最多 10 类。 */
    @Select("SELECT event_type, COUNT(*) AS cnt FROM security_events " +
            "WHERE created_at > NOW() - INTERVAL '24 hours' GROUP BY event_type ORDER BY cnt DESC LIMIT 10")
    List<Map<String, Object>> countByType24h();
}
