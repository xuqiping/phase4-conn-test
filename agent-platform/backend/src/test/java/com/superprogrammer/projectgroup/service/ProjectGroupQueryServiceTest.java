package com.superprogrammer.projectgroup.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.billing.entity.LlmUsageLogEntity;
import com.superprogrammer.billing.mapper.LlmUsageLogMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.result.PageResult;
import com.superprogrammer.media.entity.MediaGenTask;
import com.superprogrammer.media.mapper.MediaGenTaskMapper;
import com.superprogrammer.projectgroup.dto.ProjectGroupDetailVO;
import com.superprogrammer.projectgroup.dto.ProjectGroupOutputVO;
import com.superprogrammer.projectgroup.dto.ProjectGroupOverviewVO;
import com.superprogrammer.projectgroup.entity.ProjectGroupEntity;
import com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity;
import com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity;
import com.superprogrammer.projectgroup.mapper.ProjectGroupLedgerMapper;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMapper;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMemberMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step7 推进查询单测（plan 验证项）：overview 复用 requireOwner + 流水分页用户名补齐；
 * outputs 可见性（组长全员+筛选 / 成员强制 self / 非成员 403）+ 媒体概要 JOIN 批查。
 */
@ExtendWith(MockitoExtension.class)
class ProjectGroupQueryServiceTest {

