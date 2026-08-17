package com.superprogrammer.projectgroup.entity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.projectgroup.mapper.ProjectGroupLedgerMapper;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMapper;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMemberMapper;
import com.superprogrammer.projectgroup.mapper.ProjectGroupWalletMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V133 迁移冒烟 + 四实体/Mapper 契约 IT（真 PG）：
 * ①审计填充（createdAt/updatedAt/deleted=0/version=0 走 MetaObjectHandler）；
 * ②UNIQUE(group_id,user_id) 防重复入组；③组池 CHECK>=0 不可透支；
 * ④组流水 type CHECK 六枚举；⑤个人流水新枚举 GROUP_ALLOCATE 可入（CHECK 换宽）。
 */
@SpringBootTest
@Tag("integration")
@ActiveProfiles("it")
class ProjectGroupEntityIT {

    private static final long OWNER = 991100001L;   // 专用测试组长
    private static final long MEMBER = 991100002L;  // 专用测试成员

    @Autowired
    private ProjectGroupMapper groupMapper;
    @Autowired
    private ProjectGroupMemberMapper memberMapper;
    @Autowired
    private ProjectGroupWalletMapper walletMapper;
    @Autowired
    private ProjectGroupLedgerMapper ledgerMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private Long groupId;

    @BeforeEach
    void setUp() {
        clean();
        ProjectGroupEntity g = new ProjectGroupEntity();
        g.setName("IT测试组");
        g.setOwnerUserId(OWNER);
        groupMapper.insert(g);
        groupId = g.getId();

        ProjectGroupMemberEntity ownerRow = new ProjectGroupMemberEntity();
        ownerRow.setGroupId(groupId);
        ownerRow.setUserId(OWNER);          // 组长默认 quota NULL=不限
        memberMapper.insert(ownerRow);

        ProjectGroupWalletEntity w = new ProjectGroupWalletEntity();
        w.setGroupId(groupId);
        w.setBalancePoints(BigDecimal.ZERO);
        walletMapper.insert(w);
    }

    @AfterEach
    void clean() {
        // ledger 无级联须先清；members/wallets 随组 CASCADE
        jdbc.update("DELETE FROM project_group_ledger WHERE group_id IN (SELECT id FROM project_groups WHERE owner_user_id = ?)", OWNER);
        jdbc.update("DELETE FROM project_groups WHERE owner_user_id = ?", OWNER);
        jdbc.update("DELETE FROM points_ledger WHERE user_id = ? AND type IN ('GROUP_ALLOCATE','GROUP_RECLAIM')", OWNER);
    }

    @Test
    void 审计填充与默认值_insert后自动带() {
        ProjectGroupEntity g = groupMapper.selectById(groupId);
        assertThat(g.getCreatedAt()).isNotNull();
        assertThat(g.getUpdatedAt()).isNotNull();
        assertThat(g.getDeleted()).isEqualTo(0);
        assertThat(g.getVersion()).isEqualTo(0);

        ProjectGroupMemberEntity m = memberMapper.selectOne(new LambdaQueryWrapper<ProjectGroupMemberEntity>()
                .eq(ProjectGroupMemberEntity::getGroupId, groupId)
                .eq(ProjectGroupMemberEntity::getUserId, OWNER));
        assertThat(m.getQuotaLimitPoints()).isNull();     // 组长默认不限
        assertThat(m.getUsedPoints()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void 重复入组_唯一约束拒() {
        ProjectGroupMemberEntity dup = new ProjectGroupMemberEntity();
        dup.setGroupId(groupId);
        dup.setUserId(OWNER);   // setUp 已插同一 (group,user)
        assertThatThrownBy(() -> memberMapper.insert(dup))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 组池不可透支_CHECK拒负余额() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE project_group_wallets SET balance_points = -1 WHERE group_id = ?", groupId))
                .hasMessageContaining("ck_pgw_nonneg");
    }

    @Test
    void 条件扣减_余额不足返回0行() {
        jdbc.update("UPDATE project_group_wallets SET balance_points = 10 WHERE group_id = ?", groupId);
        assertThat(walletMapper.deduct(groupId, new BigDecimal("6"))).isEqualTo(1);   // 够扣
        assertThat(walletMapper.deduct(groupId, new BigDecimal("6"))).isEqualTo(0);   // 剩4不够
        BigDecimal bal = jdbc.queryForObject(
                "SELECT balance_points FROM project_group_wallets WHERE group_id = ?", BigDecimal.class, groupId);
        assertThat(bal).isEqualByComparingTo(new BigDecimal("4"));                    // 第二次未生效
    }

    @Test
    void 组流水六枚举与非法type拒() {
        insertLedger(ProjectGroupLedgerEntity.TYPE_ALLOCATE, "50.00");
        insertLedger(ProjectGroupLedgerEntity.TYPE_CONSUME, "-3.50");
        insertLedger(ProjectGroupLedgerEntity.TYPE_BACKSTOP, "-2.00");
        assertThat(ledgerMapper.selectCount(new LambdaQueryWrapper<ProjectGroupLedgerEntity>()
                .eq(ProjectGroupLedgerEntity::getGroupId, groupId))).isEqualTo(3);

        assertThatThrownBy(() -> insertLedger("STEAL", "-999.00"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_pgl_type");
    }

    @Test
    void 个人流水新枚举_GROUP_ALLOCATE可入() {
        // V133 换宽 CHECK：六枚举皆可入（个人账本可追溯划拨去向）
        jdbc.update("INSERT INTO points_ledger (user_id, type, delta_points, balance_after, ref_type, ref_id, remark) "
                        + "VALUES (?, 'GROUP_ALLOCATE', -50.00, 50.00, 'GROUP', ?, 'IT测试划拨')", OWNER, groupId);
        String type = jdbc.queryForObject(
                "SELECT type FROM points_ledger WHERE user_id = ? AND ref_type = 'GROUP' ORDER BY id DESC LIMIT 1",
                String.class, OWNER);
        assertThat(type).isEqualTo("GROUP_ALLOCATE");
    }

    private void insertLedger(String type, String delta) {
        ProjectGroupLedgerEntity l = new ProjectGroupLedgerEntity();
        l.setGroupId(groupId);
        l.setActorUserId(OWNER);
        l.setType(type);
        l.setDeltaPoints(new BigDecimal(delta));
        l.setBalanceAfter(BigDecimal.TEN);
        l.setRefType(ProjectGroupLedgerEntity.REF_GROUP);
        l.setRefId(String.valueOf(groupId));
        ledgerMapper.insert(l);
    }
}
