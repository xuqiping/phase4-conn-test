package com.superprogrammer.projectgroup.service;

import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.projectgroup.entity.ProjectGroupEntity;
import com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity;
import com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity;
import com.superprogrammer.projectgroup.entity.ProjectGroupWalletEntity;
import com.superprogrammer.projectgroup.mapper.ProjectGroupLedgerMapper;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMapper;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMemberMapper;
import com.superprogrammer.projectgroup.mapper.ProjectGroupWalletMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step3 权限矩阵单测（plan 验证项）：成员直调 403 / 组长通 / admin 通 + 组管理边界。
 * 账务并发/对账归 ProjectGroupWalletServiceIT（真 PG）。
 */
@ExtendWith(MockitoExtension.class)
class ProjectGroupServiceTest {

    private static final long GROUP_ID = 10L;
    private static final long OWNER = 1L;
    private static final long MEMBER = 2L;
    private static final long OUTSIDER = 3L;

    @Mock private ProjectGroupMapper groupMapper;
    @Mock private ProjectGroupMemberMapper memberMapper;
    @Mock private ProjectGroupWalletMapper walletMapper;
    @Mock private ProjectGroupLedgerMapper ledgerMapper;
    @Mock private UserMapper userMapper;
    @Mock private MemberBudgetService budgetService;
    /** V161 修复III B3：removeMember 先退组结算（名下/欠款）——mock 兜注入。 */
    @Mock private ProjectGroupWalletService walletService;

    @InjectMocks
    private ProjectGroupService service;

    private ProjectGroupEntity group;

    @BeforeEach
    void setUp() {
        group = new ProjectGroupEntity();
        group.setId(GROUP_ID);
        group.setName("测试组");
        group.setOwnerUserId(OWNER);
        group.setDeleted(0);
    }

    private ProjectGroupMemberEntity member(long userId) {
        ProjectGroupMemberEntity m = new ProjectGroupMemberEntity();
        m.setGroupId(GROUP_ID);
        m.setUserId(userId);
        m.setUsedPoints(BigDecimal.ZERO);
        return m;
    }

    @Test
    void 权限矩阵_非组长非admin一律403() {
        lenient().when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        assertThatThrownBy(() -> service.rename(GROUP_ID, MEMBER, false, "新名"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("仅组长");
        assertThatThrownBy(() -> service.deleteGroup(GROUP_ID, OUTSIDER, false))
                .isInstanceOf(BusinessException.class).hasMessageContaining("仅组长");
        assertThatThrownBy(() -> service.addMember(GROUP_ID, OUTSIDER, false, MEMBER, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("仅组长");
        // V139：运营/查询类改 requireRole——非成员先撞「非本项目组成员」
        assertThatThrownBy(() -> service.removeMember(GROUP_ID, OUTSIDER, false, MEMBER))
                .isInstanceOf(BusinessException.class).hasMessageContaining("非本项目组成员");
        assertThatThrownBy(() -> service.updateQuota(GROUP_ID, OUTSIDER, false, MEMBER, BigDecimal.TEN))
                .isInstanceOf(BusinessException.class).hasMessageContaining("非本项目组成员");
        assertThatThrownBy(() -> service.resetUsed(GROUP_ID, OUTSIDER, false, MEMBER))
                .isInstanceOf(BusinessException.class).hasMessageContaining("非本项目组成员");
        assertThatThrownBy(() -> service.getDetail(GROUP_ID, MEMBER, false))
                .isInstanceOf(BusinessException.class).hasMessageContaining("非本项目组成员");
    }

    // ==================== 17x#2 角色体系（V139） ====================

    private static final long MANAGER_UID = 4L;

    private ProjectGroupMemberEntity roleRow(long userId, String role) {
        ProjectGroupMemberEntity m = member(userId);
        m.setRole(role);
        return m;
    }

    @Test
    void 角色矩阵_MANAGER可运营MEMBER行() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        ProjectGroupMemberEntity mgrRow = roleRow(MANAGER_UID, ProjectGroupMemberEntity.ROLE_MANAGER);
        when(memberMapper.selectByGroupUser(GROUP_ID, MANAGER_UID)).thenReturn(mgrRow);
        when(memberMapper.selectByGroupUser(GROUP_ID, MEMBER))
                .thenReturn(roleRow(MEMBER, ProjectGroupMemberEntity.ROLE_MEMBER));
        // V156：管理调限额走 FOR UPDATE 锁自己行（不限额管理 → 预算硬卡跳过）
        when(memberMapper.selectByGroupUserForUpdate(GROUP_ID, MANAGER_UID)).thenReturn(mgrRow);

        assertThatCode(() -> service.updateQuota(GROUP_ID, MANAGER_UID, false, MEMBER, BigDecimal.TEN))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.resetUsed(GROUP_ID, MANAGER_UID, false, MEMBER))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.removeMember(GROUP_ID, MANAGER_UID, false, MEMBER))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.getDetail(GROUP_ID, MANAGER_UID, false))
                .doesNotThrowAnyException();
    }

