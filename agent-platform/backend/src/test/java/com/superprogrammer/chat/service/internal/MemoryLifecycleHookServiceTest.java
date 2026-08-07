package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.superprogrammer.chat.entity.MemoryConsolidationScope;
import com.superprogrammer.chat.entity.MemoryProjectEntry;
import com.superprogrammer.chat.entity.MemoryProjectMember;
import com.superprogrammer.chat.entity.MemoryProjectRule;
import com.superprogrammer.chat.entity.MemoryProjectSetting;
import com.superprogrammer.chat.entity.MemoryProjectUserSetting;
import com.superprogrammer.chat.entity.MemorySummary;
import com.superprogrammer.chat.entity.MemorySummaryCoverage;
import com.superprogrammer.chat.mapper.MemoryConsolidationScopeMapper;
import com.superprogrammer.chat.mapper.MemoryProjectEntryMapper;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import com.superprogrammer.chat.mapper.MemoryProjectRuleMapper;
import com.superprogrammer.chat.mapper.MemoryProjectSettingMapper;
import com.superprogrammer.chat.mapper.MemoryProjectUserSettingMapper;
import com.superprogrammer.chat.mapper.MemorySummaryCoverageMapper;
import com.superprogrammer.chat.mapper.MemorySummaryMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 计划12 · 生命周期写侧 hook · MemoryLifecycleHookService 单测（Mockito，§3.7）。
 * <p>
 * 二期 P1（V67）：turns 纯个人域——离职/删项目的 turns 标记 + PROJECT_DELETED_AFFECTED 通知
 * 随四列下线；项目删除级联新增收录规则/条目清理。覆盖：
 * <ol>
 *   <li>项目创建/成员加入：无行插 ACTIVE 行；角色映射 OWNER→OWNER、EDITOR/VIEWER→MEMBER。</li>
 *   <li>重加入：DEPARTED 行回 ACTIVE + 清 departed_at + recall_admin 保留。</li>
 *   <li>成员离职：ACTIVE→DEPARTED + departed_at；无行直接落 DEPARTED 行；已 DEPARTED 幂等。</li>
 *   <li>项目删除：总结软删/coverage/成员行/scope/开关/<b>收录规则/条目</b>清；
 *       无总结时不空调 softDeleteByIds。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class MemoryLifecycleHookServiceTest {

    @Mock MemoryProjectMemberMapper memberMapper;
    @Mock MemorySummaryMapper summaryMapper;
    @Mock MemorySummaryCoverageMapper coverageMapper;
    @Mock MemoryConsolidationScopeMapper consolidationScopeMapper;
    @Mock MemoryProjectSettingMapper projectSettingMapper;
    @Mock MemoryProjectUserSettingMapper projectUserSettingMapper;
    @Mock MemoryProjectRuleMapper projectRuleMapper;
    @Mock MemoryProjectEntryMapper projectEntryMapper;

    private MemoryLifecycleHookService service;

    /** 填充 MP lambda 缓存（LambdaQueryWrapper 解析 SFunction → 列名）。 */
    @BeforeAll
    static void initTableInfo() {
        Configuration cfg = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(cfg, "");
        TableInfoHelper.initTableInfo(assistant, MemoryProjectMember.class);
        TableInfoHelper.initTableInfo(assistant, MemorySummary.class);
        TableInfoHelper.initTableInfo(assistant, MemorySummaryCoverage.class);
        TableInfoHelper.initTableInfo(assistant, MemoryConsolidationScope.class);
        TableInfoHelper.initTableInfo(assistant, MemoryProjectSetting.class);
        TableInfoHelper.initTableInfo(assistant, MemoryProjectUserSetting.class);
        TableInfoHelper.initTableInfo(assistant, MemoryProjectRule.class);
        TableInfoHelper.initTableInfo(assistant, MemoryProjectEntry.class);
    }

    @BeforeEach
    void setUp() {
        service = new MemoryLifecycleHookService(memberMapper, summaryMapper, coverageMapper,
                consolidationScopeMapper, projectSettingMapper, projectUserSettingMapper,
                projectRuleMapper, projectEntryMapper);
    }

    private MemoryProjectMember memberRow(String role, String status) {
        MemoryProjectMember m = new MemoryProjectMember();
        m.setProjectId(100L);
        m.setUserId(1L);
        m.setRole(role);
        m.setRecallAdmin(true);
        m.setStatus(status);
        return m;
    }

    // ===== 项目创建 / 成员加入 =====

    @Test
    void onProjectCreated_noRow_insertsOwnerActive() {
        when(memberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.onProjectCreated(100L, 1L);

        verify(memberMapper).insert(argThat(m ->
                m.getProjectId().equals(100L) && m.getUserId().equals(1L)
                        && "OWNER".equals(m.getRole()) && "ACTIVE".equals(m.getStatus())
                        && Boolean.FALSE.equals(m.getRecallAdmin())
                        && m.getCreatedAt() != null && m.getUpdatedAt() != null));
    }

    @Test
    void onMemberAdded_noRow_mapsViewerToMember() {
        when(memberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.onMemberAdded(100L, 1L, "VIEWER");

        verify(memberMapper).insert(argThat(m -> "MEMBER".equals(m.getRole()) && "ACTIVE".equals(m.getStatus())));
    }

    @Test
    void onMemberAdded_departedRow_reactivatesKeepsRecallAdmin() {
        MemoryProjectMember departed = memberRow("MEMBER", "DEPARTED");
        departed.setDepartedAt(java.time.OffsetDateTime.now().minusDays(3));
        when(memberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(departed);

        service.onMemberAdded(100L, 1L, "EDITOR");

        // 走 UpdateWrapper 显式 set departed_at=null（updateById 的 NOT_NULL 策略会丢 null，IT 暴露）；
        // recall_admin 不在 set 列 → 保留
        verify(memberMapper).update(isNull(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class));
        verify(memberMapper, never()).insert(any(MemoryProjectMember.class));
    }

    @Test
    void onMemberAdded_activeSameRole_noop() {
        when(memberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(memberRow("MEMBER", "ACTIVE"));

        service.onMemberAdded(100L, 1L, "VIEWER");

        verify(memberMapper, never()).insert(any(MemoryProjectMember.class));
        verify(memberMapper, never()).updateById(any(MemoryProjectMember.class));
    }

    // ===== 成员离职（二期 P1：turns 无动作） =====

    @Test
    void onMemberDeparted_activeRow_marksDeparted() {
        when(memberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(memberRow("MEMBER", "ACTIVE"));

        service.onMemberDeparted(100L, 1L, "VIEWER");

        verify(memberMapper).updateById(argThat(m ->
                "DEPARTED".equals(m.getStatus()) && m.getDepartedAt() != null));
        verify(memberMapper, never()).insert(any(MemoryProjectMember.class));
    }

    @Test
    void onMemberDeparted_noRow_insertsDepartedRow() {
        when(memberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.onMemberDeparted(100L, 1L, "EDITOR");

        verify(memberMapper).insert(argThat(m ->
                "DEPARTED".equals(m.getStatus()) && m.getDepartedAt() != null && "MEMBER".equals(m.getRole())));
    }

    @Test
    void onMemberDeparted_alreadyDeparted_skipsUpdate() {
        when(memberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(memberRow("MEMBER", "DEPARTED"));

        service.onMemberDeparted(100L, 1L, "VIEWER");

        verify(memberMapper, never()).updateById(any(MemoryProjectMember.class));
        verify(memberMapper, never()).insert(any(MemoryProjectMember.class));
    }

    // ===== 项目删除 =====

    @Test
    void onProjectDeleted_fullCascade() {
        MemorySummary s = new MemorySummary();
        s.setId(55L);
        when(summaryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(s));
        when(summaryMapper.softDeleteByIds(List.of(55L))).thenReturn(1);

        service.onProjectDeleted(100L);

        // 总结软删 + 各表按 project_id 清（二期 P1 补：收录规则 + 收录条目）
        verify(summaryMapper).softDeleteByIds(List.of(55L));
        verify(coverageMapper).delete(any(LambdaQueryWrapper.class));
        verify(memberMapper).delete(any(LambdaQueryWrapper.class));
        verify(consolidationScopeMapper).delete(any(LambdaQueryWrapper.class));
        verify(projectSettingMapper).delete(any(LambdaQueryWrapper.class));
        verify(projectUserSettingMapper).delete(any(LambdaQueryWrapper.class));
        verify(projectRuleMapper).delete(any(LambdaQueryWrapper.class));
        verify(projectEntryMapper).delete(any(LambdaQueryWrapper.class));
    }

    @Test
    void onProjectDeleted_noSummaries_skipsSoftDelete() {
        when(summaryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        service.onProjectDeleted(100L);

        verify(summaryMapper, never()).softDeleteByIds(anyList());
        // 清理仍执行（幂等 0 行）
        verify(memberMapper).delete(any(LambdaQueryWrapper.class));
        verify(consolidationScopeMapper).delete(any(LambdaQueryWrapper.class));
        verify(projectRuleMapper).delete(any(LambdaQueryWrapper.class));
        verify(projectEntryMapper).delete(any(LambdaQueryWrapper.class));
    }
}
