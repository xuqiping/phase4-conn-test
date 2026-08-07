package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.superprogrammer.chat.dto.MemoryProjectRuleRequest;
import com.superprogrammer.chat.dto.MemoryProjectRuleVO;
import com.superprogrammer.chat.entity.MemoryProjectMember;
import com.superprogrammer.chat.entity.MemoryProjectRule;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import com.superprogrammer.chat.mapper.MemoryProjectRuleMapper;
import com.superprogrammer.common.exception.BusinessException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆二期 P1 · 收录规则 service 单测（FR-001）。
 * 纯 Mockito（承 MemoryLifecycleServiceTest 范式）：LambdaQueryWrapper 须 TableInfoHelper 预注册。
 */
@ExtendWith(MockitoExtension.class)
class MemoryProjectRuleServiceTest {

    @Mock
    private MemoryProjectRuleMapper ruleMapper;
    @Mock
    private MemoryProjectMemberMapper memberMapper;
    @Mock
    private MemoryTagAnchorService anchorService;

    private MemoryProjectRuleService service;

    @BeforeAll
    static void initTableInfo() {
        org.apache.ibatis.session.Configuration cfg = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(cfg, "");
        TableInfoHelper.initTableInfo(assistant, MemoryProjectRule.class);
        TableInfoHelper.initTableInfo(assistant, MemoryProjectMember.class);
    }

    @BeforeEach
    void setUp() {
        service = new MemoryProjectRuleService(ruleMapper, memberMapper, anchorService);
    }

    private MemoryProjectMember membership(String role) {
        MemoryProjectMember m = new MemoryProjectMember();
        m.setProjectId(1L);
        m.setUserId(100L);
        m.setRole(role);
        m.setStatus("ACTIVE");
        return m;
    }

    private MemoryProjectRuleRequest req(String text) {
        MemoryProjectRuleRequest r = new MemoryProjectRuleRequest();
        r.setRuleText(text);
        r.setPositiveExamples(List.of("SeedDance 参数讨论"));
        r.setEnabled(true);
        return r;
    }

