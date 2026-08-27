package com.superprogrammer.asset.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 修复III F2 回归 IT（真 PG，2026-08-27）：existsBySourceTaskIds 走真 SQL——
 * 单测 mock mapper 永不执行 SQL，`.apply("{0}")` 漏传实参这类语法错只有真库能抓住
 * （线上症状：产出 tab 回填「已入库」时 PG 语法错 500）。
 */
@SpringBootTest
@Tag("integration")
@ActiveProfiles("it")
class AssetMediaBridgeExistsIT {

    private static final long PROJECT_ID = 991600001L;

    @Autowired
    private AssetMediaBridgeService bridgeService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        clean();
        jdbc.update("INSERT INTO asset_projects (id, name, owner_id) OVERRIDING SYSTEM VALUE VALUES (?, 'IT产出判重项目', 1)", PROJECT_ID);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        jdbc.update("DELETE FROM assets WHERE project_id=?", PROJECT_ID);
        jdbc.update("DELETE FROM asset_projects WHERE id=?", PROJECT_ID);
    }

    private void insertAsset(long id, String genMeta) {
        jdbc.update("INSERT INTO assets (id, project_id, media_type, name, media_category, gen_meta) "
                        + "OVERRIDING SYSTEM VALUE VALUES (?, ?, 'png', 'IT产出判重', 'IMAGE', CAST(? AS jsonb))",
                id, PROJECT_ID, genMeta);
    }

    /** 真 SQL 全链：命中的 taskId 回首个资产 id；未命中不产生键；source 非 MEDIA 不误报。 */
    @Test
    void existsBySource_realSql_matchesAndMisses() {
        insertAsset(991600101L, "{\"source\":\"MEDIA\",\"taskId\":51}");
        insertAsset(991600102L, "{\"source\":\"MEDIA\",\"taskId\":52,\"imageIdx\":2}");
        insertAsset(991600103L, "{\"source\":\"CANVAS\",\"taskId\":53}");

        Map<Long, Long> map = bridgeService.existsBySourceTaskIds(List.of(51L, 52L, 53L, 54L));

        assertThat(map.get(51L)).isEqualTo(991600101L);
        assertThat(map.get(52L)).isEqualTo(991600102L);
        // CANVAS 来源与不存在的 taskId 均不回填
        assertThat(map).hasSize(2);
    }

    /** 空入参不出库查询（服务内短路）。 */
    @Test
    void existsBySource_emptyInput_shortCircuits() {
        assertThat(bridgeService.existsBySourceTaskIds(List.of())).isEmpty();
    }
}
