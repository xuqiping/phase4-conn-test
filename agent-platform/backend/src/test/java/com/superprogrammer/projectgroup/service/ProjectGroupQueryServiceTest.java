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
                List.of(), OffsetDateTime.now());
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

        ProjectGroupOverviewVO vo = service.overview(GROUP_ID, OWNER, false, 1, 10);

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
                        BigDecimal.ZERO, BigDecimal.ZERO, List.of(), OffsetDateTime.now()));
        Page<ProjectGroupLedgerEntity> p = new Page<>(1, 500);
        p.setRecords(List.of());
        p.setTotal(0);
        ArgumentCaptor<Page<ProjectGroupLedgerEntity>> cap = ArgumentCaptor.forClass(Page.class);
        when(ledgerMapper.selectPage(cap.capture(), any())).thenReturn(p);

        service.overview(GROUP_ID, OWNER, false, 1, 500);

        assertThat(cap.getValue().getSize()).isEqualTo(50);
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
}
