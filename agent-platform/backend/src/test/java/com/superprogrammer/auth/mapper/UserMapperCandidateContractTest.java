package com.superprogrammer.auth.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserMapperCandidateContractTest {

    @Test
    void candidateSqlSelectsOnlyMinimalActiveUsersWithExclusionsAndLimit() throws Exception {
        Method method = UserMapper.class.getMethod(
                "searchActiveCandidates", String.class, List.class, int.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value()).toLowerCase();

        assertTrue(sql.contains("select id, username from users"));
        assertTrue(sql.contains("status = 'active'"));
        assertTrue(sql.contains("id not in"));
        assertTrue(sql.contains("limit #{limit}"));
        assertFalse(sql.contains("password"));
        assertFalse(sql.contains("email"));
    }
}
