package com.superprogrammer.auth.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * searchActiveCandidates SQL 契约：选人候选最小列 + 仅 ACTIVE + 排除集 + 上限。
 * 修复III E3（12x#4）：列扩 id/username/name/remark、keyword 三字段模糊（username/name/remark）。
 */
class UserMapperCandidateContractTest {

    @Test
    void candidateSqlSelectsOnlyMinimalActiveUsersWithExclusionsAndLimit() throws Exception {
        Method method = UserMapper.class.getMethod(
                "searchActiveCandidates", String.class, List.class, int.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value()).toLowerCase();
        assertTrue(sql.contains("select id, username, name, remark from users"));
        assertTrue(sql.contains("status = 'active'"));
        // 三字段模糊（E3「A 班」备注筛选）
        assertTrue(sql.contains("username like"));
        assertTrue(sql.contains("name like"));
        assertTrue(sql.contains("remark like"));
        assertTrue(sql.contains("id not in"));
        assertTrue(sql.contains("limit #{limit}"));
        assertFalse(sql.contains("password"));
        assertFalse(sql.contains("email"));
    }
}