    // AC-FR-001：新规则保存 → anchor 同步算 + insert
    @Test
    void saveRule_newRule_insertsWithAnchor() {
        when(ruleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(anchorService.build(any(), isNull(), isNull(), any(), any()))
                .thenReturn(new MemoryTagAnchorService.AnchorPayload("[0.1,0.2]", "token1 token2"));

        MemoryProjectRuleVO vo = service.saveRule(1L, req("涉及 SeedDance 的讨论"), 100L);

        ArgumentCaptor<MemoryProjectRule> captor = ArgumentCaptor.forClass(MemoryProjectRule.class);
        verify(ruleMapper).insertWithAnchor(captor.capture(), org.mockito.ArgumentMatchers.eq("[0.1,0.2]"));
        assertEquals("涉及 SeedDance 的讨论", captor.getValue().getRuleText());
        assertTrue(captor.getValue().getEnabled());
        assertTrue(vo.getEnabled());
        assertTrue(vo.getAnchorReady());
    }

    // AC-FR-001：embed 失败 → 规则存库但 enabled 强制 false（坑点：anchor 未生成时规则不生效）
    @Test
    void saveRule_anchorFailed_forcesDisabled() {
        when(ruleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(anchorService.build(any(), isNull(), isNull(), any(), any())).thenReturn(null);

        MemoryProjectRuleVO vo = service.saveRule(1L, req("涉及 SeedDance 的讨论"), 100L);

        ArgumentCaptor<MemoryProjectRule> captor = ArgumentCaptor.forClass(MemoryProjectRule.class);
        verify(ruleMapper).insertWithAnchor(captor.capture(), isNull());
        assertFalse(captor.getValue().getEnabled());
        assertFalse(vo.getEnabled());
        assertFalse(vo.getAnchorReady());
    }

    // AC-FR-001：更新既有规则走 updateWithAnchor
    @Test
    void saveRule_existingRule_updates() {
        MemoryProjectRule existing = new MemoryProjectRule();
        existing.setId(9L);
        existing.setProjectId(1L);
        existing.setRuleText("旧规则");
        when(ruleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(anchorService.build(any(), isNull(), isNull(), any(), any()))
                .thenReturn(new MemoryTagAnchorService.AnchorPayload("[0.3]", "tok"));

        service.saveRule(1L, req("新规则"), 100L);

        verify(ruleMapper).updateWithAnchor(any(MemoryProjectRule.class), org.mockito.ArgumentMatchers.eq("[0.3]"));
        verify(ruleMapper, never()).insertWithAnchor(any(), any());
    }

    // 输入校验：空文本/超长/超条数 → 400
    @Test
    void saveRule_validation() {
        assertThrows(BusinessException.class, () -> service.saveRule(1L, req("  "), 100L));

        MemoryProjectRuleRequest longText = req("x".repeat(2001));
        assertThrows(BusinessException.class, () -> service.saveRule(1L, longText, 100L));

        MemoryProjectRuleRequest tooMany = req("ok");
        tooMany.setPositiveExamples(List.of("1", "2", "3", "4", "5", "6"));
        assertThrows(BusinessException.class, () -> service.saveRule(1L, tooMany, 100L));
    }

    // AC-FR-001：成员读 → negative_examples 裁掉；owner 读 → 可见
    @Test
    void getRule_visibilityByRole() {
        MemoryProjectRule rule = new MemoryProjectRule();
        rule.setId(9L);
        rule.setProjectId(1L);
        rule.setRuleText("规则");
        rule.setNegativeExamples(List.of("负例A"));
        rule.setAnchorTokens("tok");
        rule.setEnabled(true);
        when(ruleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(rule);

        when(memberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(membership("MEMBER"));
        MemoryProjectRuleVO memberView = service.getRule(1L, 100L);
        assertNull(memberView.getNegativeExamples());
        assertNotNull(memberView.getRuleText());

        when(memberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(membership("OWNER"));
        MemoryProjectRuleVO ownerView = service.getRule(1L, 100L);
        assertEquals(List.of("负例A"), ownerView.getNegativeExamples());
    }

    // AC-FR-005 前置：负例滚动追加 ≤5 先进先出；anchor 不动
    @Test
    void appendNegativeExample_rollingFive() {
        MemoryProjectRule rule = new MemoryProjectRule();
        rule.setId(9L);
        rule.setProjectId(1L);
        rule.setRuleText("规则");
        rule.setEnabled(true);
        rule.setNegativeExamples(new ArrayList<>(List.of("a", "b", "c", "d", "e")));
        when(ruleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(rule);

        service.appendNegativeExample(1L, "f");

        ArgumentCaptor<MemoryProjectRule> captor = ArgumentCaptor.forClass(MemoryProjectRule.class);
        verify(ruleMapper).updateWithAnchor(captor.capture(), isNull());
        assertEquals(List.of("b", "c", "d", "e", "f"), captor.getValue().getNegativeExamples());
    }

    @Test
    void appendNegativeExample_noRule_noop() {
        when(ruleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        service.appendNegativeExample(1L, "x");
        verify(ruleMapper, never()).updateWithAnchor(any(), any());
    }

    // 权边界：owner/admin ACTIVE 才可写；MEMBER/DEPARTED 不可
    @Test
    void isOwnerOrAdmin_roleMatrix() {
        when(memberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(membership("OWNER"));
        assertTrue(service.isOwnerOrAdmin(1L, 100L));
        when(memberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(membership("ADMIN"));
        assertTrue(service.isOwnerOrAdmin(1L, 100L));
        when(memberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(membership("MEMBER"));
        assertFalse(service.isOwnerOrAdmin(1L, 100L));

        MemoryProjectMember departed = membership("OWNER");
        departed.setStatus("DEPARTED");
        when(memberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(departed);
        assertFalse(service.isOwnerOrAdmin(1L, 100L));

        lenient().when(memberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        assertFalse(service.isOwnerOrAdmin(1L, 999L));
    }
}