    /** getSqlSegment/getParamNameValuePairs 断言需 TableInfo 缓存（纯单测无 Spring 容器，手动初始化——UserControllerRemarkTest 同款） */
    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                ProjectGroupLedgerEntity.class);
    }

    private static final long GROUP_ID = 10L;
    private static final long OWNER = 1L;
    private static final long MEMBER = 2L;
    private static final long OTHER_MEMBER = 3L;
    private static final long OUTSIDER = 9L;

    @Mock private ProjectGroupMapper groupMapper;
    @Mock private ProjectGroupMemberMapper memberMapper;
    @Mock private ProjectGroupLedgerMapper ledgerMapper;
    @Mock private LlmUsageLogMapper usageLogMapper;
    @Mock private MediaGenTaskMapper mediaTaskMapper;
    @Mock private UserMapper userMapper;
    @Mock private ProjectGroupService groupService;
    @Mock private ProjectGroupVisibilityService visibilityService;

    @InjectMocks
    private ProjectGroupQueryService service;

    private ProjectGroupEntity group;

    @BeforeEach
    void setUp() {
        group = new ProjectGroupEntity();
        group.setId(GROUP_ID);
        group.setOwnerUserId(OWNER);
        group.setDeleted(0);
        lenient().when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        // V138 可见性服务默认放行（本类只验 outputs 编排，可见性规则自身在 VisibilityServiceTest 覆盖）
        // V139：outputs 改走预取版 canSeeOutputResolved（7 参），旧 5 参不再被本类调用
        lenient().when(visibilityService.canSeeOutputResolved(any(), any(), eq(false), any(), any(), any(), any()))
                .thenReturn(true);
        lenient().when(visibilityService.visibleAllKindsForMember(any()))
                .thenReturn(List.of("CHAT", "EMBED", "RERANK", "IMAGE", "VIDEO"));
    }

    private ProjectGroupMemberEntity memberRow(long uid) {
        ProjectGroupMemberEntity m = new ProjectGroupMemberEntity();
        m.setGroupId(GROUP_ID);
        m.setUserId(uid);
        return m;
    }

    private LlmUsageLogEntity usage(long id, long uid, String kind, Long taskId) {
        LlmUsageLogEntity u = new LlmUsageLogEntity();
        u.setId(id);
        u.setUserId(uid);
        u.setKind(kind);
        u.setTaskId(taskId);
        u.setPointsConsumed(new BigDecimal("1.5"));
        u.setStatus("SUCCESS");
        return u;
    }

    private User user(long id, String name) {
        User u = new User();
        u.setId(id);
        u.setUsername(name);
        return u;
    }

    // ==================== overview ====================

    @Test
    void overview_delegatesDetailAndPagesLedgerWithUsernames() {
        ProjectGroupDetailVO detail = new ProjectGroupDetailVO(
                GROUP_ID, "组A", null, OWNER, "owner", new BigDecimal("100"), BigDecimal.ZERO,
                List.of(), OffsetDateTime.now(), "OWN", null, false);
        when(groupService.getDetail(GROUP_ID, OWNER, false)).thenReturn(detail);

        ProjectGroupLedgerEntity l = new ProjectGroupLedgerEntity();
        l.setId(7L);
        l.setGroupId(GROUP_ID);
        l.setActorUserId(OWNER);
        l.setType(ProjectGroupLedgerEntity.TYPE_ALLOCATE);
        l.setDeltaPoints(new BigDecimal("100"));
        l.setBalanceAfter(new BigDecimal("100"));
        Page<ProjectGroupLedgerEntity> page = new Page<>(1, 10);
        page.setRecords(List.of(l));
        page.setTotal(1);
        when(ledgerMapper.selectPage(any(), any())).thenReturn(page);
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(user(OWNER, "owner")));

        ProjectGroupOverviewVO vo = service.overview(GROUP_ID, OWNER, false, null, null, null, null, null, 1, 10);

        assertThat(vo.group().id()).isEqualTo(GROUP_ID);
        assertThat(vo.ledger().getRecords()).hasSize(1);
        assertThat(vo.ledger().getRecords().get(0).actorUsername()).isEqualTo("owner");
        assertThat(vo.ledger().getTotal()).isEqualTo(1);
        // 组长校验复用 getDetail 内 requireOwner（不在此重复断言 403 场景）
        verify(groupService).getDetail(GROUP_ID, OWNER, false);
    }

    @Test
    void overview_capsPageSizeAt50() {
        when(groupService.getDetail(anyLong(), anyLong(), eq(false)))
                .thenReturn(new ProjectGroupDetailVO(GROUP_ID, "g", null, OWNER, "o",
                        BigDecimal.ZERO, BigDecimal.ZERO, List.of(), OffsetDateTime.now(), "OWN", null, false));
        Page<ProjectGroupLedgerEntity> p = new Page<>(1, 500);
        p.setRecords(List.of());
        p.setTotal(0);
        ArgumentCaptor<Page<ProjectGroupLedgerEntity>> cap = ArgumentCaptor.forClass(Page.class);
        when(ledgerMapper.selectPage(cap.capture(), any())).thenReturn(p);

        service.overview(GROUP_ID, OWNER, false, null, null, null, null, null, 1, 500);

        assertThat(cap.getValue().getSize()).isEqualTo(50);
    }

    // ==================== 修复IV D3：17x-4 overview 成员同口径裁剪 ====================

    @Test
    void overview_memberView_ledgerOwnRowsOnly_balanceAfterNulled() {
        when(groupService.getDetail(GROUP_ID, MEMBER, false)).thenReturn(new ProjectGroupDetailVO(
                GROUP_ID, "组A", null, OWNER, "owner", null, null,
                List.of(), OffsetDateTime.now(), "OWN", null, false));
        ProjectGroupMemberEntity viewer = memberRow(MEMBER);
        viewer.setRole(ProjectGroupMemberEntity.ROLE_MEMBER);
        when(memberMapper.selectByGroupUser(GROUP_ID, MEMBER)).thenReturn(viewer);
        ProjectGroupLedgerEntity l = new ProjectGroupLedgerEntity();
        l.setId(7L);
        l.setGroupId(GROUP_ID);
        l.setActorUserId(MEMBER);
        l.setType(ProjectGroupLedgerEntity.TYPE_MEMBER_ALLOCATE);
        l.setDeltaPoints(BigDecimal.TEN);
        l.setBalanceAfter(new BigDecimal("90"));
        Page<ProjectGroupLedgerEntity> page = new Page<>(1, 10);
        page.setRecords(List.of(l));
        page.setTotal(1);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProjectGroupLedgerEntity>> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        when(ledgerMapper.selectPage(any(), cap.capture())).thenReturn(page);
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(user(MEMBER, "m2")));

        ProjectGroupOverviewVO vo = service.overview(GROUP_ID, MEMBER, false, null, null, null, null, null, 1, 10);

        // 成员视角：流水行 balanceAfter（组余额快照）不透出
        assertThat(vo.ledger().getRecords()).hasSize(1);
        assertThat(vo.ledger().getRecords().get(0).balanceAfter()).isNull();
        assertThat(vo.ledger().getRecords().get(0).actorUsername()).isEqualTo("m2");
    }

    @Test
    void overview_managerView_fullLedgerWithBalance() {
        when(groupService.getDetail(GROUP_ID, MEMBER, false)).thenReturn(new ProjectGroupDetailVO(
                GROUP_ID, "组A", null, OWNER, "owner", BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(), OffsetDateTime.now(), "OWN", null, false));
        ProjectGroupMemberEntity mgr = memberRow(MEMBER);
        mgr.setRole(ProjectGroupMemberEntity.ROLE_MANAGER);
        when(memberMapper.selectByGroupUser(GROUP_ID, MEMBER)).thenReturn(mgr);
        ProjectGroupLedgerEntity l = new ProjectGroupLedgerEntity();
        l.setId(7L);
        l.setGroupId(GROUP_ID);
        l.setActorUserId(OWNER);
        l.setType(ProjectGroupLedgerEntity.TYPE_ALLOCATE);
        l.setDeltaPoints(BigDecimal.TEN);
        l.setBalanceAfter(new BigDecimal("90"));
        Page<ProjectGroupLedgerEntity> page = new Page<>(1, 10);
        page.setRecords(List.of(l));
        page.setTotal(1);
        when(ledgerMapper.selectPage(any(), any())).thenReturn(page);
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(user(OWNER, "owner")));

        ProjectGroupOverviewVO vo = service.overview(GROUP_ID, MEMBER, false, null, null, null, null, null, 1, 10);

        // 管理视角：他人行也可见且 balanceAfter 透出（不回归）
        assertThat(vo.ledger().getRecords()).hasSize(1);
        assertThat(vo.ledger().getRecords().get(0).balanceAfter()).isEqualByComparingTo(new BigDecimal("90"));
    }

    // ==================== 修复V B1：流水筛选（17x#1） ====================

    @Test
    void overview_managerView_invalidType_throws400() {
        when(groupService.getDetail(anyLong(), anyLong(), eq(false)))
                .thenReturn(new ProjectGroupDetailVO(GROUP_ID, "g", null, OWNER, "o",
                        BigDecimal.ZERO, BigDecimal.ZERO, List.of(), OffsetDateTime.now(), "OWN", null, false));

        assertThatThrownBy(() -> service.overview(GROUP_ID, OWNER, false,
                null, "DROP_ALL", null, null, null, 1, 10))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法流水类型");
        verify(ledgerMapper, never()).selectPage(any(), any());
    }

    @Test
    void overview_memberView_ignoresFilters_evenInvalidType() {
        // 成员路径忽略筛选参数（后端强制 self，不信任前端）：非法 type 也不 400
        when(groupService.getDetail(GROUP_ID, MEMBER, false)).thenReturn(new ProjectGroupDetailVO(
                GROUP_ID, "组A", null, OWNER, "owner", null, null,
                List.of(), OffsetDateTime.now(), "OWN", null, false));
        ProjectGroupMemberEntity viewer = memberRow(MEMBER);
        viewer.setRole(ProjectGroupMemberEntity.ROLE_MEMBER);
        when(memberMapper.selectByGroupUser(GROUP_ID, MEMBER)).thenReturn(viewer);
        Page<ProjectGroupLedgerEntity> p = new Page<>(1, 10);
        p.setRecords(List.of());
        p.setTotal(0);
        when(ledgerMapper.selectPage(any(), any())).thenReturn(p);

        org.assertj.core.api.Assertions.assertThatCode(() ->
                service.overview(GROUP_ID, MEMBER, false, "kw", "NOT_A_TYPE", 1L, null, null, 1, 10))
                .doesNotThrowAnyException();
    }

    @Test
    void overview_managerView_keywordFilter_parameterizedAndEscaped() {
        when(groupService.getDetail(anyLong(), anyLong(), eq(false)))
                .thenReturn(new ProjectGroupDetailVO(GROUP_ID, "g", null, OWNER, "o",
                        BigDecimal.ZERO, BigDecimal.ZERO, List.of(), OffsetDateTime.now(), "OWN", null, false));
        Page<ProjectGroupLedgerEntity> p = new Page<>(1, 10);
        p.setRecords(List.of());
        p.setTotal(0);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProjectGroupLedgerEntity>> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        when(ledgerMapper.selectPage(any(), cap.capture())).thenReturn(p);

        service.overview(GROUP_ID, OWNER, false, "a%b_\\c", null, 5L, null, null, 1, 10);

        // 子查询框架进 SQL；LIKE 值走参数（不拼用户输入——SQL 段无明文关键词）
        assertThat(cap.getValue().getSqlSegment()).contains("IN (SELECT id FROM users");
        assertThat(cap.getValue().getSqlSegment()).doesNotContain("a%b");
        // 参数值已完成 \ % _ 转义（\ % _ 各自加反斜杠前缀）
        assertThat(cap.getValue().getParamNameValuePairs().values())
                .contains("a\\%b\\_\\\\c");
    }

    // ==================== 修复V B2：CSV 导出（17x#1） ====================

    @Test
    void exportLedger_memberForbidden() {
        ProjectGroupMemberEntity viewer = memberRow(MEMBER);
        viewer.setRole(ProjectGroupMemberEntity.ROLE_MEMBER);
        when(memberMapper.selectByGroupUser(GROUP_ID, MEMBER)).thenReturn(viewer);

        assertThatThrownBy(() -> service.exportLedger(GROUP_ID, MEMBER, false, null, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅组长/管理员可导出");
        verify(ledgerMapper, never()).selectList(any());
    }

    @Test
    void exportLedger_ownerCsv_bomHeaderLabelsEscaping() {
        ProjectGroupLedgerEntity l = new ProjectGroupLedgerEntity();
        l.setId(7L);
        l.setGroupId(GROUP_ID);
        l.setActorUserId(OWNER);
        l.setCreatedAt(OffsetDateTime.parse("2026-08-27T10:00:00+08:00"));
        l.setType(ProjectGroupLedgerEntity.TYPE_MEMBER_ALLOCATE);
        l.setDeltaPoints(new BigDecimal("1.50"));
        l.setBalanceAfter(new BigDecimal("98.500"));
        l.setRefType("media_task");
        l.setRefId("51");
        l.setRemark("含,引号\"换行\n第二行");
        when(ledgerMapper.selectList(any())).thenReturn(List.of(l));
        User u = user(OWNER, "owner");
        u.setName("张三");
        u.setRemark("VIP客户");
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(u));

        byte[] csv = service.exportLedger(GROUP_ID, OWNER, false, null, null, null, null, null);

        String s = new String(csv, java.nio.charset.StandardCharsets.UTF_8);
        // BOM 三字节
        assertThat(csv[0] & 0xFF).isEqualTo(0xEF);
        assertThat(csv[1] & 0xFF).isEqualTo(0xBB);
        assertThat(csv[2] & 0xFF).isEqualTo(0xBF);
        assertThat(s).startsWith("﻿时间,类型,操作人,变动积分,变动后组池余额,关联,备注\r\n");
        assertThat(s).contains("配额划入");                       // 14 种类型中文标签
        assertThat(s).contains("张三（owner）·VIP客户");          // 操作人三段
        assertThat(s).contains("1.5").contains("98.5");           // 去尾零
        assertThat(s).contains("media_task#51");                  // 关联
        assertThat(s).contains("\"含,引号\"\"换行\n第二行\"");     // RFC4180：包裹+引号翻倍
    }

    @Test
    void exportLedger_overLimit_truncatesWithNote() {
        // 常量 50000 内联编译，直接造 50001 行真跑（内存/耗时单测可承受）
        java.util.List<ProjectGroupLedgerEntity> rows = new java.util.ArrayList<>();
        for (int i = 0; i < service.EXPORT_ROW_LIMIT + 1; i++) {
            ProjectGroupLedgerEntity l = new ProjectGroupLedgerEntity();
            l.setId((long) i);
            l.setGroupId(GROUP_ID);
            l.setType(ProjectGroupLedgerEntity.TYPE_ALLOCATE);
            l.setDeltaPoints(BigDecimal.ONE);
            rows.add(l);
        }
        when(ledgerMapper.selectList(any())).thenReturn(rows);
        lenient().when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of()); // 行无 actor → 不触发批查
        when(ledgerMapper.selectCount(any())).thenReturn((long) service.EXPORT_ROW_LIMIT + 1);

        byte[] csv = service.exportLedger(GROUP_ID, OWNER, false, null, null, null, null, null);
        String s = new String(csv, java.nio.charset.StandardCharsets.UTF_8);

        assertThat(s).endsWith("# 截断：共命中 50001 行，仅导出前 50000 行\r\n");
        // 数据行 = 50000（+表头+注记行）
        assertThat(s.split("\r\n")).hasSize(50_002);
    }

    // ==================== outputs 可见性 ====================

    @Test
    void outputs_ownerSeesAllWithFilters() {
        when(memberMapper.selectByGroupUser(GROUP_ID, OWNER)).thenReturn(memberRow(OWNER));
        Page<LlmUsageLogEntity> p = new Page<>(1, 10);
        p.setRecords(List.of(usage(1, MEMBER, "VIDEO", 5L)));
        p.setTotal(1);
        when(usageLogMapper.selectPage(any(), any())).thenReturn(p);
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(user(MEMBER, "m2")));
        MediaGenTask t = new MediaGenTask();
        t.setId(5L);
        t.setStatus("SUCCEEDED");
        t.setRequestConfig("{\"prompt\":\"猫\"}");
        when(mediaTaskMapper.selectBatchIds(anyCollection())).thenReturn(List.of(t));

        PageResult<ProjectGroupOutputVO> out = service.outputs(
                GROUP_ID, OWNER, false, null, "VIDEO", null, null, 1, 10);

        assertThat(out.getRecords()).hasSize(1);
        ProjectGroupOutputVO row = out.getRecords().get(0);
        assertThat(row.username()).isEqualTo("m2");
        assertThat(row.taskId()).isEqualTo(5L);
        assertThat(row.mediaStatus()).isEqualTo("SUCCEEDED");
        assertThat(row.mediaPrompt()).isEqualTo("猫");
    }

    @Test
    void outputs_memberForcedToSelfRows() {
        when(memberMapper.selectByGroupUser(GROUP_ID, MEMBER)).thenReturn(memberRow(MEMBER));
        Page<LlmUsageLogEntity> p = new Page<>(1, 10);
        p.setRecords(List.of(usage(2, MEMBER, "CHAT", null)));
        p.setTotal(1);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LlmUsageLogEntity>> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        when(usageLogMapper.selectPage(any(), cap.capture())).thenReturn(p);
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(user(MEMBER, "m2")));

        // 成员传 memberUserId=他人：应被强制覆盖为 self
        PageResult<ProjectGroupOutputVO> out = service.outputs(
                GROUP_ID, MEMBER, false, OTHER_MEMBER, null, null, null, 1, 10);

        assertThat(out.getRecords()).hasSize(1);
        assertThat(out.getRecords().get(0).userId()).isEqualTo(MEMBER);
        // CHAT 行无任务概要
        assertThat(out.getRecords().get(0).mediaStatus()).isNull();
    }

    @Test
    void outputs_outsiderForbidden() {
        when(memberMapper.selectByGroupUser(GROUP_ID, OUTSIDER)).thenReturn(null);

        assertThatThrownBy(() -> service.outputs(GROUP_ID, OUTSIDER, false, null, null, null, null, 1, 10))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非本项目组成员");
        verify(usageLogMapper, never()).selectPage(any(), any());
    }

    @Test
    void outputs_deletedGroupNotFound() {
        group.setDeleted(1);

        assertThatThrownBy(() -> service.outputs(GROUP_ID, OWNER, false, null, null, null, null, 1, 10))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("项目组不存在");
    }

    // ==================== 17x#2 成员级覆盖（V139） ====================

    @Test
    void outputs_memberOverride_prefilterWidens_memoryFiltersExactly() {
        when(memberMapper.selectByGroupUser(GROUP_ID, MEMBER)).thenReturn(memberRow(MEMBER));
        // 组级无 ALL 模块（默认 OWN），成员覆盖里有 VIDEO=ALL → 预过滤放宽含 VIDEO
        when(visibilityService.visibleAllKindsForMember(any())).thenReturn(List.of());
        when(memberMapper.selectList(any()))
                .thenReturn(List.of(memberRow(MEMBER), memberRow(OTHER_MEMBER)));
        when(visibilityService.kindsAnyMemberOverrideAll(any())).thenReturn(List.of("VIDEO"));
        Page<LlmUsageLogEntity> p = new Page<>(1, 10);
        p.setRecords(List.of(usage(1, OTHER_MEMBER, "VIDEO", null), usage(2, OTHER_MEMBER, "IMAGE", null)));
        p.setTotal(2);
        when(usageLogMapper.selectPage(any(), any())).thenReturn(p);
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(user(OTHER_MEMBER, "m3")));
        // 行级精判：归属人覆盖 VIDEO=ALL 放行 / IMAGE 无覆盖落组默认 OWN 拒
        when(visibilityService.canSeeOutputResolved(any(), eq(MEMBER), eq(false), eq("VIDEO"), eq(OTHER_MEMBER), any(), any()))
                .thenReturn(true);
        when(visibilityService.canSeeOutputResolved(any(), eq(MEMBER), eq(false), eq("IMAGE"), eq(OTHER_MEMBER), any(), any()))
                .thenReturn(false);

        PageResult<ProjectGroupOutputVO> out = service.outputs(
                GROUP_ID, MEMBER, false, null, null, null, null, 1, 10);

        assertThat(out.getRecords()).hasSize(1);
        assertThat(out.getRecords().get(0).kind()).isEqualTo("VIDEO");
    }

    @Test
    void outputs_managerSeesAllLikeOwner() {
        ProjectGroupMemberEntity mgr = memberRow(MEMBER);
        mgr.setRole("MANAGER");
        when(memberMapper.selectByGroupUser(GROUP_ID, MEMBER)).thenReturn(mgr);
        Page<LlmUsageLogEntity> p = new Page<>(1, 10);
        p.setRecords(List.of(usage(1, OTHER_MEMBER, "CHAT", null)));
        p.setTotal(1);
        when(usageLogMapper.selectPage(any(), any())).thenReturn(p);
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(user(OTHER_MEMBER, "m3")));

        // MANAGER 视同组长：不强制 self、不走行级过滤
        PageResult<ProjectGroupOutputVO> out = service.outputs(
                GROUP_ID, MEMBER, false, null, null, null, null, 1, 10);

        assertThat(out.getRecords()).hasSize(1);
        assertThat(out.getRecords().get(0).userId()).isEqualTo(OTHER_MEMBER);
        verify(visibilityService, never()).canSeeOutputResolved(any(), any(), eq(false), any(), any(), any(), any());
    }
}
