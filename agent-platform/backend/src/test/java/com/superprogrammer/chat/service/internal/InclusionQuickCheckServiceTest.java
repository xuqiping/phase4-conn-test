package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.superprogrammer.chat.entity.MemoryProjectMember;
import com.superprogrammer.chat.entity.MemoryProjectRule;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import com.superprogrammer.project.entity.Project;
import com.superprogrammer.project.mapper.ProjectMapper;
import com.superprogrammer.system.service.SystemSettingService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 5x #7 · 收录命中确定性快检单测（纯 Mockito，承 MemoryProjectRuleServiceTest 范式）。
 */
@ExtendWith(MockitoExtension.class)
class InclusionQuickCheckServiceTest {

    @Mock private MemoryProjectMemberMapper memberMapper;
    @Mock private MemoryProjectRuleService ruleService;
    @Mock private MemoryGenToggleService toggleService;
    @Mock private ProjectMapper projectMapper;
    @Mock private SystemSettingService systemSettingService;

    private InclusionQuickCheckService service;

    @BeforeAll
    static void initTableInfo() {
        Configuration cfg = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(cfg, "");
        TableInfoHelper.initTableInfo(assistant, MemoryProjectMember.class);
    }

    @BeforeEach
    void setUp() {
        service = new InclusionQuickCheckService(memberMapper, ruleService, toggleService,
                projectMapper, systemSettingService);
    }

    private static MemoryProjectMember member(Long projectId) {
        MemoryProjectMember m = new MemoryProjectMember();
        m.setProjectId(projectId);
        m.setUserId(100L);
        m.setRole("MEMBER");
        m.setStatus("ACTIVE");
        return m;
    }

    private static MemoryProjectRule rule(Long id, Long projectId, List<String> patterns, OffsetDateTime createdAt) {
        MemoryProjectRule r = new MemoryProjectRule();
        r.setId(id);
        r.setProjectId(projectId);
        r.setFilenamePatterns(patterns);
        r.setEnabled(true);
        r.setCreatedAt(createdAt);
        return r;
    }

    // ---- 1. 命中：附件名匹配 pattern（大小写/trim 归一），项目名回填，先建规则在前 ----

    @Test
    void hitReturnsProjectNameAndPattern() {
        when(memberMapper.selectList(any())).thenReturn(List.of(member(9L)));
        when(toggleService.resolveGenEnabled(100L, 9L)).thenReturn(true);
        MemoryProjectRule r = rule(5L, 9L, List.of(" PDF课件 "), OffsetDateTime.now().minusDays(1));
        when(ruleService.findRoutingCandidates(List.of(9L))).thenReturn(List.of(r));
        Project p = new Project();
        p.setId(9L);
        p.setName("AA老师项目");
        when(projectMapper.selectBatchIds(anyCollection())).thenReturn(List.of(p));

        List<InclusionQuickCheckService.Hit> hits = service.quickCheck(100L, List.of("期中PDF课件.zip"));

        assertEquals(1, hits.size());
        assertEquals(9L, hits.get(0).projectId());
        assertEquals("AA老师项目", hits.get(0).projectName());
        assertEquals("PDF课件", hits.get(0).matchedPattern(), "trim 后原 pattern");
        assertEquals("期中PDF课件.zip", hits.get(0).matchedFile());
    }

    // ---- 2. 多项目命中：先建规则（createdAt 升序）在前，每项目至多一条 ----

    @Test
    void multiHitEarliestRuleFirstPerProjectOnce() {
        when(memberMapper.selectList(any())).thenReturn(List.of(member(1L), member(2L)));
        when(toggleService.resolveGenEnabled(100L, 1L)).thenReturn(true);
        when(toggleService.resolveGenEnabled(100L, 2L)).thenReturn(true);
        // 乱序给：后建(项目2)在前，先建(项目1)在后 → 输出必须先建在前
        MemoryProjectRule later = rule(20L, 2L, List.of("课件"), OffsetDateTime.now().minusDays(1));
        MemoryProjectRule earlier = rule(10L, 1L, List.of("课件"), OffsetDateTime.now().minusDays(10));
        when(ruleService.findRoutingCandidates(List.of(1L, 2L))).thenReturn(List.of(later, earlier));
        when(projectMapper.selectBatchIds(anyCollection())).thenReturn(List.of());

        List<InclusionQuickCheckService.Hit> hits = service.quickCheck(100L, List.of("数学课件.pdf"));

        assertEquals(2, hits.size());
        assertEquals(1L, hits.get(0).projectId(), "先建规则项目排前（与路由 5x #8 同口径）");
        assertEquals(2L, hits.get(1).projectId());
        assertEquals("项目#2", hits.get(1).projectName(), "项目名查不到 → 兜底「项目#id」");
    }

    // ---- 3. 无附件 → 零 DB 交互（纯文本消息不进 MVP 快检）----

    @Test
    void noAttachmentsSkipsDb() {
        List<InclusionQuickCheckService.Hit> hits = service.quickCheck(100L, List.of());

        assertTrue(hits.isEmpty());
        verify(memberMapper, never()).selectList(any());
        verify(ruleService, never()).findRoutingCandidates(any());
    }

    // ---- 4. gen 双开关关的项目被滤掉（与路由 ② 同门）----

    @Test
    void genOffProjectExcluded() {
        when(memberMapper.selectList(any())).thenReturn(List.of(member(1L), member(2L)));
        lenient().when(toggleService.resolveGenEnabled(100L, 1L)).thenReturn(true);
        when(toggleService.resolveGenEnabled(100L, 2L)).thenReturn(false);
        when(ruleService.findRoutingCandidates(List.of(1L))).thenReturn(List.of());

        List<InclusionQuickCheckService.Hit> hits = service.quickCheck(100L, List.of("a.pdf"));

        assertTrue(hits.isEmpty());
        verify(ruleService).findRoutingCandidates(List.of(1L));
    }
}
