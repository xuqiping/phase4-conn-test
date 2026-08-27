package com.superprogrammer.projectgroup.service;

import com.superprogrammer.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 修复V B1/B2（17x#1）真 PG IT：流水筛选（keyword 子查询/type/actor/时间）与 CSV 导出全链走真 SQL——
 * 单测 mock mapper 永不执行 SQL，`.apply` 子查询语法错/参数错只有真库能抓住（AssetMediaBridgeExistsIT 同教训）。
 */
@SpringBootTest
@Tag("integration")
@ActiveProfiles("it")
class ProjectGroupLedgerFilterIT {

    private static final long GROUP_ID = 991700001L;
    private static final long OWNER = 1L;
    private static final long MEMBER = 2L;

    @Autowired
    private ProjectGroupQueryService queryService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        clean();
        jdbc.update("INSERT INTO project_groups (id, name, owner_user_id) OVERRIDING SYSTEM VALUE VALUES (?, 'IT流水筛选组', ?)",
                GROUP_ID, OWNER);
        jdbc.update("INSERT INTO project_group_wallets (group_id, balance_points) VALUES (?, 100)", GROUP_ID);
        jdbc.update("INSERT INTO project_group_members (group_id, user_id, role) VALUES (?, ?, 'MEMBER')", GROUP_ID, MEMBER);
        // 四行流水：老划拨（2 天前）/ 消耗（成员）/ 兜底 / 配额划入——remark 与 actor 分散供筛选命中/排除
        jdbc.update("INSERT INTO project_group_ledger (group_id, actor_user_id, type, delta_points, balance_after, remark, created_at) "
                        + "VALUES (?, ?, 'ALLOCATE', 200, 200, '月初划拨', NOW() - INTERVAL '2 days')", GROUP_ID, OWNER);
        jdbc.update("INSERT INTO project_group_ledger (group_id, actor_user_id, type, delta_points, balance_after, ref_type, ref_id, remark) "
                        + "VALUES (?, ?, 'CONSUME', -30, 170, 'VIDEO', '51', '视频生成消耗')", GROUP_ID, MEMBER);
        jdbc.update("INSERT INTO project_group_ledger (group_id, actor_user_id, type, delta_points, balance_after, remark) "
                        + "VALUES (?, ?, 'BACKSTOP', -70, 100, '组长兜底垫付')", GROUP_ID, OWNER);
        jdbc.update("INSERT INTO project_group_ledger (group_id, actor_user_id, type, delta_points, balance_after, remark) "
                        + "VALUES (?, ?, 'MEMBER_ALLOCATE', 0, 100, '配额调整留痕')", GROUP_ID, OWNER);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        jdbc.update("DELETE FROM project_group_ledger WHERE group_id=?", GROUP_ID);
        jdbc.update("DELETE FROM project_group_wallets WHERE group_id=?", GROUP_ID);
        jdbc.update("DELETE FROM project_group_members WHERE group_id=?", GROUP_ID);
        jdbc.update("DELETE FROM project_groups WHERE id=?", GROUP_ID);
    }

    /** keyword 命中流水备注（含 users 子查询的 OR 真执行不炸）；type/actor/时间窗各自命中与排除。 */
    @Test
    void overview_ownerFilters_realSql() {
        // keyword 命中备注「月初划拨」→ 仅 1 行 ALLOCATE（子查询 OR 分支同时真执行）
        var byKw = queryService.overview(GROUP_ID, OWNER, false, "月初划拨", null, null, null, null, 1, 10);
        assertThat(byKw.ledger().getTotal()).isEqualTo(1);
        assertThat(byKw.ledger().getRecords().get(0).type()).isEqualTo("ALLOCATE");

        // keyword 命中 % 通配符明文 → 不当通配用（转义后无命中）
        assertThat(queryService.overview(GROUP_ID, OWNER, false, "%月初%", null, null, null, null, 1, 10)
                .ledger().getTotal()).isZero();

        // type 筛选
        assertThat(queryService.overview(GROUP_ID, OWNER, false, null, "CONSUME", null, null, null, 1, 10)
                .ledger().getTotal()).isEqualTo(1);

        // actor 筛选：成员仅 1 行（消耗）
        assertThat(queryService.overview(GROUP_ID, OWNER, false, null, null, MEMBER, null, null, 1, 10)
                .ledger().getTotal()).isEqualTo(1);

        // 时间窗：from=1 天前 → 排除 2 天前的划拨，余 3 行
        assertThat(queryService.overview(GROUP_ID, OWNER, false, null, null, null,
                OffsetDateTime.now().minusDays(1), null, 1, 10)
                .ledger().getTotal()).isEqualTo(3);
    }

    /** type 非白名单 → 400（不进 SQL）。 */
    @Test
    void overview_invalidType_throws400() {
        assertThatThrownBy(() -> queryService.overview(GROUP_ID, OWNER, false, null, "DROP_ALL", null, null, null, 1, 10))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法流水类型");
    }

    /** MEMBER 路径忽略筛选（传命中他行的 keyword 也只见本人行）。 */
    @Test
    void overview_memberIgnoresFilters_stillSelfOnly() {
        var vo = queryService.overview(GROUP_ID, MEMBER, false, "月初划拨", "ALLOCATE", OWNER, null, null, 1, 10);
        assertThat(vo.ledger().getTotal()).isEqualTo(1);
        assertThat(vo.ledger().getRecords().get(0).actorUserId()).isEqualTo(MEMBER);
        assertThat(vo.ledger().getRecords().get(0).balanceAfter()).isNull(); // IV D3 口径不回归
    }

    /** export：MEMBER 403（决策 4）。 */
    @Test
    void exportLedger_memberForbidden() {
        assertThatThrownBy(() -> queryService.exportLedger(GROUP_ID, MEMBER, false, null, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅组长/管理员可导出");
    }

    /** export：owner 全量 CSV（BOM/表头/中文标签/转义）真 SQL 全链。 */
    @Test
    void exportLedger_ownerCsv_realSql() {
        jdbc.update("UPDATE project_group_ledger SET remark='含,逗号\"引号' WHERE group_id=? AND type='BACKSTOP'", GROUP_ID);

        byte[] csv = queryService.exportLedger(GROUP_ID, OWNER, false, null, null, null, null, null);
        String s = new String(csv, StandardCharsets.UTF_8);

        assertThat(csv[0] & 0xFF).isEqualTo(0xEF); // BOM
        assertThat(csv[1] & 0xFF).isEqualTo(0xBB);
        assertThat(csv[2] & 0xFF).isEqualTo(0xBF);
        assertThat(s).startsWith("﻿时间,类型,操作人,变动积分,变动后组池余额,关联,备注\r\n");
        assertThat(s).contains("兜底");                       // 中文标签
        assertThat(s).contains("配额划入");
        assertThat(s).contains("VIDEO#51"); // ref 列（CONSUME 行，refType#refId 原样）
        assertThat(s).contains("\"含,逗号\"\"引号\"");         // RFC4180 转义
        assertThat(s.split("\r\n")).hasSize(5);                // 表头 + 4 数据行
    }

    /** export 带筛选：keyword 收窄后导出行数同步。 */
    @Test
    void exportLedger_respectsFilters() {
        byte[] csv = queryService.exportLedger(GROUP_ID, OWNER, false, "视频生成", null, null, null, null);
        String s = new String(csv, StandardCharsets.UTF_8);
        assertThat(s.split("\r\n")).hasSize(2); // 表头 + 1 行
        assertThat(s).contains("视频生成消耗");
    }
}