    @Test
    void 角色矩阵_MEMBER运营403_MANAGER动MANAGER行403() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        when(memberMapper.selectByGroupUser(GROUP_ID, MEMBER))
                .thenReturn(roleRow(MEMBER, ProjectGroupMemberEntity.ROLE_MEMBER));
        // MEMBER 运营他人：角色不足
        assertThatThrownBy(() -> service.updateQuota(GROUP_ID, MEMBER, false, OUTSIDER, BigDecimal.TEN))
                .isInstanceOf(BusinessException.class).hasMessageContaining("无权操作");
        // MANAGER 动 MANAGER 行：目标不可运营
        when(memberMapper.selectByGroupUser(GROUP_ID, MANAGER_UID))
                .thenReturn(roleRow(MANAGER_UID, ProjectGroupMemberEntity.ROLE_MANAGER));
        assertThatThrownBy(() -> service.removeMember(GROUP_ID, MANAGER_UID, false, MANAGER_UID))
                .isInstanceOf(BusinessException.class).hasMessageContaining("仅可管理普通成员");
    }

    @Test
    void 任免角色_仅组长_MEMBER与MANAGER互转() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        when(memberMapper.selectByGroupUserForUpdate(GROUP_ID, MEMBER))
                .thenReturn(roleRow(MEMBER, ProjectGroupMemberEntity.ROLE_MEMBER));
        // 非组长（MEMBER 行）任免 → requireOwner 拦
        assertThatThrownBy(() -> service.updateMemberRole(GROUP_ID, MEMBER, false, OUTSIDER,
                ProjectGroupMemberEntity.ROLE_MANAGER))
                .isInstanceOf(BusinessException.class).hasMessageContaining("仅组长");
        // 组长升 MANAGER
        assertThatCode(() -> service.updateMemberRole(GROUP_ID, OWNER, false, MEMBER,
                ProjectGroupMemberEntity.ROLE_MANAGER)).doesNotThrowAnyException();
        verify(memberMapper).updateById(any(ProjectGroupMemberEntity.class));
    }

    @Test
    void 任免角色_边界_组长行不可动_非法角色_同角色幂等() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        // 目标=组长本人
        assertThatThrownBy(() -> service.updateMemberRole(GROUP_ID, OWNER, false, OWNER,
                ProjectGroupMemberEntity.ROLE_MEMBER))
                .isInstanceOf(BusinessException.class).hasMessageContaining("组长角色不可变更");
        // 非法角色
        assertThatThrownBy(() -> service.updateMemberRole(GROUP_ID, OWNER, false, MEMBER, "SUPER"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("仅支持");
        // 同角色幂等：不触发 update
        when(memberMapper.selectByGroupUserForUpdate(GROUP_ID, MEMBER))
                .thenReturn(roleRow(MEMBER, ProjectGroupMemberEntity.ROLE_MEMBER));
        assertThatCode(() -> service.updateMemberRole(GROUP_ID, OWNER, false, MEMBER,
                ProjectGroupMemberEntity.ROLE_MEMBER)).doesNotThrowAnyException();
        verify(memberMapper, never()).updateById(any(ProjectGroupMemberEntity.class));
    }

    // ==================== 17x#2 功能开关（V139） ====================

    @Test
    void 功能开关_设置与非法值() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        when(memberMapper.selectByGroupUser(GROUP_ID, MEMBER))
                .thenReturn(roleRow(MEMBER, ProjectGroupMemberEntity.ROLE_MEMBER));
        // 组长设置白名单
        assertThatCode(() -> service.updateMemberKinds(GROUP_ID, OWNER, false, MEMBER, List.of("CHAT", "IMAGE")))
                .doesNotThrowAnyException();
        org.mockito.ArgumentCaptor<ProjectGroupMemberEntity> cap =
                org.mockito.ArgumentCaptor.forClass(ProjectGroupMemberEntity.class);
        verify(memberMapper).updateById(cap.capture());
        assertThat(cap.getValue().getAllowedKinds()).isEqualTo("[\"CHAT\",\"IMAGE\"]");
        // null=不限
        assertThatCode(() -> service.updateMemberKinds(GROUP_ID, OWNER, false, MEMBER, null))
                .doesNotThrowAnyException();
        // 非法模块 400
        assertThatThrownBy(() -> service.updateMemberKinds(GROUP_ID, OWNER, false, MEMBER, List.of("HACK")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("非法模块");
    }

    @Test
    void 功能开关_MANAGER可设但不能动MANAGER行() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        when(memberMapper.selectByGroupUser(GROUP_ID, MANAGER_UID))
                .thenReturn(roleRow(MANAGER_UID, ProjectGroupMemberEntity.ROLE_MANAGER));
        when(memberMapper.selectByGroupUser(GROUP_ID, MEMBER))
                .thenReturn(roleRow(MEMBER, ProjectGroupMemberEntity.ROLE_MEMBER));
        // MANAGER 设 MEMBER 行：通
        assertThatCode(() -> service.updateMemberKinds(GROUP_ID, MANAGER_UID, false, MEMBER, List.of()))
                .doesNotThrowAnyException();
        // MANAGER 设 MANAGER 行（自己）：403
        assertThatThrownBy(() -> service.updateMemberKinds(GROUP_ID, MANAGER_UID, false, MANAGER_UID, List.of()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("仅可管理普通成员");
    }

    @Test
    void 权限矩阵_组长通_admin越组长通() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        assertThatCode(() -> service.rename(GROUP_ID, OWNER, false, "新名")).doesNotThrowAnyException();
        assertThatCode(() -> service.rename(GROUP_ID, OUTSIDER, true, "管理员改名")).doesNotThrowAnyException();
        verify(groupMapper, org.mockito.Mockito.times(2)).updateById(any(ProjectGroupEntity.class));
    }

    @Test
    void 组不存在或软删_404() {
        when(groupMapper.selectById(999L)).thenReturn(null);
        assertThatThrownBy(() -> service.rename(999L, OWNER, false, "x"))
                .hasMessageContaining("不存在");
        group.setDeleted(1);
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        assertThatThrownBy(() -> service.rename(GROUP_ID, OWNER, false, "x"))
                .hasMessageContaining("不存在");
    }

    @Test
    void 删组_组池非0拒() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        ProjectGroupWalletEntity w = new ProjectGroupWalletEntity();
        w.setGroupId(GROUP_ID);
        w.setBalancePoints(new BigDecimal("5"));
        when(walletMapper.selectByGroupId(GROUP_ID)).thenReturn(w);
        assertThatThrownBy(() -> service.deleteGroup(GROUP_ID, OWNER, false))
                .hasMessageContaining("先回收");
        verify(groupMapper, never()).deleteById(anyLong());
    }

    @Test
    void 加成员_重复入组CONFLICT_用户不存在404() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        when(userMapper.selectById(MEMBER)).thenReturn(null);
        assertThatThrownBy(() -> service.addMember(GROUP_ID, OWNER, false, MEMBER, null))
                .hasMessageContaining("用户不存在");

        User u = new User();
        u.setId(MEMBER);
        when(userMapper.selectById(MEMBER)).thenReturn(u);
        when(memberMapper.selectByGroupUser(GROUP_ID, MEMBER)).thenReturn(member(MEMBER));
        assertThatThrownBy(() -> service.addMember(GROUP_ID, OWNER, false, MEMBER, null))
                .hasMessageContaining("已是组成员");
    }

    @Test
    void 移除成员_组长自身不可移除() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        assertThatThrownBy(() -> service.removeMember(GROUP_ID, OWNER, false, OWNER))
                .hasMessageContaining("组长不可移除");
    }

    @Test
    void 候选搜索_排除组长与已有成员_空关键词50条上限() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        when(memberMapper.selectList(any())).thenReturn(List.of(member(OWNER), member(MEMBER)));
        User cand = new User();
        cand.setId(9L);
        cand.setUsername("cand9");
        when(userMapper.searchActiveCandidates(eq(""), anyList(), eq(50))).thenReturn(List.of(cand));

        var result = service.searchCandidates(GROUP_ID, OWNER, false, "");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).userId()).isEqualTo(9L);
        // 排除集含组长+已有成员
        verify(userMapper).searchActiveCandidates(eq(""), eq(List.of(OWNER, MEMBER)), eq(50));
    }

    @Test
    void findMember_软删组或非成员返null() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        when(memberMapper.selectByGroupUser(GROUP_ID, MEMBER)).thenReturn(member(MEMBER));
        assertThat(service.findMember(GROUP_ID, MEMBER)).isNotNull();

        when(memberMapper.selectByGroupUser(GROUP_ID, OUTSIDER)).thenReturn(null);
        assertThat(service.findMember(GROUP_ID, OUTSIDER)).isNull();
        assertThat(service.findMember(null, MEMBER)).isNull();

        group.setDeleted(1);
        assertThat(service.findMember(GROUP_ID, MEMBER)).isNull();
    }

    // ==================== 17x#1 复活（V139） ====================

    @Test
    void 复活_软删残留行命中_记流水不新插() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        User u = new User();
        u.setId(MEMBER);
        when(userMapper.selectById(MEMBER)).thenReturn(u);
        when(memberMapper.reviveRow(GROUP_ID, MEMBER, null, OWNER)).thenReturn(1);
        ProjectGroupWalletEntity w = new ProjectGroupWalletEntity();
        w.setBalancePoints(new BigDecimal("50"));
        when(walletMapper.selectByGroupId(GROUP_ID)).thenReturn(w);

        service.addMember(GROUP_ID, OWNER, false, MEMBER, null);

        // 复活成功：ADMIN_ADJUST 流水留痕（delta=0，balance_after=组池现值），不再走探针/新插
        org.mockito.ArgumentCaptor<ProjectGroupLedgerEntity> cap =
                org.mockito.ArgumentCaptor.forClass(ProjectGroupLedgerEntity.class);
        verify(ledgerMapper).insert(cap.capture());
        assertThat(cap.getValue().getType()).isEqualTo(ProjectGroupLedgerEntity.TYPE_ADMIN_ADJUST);
        assertThat(cap.getValue().getDeltaPoints()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(cap.getValue().getBalanceAfter()).isEqualByComparingTo(new BigDecimal("50"));
        assertThat(cap.getValue().getRemark()).contains("复活");
        verify(memberMapper, never()).selectByGroupUser(anyLong(), anyLong());
        verify(memberMapper, never()).insert(any(ProjectGroupMemberEntity.class));
    }

    @Test
    void 复活未命中_走探针新插() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        User u = new User();
        u.setId(MEMBER);
        when(userMapper.selectById(MEMBER)).thenReturn(u);
        when(memberMapper.reviveRow(GROUP_ID, MEMBER, BigDecimal.TEN, OWNER)).thenReturn(0);
        when(memberMapper.selectByGroupUser(GROUP_ID, MEMBER)).thenReturn(null);

        service.addMember(GROUP_ID, OWNER, false, MEMBER, BigDecimal.TEN);

        org.mockito.ArgumentCaptor<ProjectGroupMemberEntity> cap =
                org.mockito.ArgumentCaptor.forClass(ProjectGroupMemberEntity.class);
        verify(memberMapper).insert(cap.capture());
        // 新插行角色显式 MEMBER（不依赖 DB 默认值漂移）
        assertThat(cap.getValue().getRole()).isEqualTo(ProjectGroupMemberEntity.ROLE_MEMBER);
        assertThat(cap.getValue().getQuotaLimitPoints()).isEqualByComparingTo(BigDecimal.TEN);
        // A1：配额落行记 MEMBER_ALLOCATE（毛额口径，非资金腿）
        org.mockito.ArgumentCaptor<ProjectGroupLedgerEntity> lcap =
                org.mockito.ArgumentCaptor.forClass(ProjectGroupLedgerEntity.class);
        verify(ledgerMapper).insert(lcap.capture());
        assertThat(lcap.getValue().getType()).isEqualTo(ProjectGroupLedgerEntity.TYPE_MEMBER_ALLOCATE);
        assertThat(lcap.getValue().getDeltaPoints()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(lcap.getValue().getRefType()).isEqualTo(ProjectGroupLedgerEntity.REF_MEMBER);
    }

    // ==================== 17x 层级额度（V156） ====================

    @Test
    void 层级额度_组长给MANAGER行定额度_低于已占用拒() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        ProjectGroupMemberEntity mgrRow = roleRow(MANAGER_UID, ProjectGroupMemberEntity.ROLE_MANAGER);
        when(memberMapper.selectByGroupUser(GROUP_ID, MANAGER_UID)).thenReturn(mgrRow);
        when(memberMapper.selectByGroupUserForUpdate(GROUP_ID, MANAGER_UID)).thenReturn(mgrRow);
        // 已占用 800（mock）：定 5000 通；定 500 拒
        when(budgetService.hasUnboundedChild(GROUP_ID, MANAGER_UID, null)).thenReturn(false);
        when(budgetService.occupied(GROUP_ID, mgrRow, null)).thenReturn(new BigDecimal("800"));

        assertThatCode(() -> service.updateQuota(GROUP_ID, OWNER, false, MANAGER_UID, new BigDecimal("5000")))
                .doesNotThrowAnyException();
        assertThat(mgrRow.getAllocatedByUserId()).isEqualTo(OWNER);
        assertThatThrownBy(() -> service.updateQuota(GROUP_ID, OWNER, false, MANAGER_UID, new BigDecimal("500")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已占用");
    }

    @Test
    void 层级额度_管理配额度_超可分配拒_不限拒() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        ProjectGroupMemberEntity mgrRow = roleRow(MANAGER_UID, ProjectGroupMemberEntity.ROLE_MANAGER);
        mgrRow.setQuotaLimitPoints(new BigDecimal("5000"));
        ProjectGroupMemberEntity memberRow = roleRow(MEMBER, ProjectGroupMemberEntity.ROLE_MEMBER);
        when(memberMapper.selectByGroupUser(GROUP_ID, MANAGER_UID)).thenReturn(mgrRow);
        when(memberMapper.selectByGroupUser(GROUP_ID, MEMBER)).thenReturn(memberRow);
        when(memberMapper.selectByGroupUserForUpdate(GROUP_ID, MANAGER_UID)).thenReturn(mgrRow);

        // null=不限 → 修复IV D2 冻结：组长/管理/不限额管理一律 400「不限额度已停用」（原 V156「不能不限」被前置涵盖）
        assertThatThrownBy(() -> service.updateQuota(GROUP_ID, MANAGER_UID, false, MEMBER, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不限额度已停用");
        // 预算硬卡（mock 抛超发）→ 拒
        org.mockito.Mockito.doThrow(new BusinessException(
                        com.superprogrammer.common.exception.ErrorCode.BAD_REQUEST, "超出你的可分配额度"))
                .when(budgetService).requireWithinBudget(GROUP_ID, mgrRow, memberRow, new BigDecimal("99999"));
        assertThatThrownBy(() -> service.updateQuota(GROUP_ID, MANAGER_UID, false, MEMBER, new BigDecimal("99999")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("可分配");
        // 正常分配 → 通，且 allocated_by=管理自己
        assertThatCode(() -> service.updateQuota(GROUP_ID, MANAGER_UID, false, MEMBER, BigDecimal.TEN))
                .doesNotThrowAnyException();
        assertThat(memberRow.getAllocatedByUserId()).isEqualTo(MANAGER_UID);
    }

    @Test
    void 层级额度_管理降职_下级改挂组长() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        when(memberMapper.selectByGroupUserForUpdate(GROUP_ID, MANAGER_UID))
                .thenReturn(roleRow(MANAGER_UID, ProjectGroupMemberEntity.ROLE_MANAGER));
        when(memberMapper.selectChildren(GROUP_ID, MANAGER_UID)).thenReturn(List.of());
        assertThatCode(() -> service.updateMemberRole(GROUP_ID, OWNER, false, MANAGER_UID,
                ProjectGroupMemberEntity.ROLE_MEMBER)).doesNotThrowAnyException();
        verify(memberMapper).reparentChildren(GROUP_ID, MANAGER_UID, OWNER);
    }

    // ==================== 17x-1 降职缩额 + 成员流水（A1/A2） ====================

    private ProjectGroupMemberEntity managerRow(BigDecimal quota, BigDecimal used) {
        ProjectGroupMemberEntity m = roleRow(MANAGER_UID, ProjectGroupMemberEntity.ROLE_MANAGER);
        m.setQuotaLimitPoints(quota);
        m.setUsedPoints(used);
        return m;
    }

    private ProjectGroupMemberEntity childRow(long userId, BigDecimal quota) {
        ProjectGroupMemberEntity c = roleRow(userId, ProjectGroupMemberEntity.ROLE_MEMBER);
        c.setQuotaLimitPoints(quota);
        c.setUsedPoints(BigDecimal.ZERO);
        c.setAllocatedByUserId(MANAGER_UID);
        return c;
    }

    @Test
    void 降职缩额_三算例_200分100剩100_used150保150_下级不限额取used() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        when(walletMapper.selectByGroupId(GROUP_ID)).thenReturn(null);

        // ① quota=200、下级 B quota=100、used=0 → 缩到 100（200−100），记 MEMBER_RECLAIM(−100)
        ProjectGroupMemberEntity m1 = managerRow(new BigDecimal("200"), BigDecimal.ZERO);
        when(memberMapper.selectByGroupUserForUpdate(GROUP_ID, MANAGER_UID)).thenReturn(m1);
        when(memberMapper.selectChildren(GROUP_ID, MANAGER_UID))
                .thenReturn(List.of(childRow(MEMBER, new BigDecimal("100"))));
        assertThatCode(() -> service.updateMemberRole(GROUP_ID, OWNER, false, MANAGER_UID,
                ProjectGroupMemberEntity.ROLE_MEMBER)).doesNotThrowAnyException();
        assertThat(m1.getQuotaLimitPoints()).isEqualByComparingTo(new BigDecimal("100"));

        // ② quota=200、下级分走 100、已用 150 → max(150, 200−100)=150（已用不回收）
        ProjectGroupMemberEntity m2 = managerRow(new BigDecimal("200"), new BigDecimal("150"));
        when(memberMapper.selectByGroupUserForUpdate(GROUP_ID, MANAGER_UID)).thenReturn(m2);
        assertThatCode(() -> service.updateMemberRole(GROUP_ID, OWNER, false, MANAGER_UID,
                ProjectGroupMemberEntity.ROLE_MEMBER)).doesNotThrowAnyException();
        assertThat(m2.getQuotaLimitPoints()).isEqualByComparingTo(new BigDecimal("150"));

        // ③ quota=200、任一下级不限额（NULL）→ 差额不可算，保守取 used
        ProjectGroupMemberEntity m3 = managerRow(new BigDecimal("200"), new BigDecimal("50"));
        when(memberMapper.selectByGroupUserForUpdate(GROUP_ID, MANAGER_UID)).thenReturn(m3);
        when(memberMapper.selectChildren(GROUP_ID, MANAGER_UID))
                .thenReturn(List.of(childRow(MEMBER, null)));
        assertThatCode(() -> service.updateMemberRole(GROUP_ID, OWNER, false, MANAGER_UID,
                ProjectGroupMemberEntity.ROLE_MEMBER)).doesNotThrowAnyException();
        assertThat(m3.getQuotaLimitPoints()).isEqualByComparingTo(new BigDecimal("50"));

        // ① 触发一次缩额流水：MEMBER_RECLAIM delta=−100、ref=成员 userId、remark 留痕
        org.mockito.ArgumentCaptor<ProjectGroupLedgerEntity> cap =
                org.mockito.ArgumentCaptor.forClass(ProjectGroupLedgerEntity.class);
        verify(ledgerMapper, org.mockito.Mockito.times(3)).insert(cap.capture());
        assertThat(cap.getAllValues().get(0).getType()).isEqualTo(ProjectGroupLedgerEntity.TYPE_MEMBER_RECLAIM);
        assertThat(cap.getAllValues().get(0).getDeltaPoints()).isEqualByComparingTo(new BigDecimal("-100"));
        assertThat(cap.getAllValues().get(0).getRefType()).isEqualTo(ProjectGroupLedgerEntity.REF_MEMBER);
        assertThat(cap.getAllValues().get(0).getRefId()).isEqualTo(String.valueOf(MANAGER_UID));
        assertThat(cap.getAllValues().get(0).getRemark()).contains("管理降职缩额");
    }

    @Test
    void 降职缩额_不限额管理_降职后仍不限_无缩额流水() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        ProjectGroupMemberEntity m = managerRow(null, new BigDecimal("80"));
        when(memberMapper.selectByGroupUserForUpdate(GROUP_ID, MANAGER_UID)).thenReturn(m);
        when(memberMapper.selectChildren(GROUP_ID, MANAGER_UID))
                .thenReturn(List.of(childRow(MEMBER, new BigDecimal("100"))));
        assertThatCode(() -> service.updateMemberRole(GROUP_ID, OWNER, false, MANAGER_UID,
                ProjectGroupMemberEntity.ROLE_MEMBER)).doesNotThrowAnyException();
        assertThat(m.getQuotaLimitPoints()).isNull();
        verify(ledgerMapper, never()).insert(any(ProjectGroupLedgerEntity.class));
    }

    @Test
    void 降职缩额_无下级且无需缩_不落流水() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        // quota=200、无下级、used=0 → max(0, 200−0)=200=原值，无变化无流水
        ProjectGroupMemberEntity m = managerRow(new BigDecimal("200"), BigDecimal.ZERO);
        when(memberMapper.selectByGroupUserForUpdate(GROUP_ID, MANAGER_UID)).thenReturn(m);
        when(memberMapper.selectChildren(GROUP_ID, MANAGER_UID)).thenReturn(List.of());
        assertThatCode(() -> service.updateMemberRole(GROUP_ID, OWNER, false, MANAGER_UID,
                ProjectGroupMemberEntity.ROLE_MEMBER)).doesNotThrowAnyException();
        assertThat(m.getQuotaLimitPoints()).isEqualByComparingTo(new BigDecimal("200"));
        verify(ledgerMapper, never()).insert(any(ProjectGroupLedgerEntity.class));
    }

    @Test
    void 成员流水_调增ALLOCATE_调减RECLAIM_null拒400_不变不落() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        when(walletMapper.selectByGroupId(GROUP_ID)).thenReturn(null);
        ProjectGroupMemberEntity memberRow = roleRow(MEMBER, ProjectGroupMemberEntity.ROLE_MEMBER);

        // 组长调增 50→100：ALLOCATE(+50)
        memberRow.setQuotaLimitPoints(new BigDecimal("50"));
        when(memberMapper.selectByGroupUser(GROUP_ID, MEMBER)).thenReturn(memberRow);
        service.updateQuota(GROUP_ID, OWNER, false, MEMBER, new BigDecimal("100"));
        // 调减 100→40：RECLAIM(−60)
        service.updateQuota(GROUP_ID, OWNER, false, MEMBER, new BigDecimal("40"));
        // 修复IV D2（17x-3）：null=不限 → 400，不再产生 QUOTA_ADJUST「→不限」流水
        assertThatThrownBy(() -> service.updateQuota(GROUP_ID, OWNER, false, MEMBER, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不限额度已停用");
        // 不变（40→40）：不落行
        service.updateQuota(GROUP_ID, OWNER, false, MEMBER, new BigDecimal("40"));

        org.mockito.ArgumentCaptor<ProjectGroupLedgerEntity> cap =
                org.mockito.ArgumentCaptor.forClass(ProjectGroupLedgerEntity.class);
        verify(ledgerMapper, org.mockito.Mockito.times(2)).insert(cap.capture());
        assertThat(cap.getAllValues().get(0).getType()).isEqualTo(ProjectGroupLedgerEntity.TYPE_MEMBER_ALLOCATE);
        assertThat(cap.getAllValues().get(0).getDeltaPoints()).isEqualByComparingTo(new BigDecimal("50"));
        assertThat(cap.getAllValues().get(1).getType()).isEqualTo(ProjectGroupLedgerEntity.TYPE_MEMBER_RECLAIM);
        assertThat(cap.getAllValues().get(1).getDeltaPoints()).isEqualByComparingTo(new BigDecimal("-60"));
    }

    @Test
    void 层级额度_组长行不可调限额() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        when(memberMapper.selectByGroupUser(GROUP_ID, OWNER))
                .thenReturn(roleRow(OWNER, ProjectGroupMemberEntity.ROLE_OWNER));
        assertThatThrownBy(() -> service.updateQuota(GROUP_ID, OWNER, false, OWNER, BigDecimal.TEN))
                .isInstanceOf(BusinessException.class).hasMessageContaining("组长行不可调限额");
    }

    // ==================== 修复IV D3：17x-4 组织信息可见（MEMBER+ 可读 + 单点裁剪） ====================

    private ProjectGroupMemberEntity fullRow(long userId, String role, BigDecimal quota) {
        ProjectGroupMemberEntity m = roleRow(userId, role);
        m.setQuotaLimitPoints(quota);
        m.setUsedPoints(new BigDecimal("12"));
        m.setSelfPoints(new BigDecimal("5"));
        m.setDebtPoolPoints(BigDecimal.ZERO);
        m.setDebtLeaderPoints(BigDecimal.ZERO);
        m.setAllocatedByUserId(OWNER);
        m.setAllowedKinds("[\"CHAT\"]");
        return m;
    }

    private User user(long id, String uname, String remark) {
        User u = new User();
        u.setId(id);
        u.setUsername(uname);
        u.setName(uname + "名");
        u.setRemark(remark);
        return u;
    }

    private List<ProjectGroupMemberEntity> stubDetailMembers() {
        List<ProjectGroupMemberEntity> rows = List.of(
                fullRow(OWNER, ProjectGroupMemberEntity.ROLE_OWNER, null),
                fullRow(MANAGER_UID, ProjectGroupMemberEntity.ROLE_MANAGER, new BigDecimal("5000")),
                fullRow(MEMBER, ProjectGroupMemberEntity.ROLE_MEMBER, new BigDecimal("100")));
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        when(walletMapper.selectByGroupId(GROUP_ID)).thenReturn(null);
        when(walletMapper.sumInflightEstimated(GROUP_ID)).thenReturn(new BigDecimal("7"));
        when(memberMapper.selectList(any())).thenReturn(rows);
        when(userMapper.selectBatchIds(anyList())).thenReturn(List.of(
                user(OWNER, "owner", "组长备注"),
                user(MANAGER_UID, "manager", "管理备注"),
                user(MEMBER, "member", "成员备注")));
        return rows;
    }

    @Test
    void 受限视图_MEMBER可读_他人行额度类裁空_本人行完整_组财务不透出() {
        List<ProjectGroupMemberEntity> rows = stubDetailMembers();
        when(memberMapper.selectByGroupUser(GROUP_ID, MEMBER)).thenReturn(rows.get(2));

        var d = service.getDetail(GROUP_ID, MEMBER, false);

        // 组级财务（管理数据）不透出
        assertThat(d.balancePoints()).isNull();
        assertThat(d.inflightPoints()).isNull();
        assertThat(d.members()).hasSize(3);
        // MANAGER 行=他人：额度/欠款/可分配/分配人/功能开关/可见性覆盖全裁
        var other = d.members().get(1);
        assertThat(other.quotaLimitPoints()).isNull();
        assertThat(other.usedPoints()).isNull();
        assertThat(other.selfPoints()).isNull();
        assertThat(other.debtPoolPoints()).isNull();
        assertThat(other.debtLeaderPoints()).isNull();
        assertThat(other.allocatedByUserId()).isNull();
        assertThat(other.allowedKinds()).isNull();
        assertThat(other.memberVisibilityOverrides()).isNull();
        assertThat(other.allocatablePoints()).isNull();
        // 组织信息保留（决策 6）：username/角色/备注/加入时间
        assertThat(other.username()).isEqualTo("manager");
        assertThat(other.displayName()).isEqualTo("manager名");
        assertThat(other.role()).isEqualTo(ProjectGroupMemberEntity.ROLE_MANAGER);
        assertThat(other.remark()).isEqualTo("管理备注");
        assertThat(other.joinedAt()).isNull(); // 实体未设 createdAt，null 本身即未裁剪信号（裁剪不动该字段）
        // 本人行完整
        var own = d.members().get(2);
        assertThat(own.quotaLimitPoints()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(own.usedPoints()).isEqualByComparingTo(new BigDecimal("12"));
        assertThat(own.selfPoints()).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(own.allowedKinds()).containsExactly("CHAT");
    }

    @Test
    void 受限视图_MANAGER视角_全显不裁() {
        List<ProjectGroupMemberEntity> rows = stubDetailMembers();
        when(memberMapper.selectByGroupUser(GROUP_ID, MANAGER_UID)).thenReturn(rows.get(1));
        lenient().when(budgetService.allocatable(eq(GROUP_ID), any(), any()))
                .thenReturn(new BigDecimal("300"));

        var d = service.getDetail(GROUP_ID, MANAGER_UID, false);

        assertThat(d.balancePoints()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(d.inflightPoints()).isEqualByComparingTo(new BigDecimal("7"));
        var member = d.members().get(2);
        assertThat(member.quotaLimitPoints()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(member.usedPoints()).isEqualByComparingTo(new BigDecimal("12"));
        assertThat(d.members().get(1).allocatablePoints()).isEqualByComparingTo(new BigDecimal("300"));
    }

    @Test
    void 受限视图_组长视角_全显不裁() {
        stubDetailMembers(); // 组长无成员行也通（ownerView 直接放行）

        var d = service.getDetail(GROUP_ID, OWNER, false);

        assertThat(d.balancePoints()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(d.inflightPoints()).isEqualByComparingTo(new BigDecimal("7"));
        assertThat(d.members().get(2).quotaLimitPoints()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(d.members().get(2).allocatedByUserId()).isEqualTo(OWNER);
    }

    @Test
    void 受限视图_admin视角_全显_无需成员行() {
        stubDetailMembers();
        lenient().when(budgetService.allocatable(eq(GROUP_ID), any(), any()))
                .thenReturn(new BigDecimal("300"));

        var d = service.getDetail(GROUP_ID, OUTSIDER, true);

        assertThat(d.balancePoints()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(d.members().get(1).quotaLimitPoints()).isEqualByComparingTo(new BigDecimal("5000"));
        assertThat(d.members().get(2).remark()).isEqualTo("成员备注");
    }
}
