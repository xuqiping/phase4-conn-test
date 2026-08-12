package com.superprogrammer.asset.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class AssetMapperFileAccessContractTest {

    @Test
    void fileAccessSqlRequiresLiveAssetProjectAndKnownReadGrant() throws Exception {
        Method method = AssetMapper.class.getMethod(
                "countAccessibleFileReferences", String.class, Long.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ")
                .toLowerCase();

        assertThat(sql)
                .contains("from asset_versions av")
                .contains("join assets a")
                .contains("a.deleted = 0")
                .contains("join asset_projects p")
                .contains("p.deleted = 0")
                .contains("p.owner_id = #{userid}")
                .contains("asset_project_members")
                .contains("p.public_pool = true")
                .contains("p.public_access_mode = 'open'")
                .contains("asset_public_access_requests")
                .contains("r.status = 'approved'");
    }
}
