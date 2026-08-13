// agent-platform/backend/src/main/java/com/superprogrammer/common/security/mapper/IpBlacklistMapper.java
package com.superprogrammer.common.security.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.common.security.entity.IpBlacklist;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

/** IP 封禁 Mapper（11x 加固）。 */
@Mapper
public interface IpBlacklistMapper extends BaseMapper<IpBlacklist> {

    /** upsert：UNIQUE(ip) 冲突时刷新封禁期/原因（重复触发自动封=续期）。 */
    @Insert("INSERT INTO ip_blacklist (ip, source, reason, banned_until, created_by) " +
            "VALUES (#{ip}, #{source}, #{reason}, #{bannedUntil}, #{createdBy}) " +
            "ON CONFLICT (ip) DO UPDATE SET source = EXCLUDED.source, reason = EXCLUDED.reason, " +
            "banned_until = EXCLUDED.banned_until")
    int upsert(@Param("ip") String ip, @Param("source") String source, @Param("reason") String reason,
               @Param("bannedUntil") OffsetDateTime bannedUntil, @Param("createdBy") String createdBy);

    /** 启动加载：未过期（永久或 banned_until > now）的全部行。 */
    @Select("SELECT * FROM ip_blacklist WHERE banned_until IS NULL OR banned_until > #{now}")
    List<IpBlacklist> selectActive(@Param("now") OffsetDateTime now);

    /** 解封。 */
    @Delete("DELETE FROM ip_blacklist WHERE ip = #{ip}")
    int deleteByIp(@Param("ip") String ip);

    /** 定时清理：物理删过期行（Redis key 有 TTL 会自愈，这里清 DB 取证残渣）。 */
    @Delete("DELETE FROM ip_blacklist WHERE banned_until IS NOT NULL AND banned_until <= #{now}")
    int deleteExpired(@Param("now") OffsetDateTime now);
}
