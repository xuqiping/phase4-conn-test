package com.superprogrammer.asset.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class AssetPublicAccessRequestMapperContractTest {

    @Test
    void ownerListJoinsOnlyApplicantUsername() throws Exception {
        Method method = AssetPublicAccessRequestMapper.class.getMethod("selectOwnerViewByProjectId", Long.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ")
                .toLowerCase();

        assertThat(sql)
                .contains("join users u on u.id = r.applicant_id")
                .contains("u.username as applicant_username")
                .doesNotContain("password")
                .doesNotContain("email");
    }
}
