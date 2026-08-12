package com.superprogrammer.chat.service.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.entity.MemoryTag;
import com.superprogrammer.chat.mapper.MemoryTagMapper;
import com.superprogrammer.chat.mapper.MemoryTagRepairMapper;
import com.superprogrammer.chat.service.internal.MemoryTagRepairService.MergeGroup;
import com.superprogrammer.chat.service.internal.MemoryTagRepairService.RepairReport;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmResponse;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V77 · MemoryTagRepairService 单测：dry-run 报告正确 + execute 后 6 表重指 + survivor 改大类 + loser 软删。
 * <p>
 * 场景：孤儿 106（NULL 锚点）；同 user 同大类下 113/114 细标签归并（survivor=113 usage 高）；
 * 200 单标签组改大类（编程→技术技能）。
 */
@ExtendWith(MockitoExtension.class)
class MemoryTagRepairServiceTest {

    @Mock MemoryTagMapper tagMapper;
    @Mock MemoryTagRepairMapper repairMapper;
    @Mock MemoryTagAnchorService anchorService;
    @Mock LlmGateway llmGateway;
    @Mock SystemSettingService systemSettingService;

    @InjectMocks MemoryTagRepairService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 修复 @InjectMocks 未注入手写 ObjectMapper 字段（final 字段反射塞）。 */
    void wire() throws Exception {
        java.lang.reflect.Field f = MemoryTagRepairService.class.getDeclaredField("objectMapper");
        f.setAccessible(true);
        f.set(service, objectMapper);
    }

    private static MemoryTag tag(long id, String subject, String topic, String label, int usage) {
        MemoryTag t = new MemoryTag();
        t.setId(id);
        t.setUserId(1L);
        t.setSubject(subject);
        t.setTopic(topic);
        t.setLabel(label);
        t.setUsageCount(usage);
        return t;
    }

    private void stubClassify() {
        when(systemSettingService.getMemoryTagVocab()).thenReturn(List.of("旅行出行", "技术技能", "其他"));
        when(systemSettingService.getMemoryJudgeModel()).thenReturn("doubao-seed-2.0-code");
        // 113/114 归「旅行出行」→ 同组归并；200 归「技术技能」→ 单组改大类
        String classify = "[{\"id\":113,\"topic\":\"旅行出行\"},"
                + "{\"id\":114,\"topic\":\"旅行出行\"},{\"id\":200,\"topic\":\"技术技能\"}]";
        when(llmGateway.chat(any(), eq(1L))).thenReturn(LlmResponse.builder().content(classify).build());
    }

    // ===== dry-run：只算报告，不落库 =====

    @Test
    void dryRun_reportsWithoutWriting() throws Exception {
        wire();
        when(repairMapper.findNullAnchorTags()).thenReturn(List.of(tag(106L, "我", "SeedDance", "视频", 1)));
        when(tagMapper.selectList(any())).thenReturn(List.of(
                tag(113L, "我", "旅游攻略", "杭州", 5),
                tag(114L, "我", "旅行计划", "苏州", 2),
                tag(200L, "我", "编程", "后端", 3)));
        stubClassify();

        RepairReport r = service.repair(true);

        assertTrue(r.dryRun);
        assertEquals(1, r.orphanCount, "1 个孤儿（106）");
        assertEquals(List.of(106L), r.orphanRegenerated);
        assertEquals(1, r.mergeGroups.size(), "113/114 同大类归并 1 组");
        MergeGroup mg = r.mergeGroups.get(0);
        assertEquals(113L, mg.survivorId, "survivor = usage 最高 113");
        assertEquals(List.of(114L), mg.loserIds);
        assertEquals("旅行出行", mg.category);
        assertEquals(List.of(200L), r.retagged, "200 单标签组改大类（编程→技术技能）");

        // dry-run 零落库
        verify(repairMapper, never()).updateTopicAndAnchor(anyLong(), anyString(), any(), any());
        verify(repairMapper, never()).softDeleteTag(anyLong());
        verify(repairMapper, never()).reassignSummariesTagId(anyLong(), anyLong());
        verify(anchorService, never()).build(anyLong(), anyString(), anyString(), anyString(), any());
    }

    // ===== survivor 优先取已是目标大类的干净标签（避免改 topic 撞 UNIQUE）=====

    @Test
    void execute_prefersAlreadyCleanTagAsSurvivor() throws Exception {
        wire();
        // 300 已是干净大类 topic=旅行出行（usage 低）；301/302 细标签（usage 高）待归并。
        // 期望：300 留作 survivor（保留干净 topic，免改 topic 撞 uk），301/302 并入它。
        when(repairMapper.findNullAnchorTags()).thenReturn(List.of());
        when(tagMapper.selectList(any())).thenReturn(List.of(
                tag(300L, "我", "旅行出行", "杭州游", 1),
                tag(301L, "我", "旅游攻略", "苏州", 9),
                tag(302L, "我", "旅行计划", "南京", 8)));
        when(systemSettingService.getMemoryTagVocab()).thenReturn(List.of("旅行出行", "技术技能"));
        when(systemSettingService.getMemoryJudgeModel()).thenReturn("doubao-seed-2.0-code");
        when(llmGateway.chat(any(), eq(1L))).thenReturn(LlmResponse.builder().content(
                "[{\"id\":300,\"topic\":\"旅行出行\"},{\"id\":301,\"topic\":\"旅行出行\"},{\"id\":302,\"topic\":\"旅行出行\"}]")
                .build());
        when(anchorService.build(eq(1L), anyString(), anyString(), anyString(), any()))
                .thenReturn(new MemoryTagAnchorService.AnchorPayload("[0.2]", "tok"));

        RepairReport r = service.repair(false);

        assertEquals(1, r.mergeGroups.size());
        assertEquals(300L, r.mergeGroups.get(0).survivorId, "已是目标大类的干净标签应作 survivor");
        // 301/302 并入 300（6 表重指 + 软删）
        verify(repairMapper).reassignSummariesTagId(301L, 300L);
        verify(repairMapper).softDeleteTag(301L);
        verify(repairMapper).softDeleteTag(302L);
        // survivor 300 topic 已是旅行出行→updateTopicAndAnchor 仍调（重生锚点），但不撞 UNIQUE
        verify(repairMapper).updateTopicAndAnchor(eq(300L), eq("旅行出行"), any(), any());
    }

    // ===== execute：6 表全重指 + survivor 改大类 + loser 软删 =====

    @Test
    void execute_reassignsAllTablesAndSoftDeletesLoser() throws Exception {
        wire();
        when(repairMapper.findNullAnchorTags()).thenReturn(List.of(tag(106L, "我", "SeedDance", "视频", 1)));
        when(tagMapper.selectList(any())).thenReturn(List.of(
                tag(113L, "我", "旅游攻略", "杭州", 5),
                tag(114L, "我", "旅行计划", "苏州", 2),
                tag(200L, "我", "编程", "后端", 3)));
        stubClassify();
        // anchor 重生桩（孤儿 106 + survivor 113 + 单组 200，三处 build）
        when(anchorService.build(eq(1L), anyString(), anyString(), anyString(), any()))
                .thenReturn(new MemoryTagAnchorService.AnchorPayload("[0.2]", "tok"));

        RepairReport r = service.repair(false);

        // loser 114 的 6 表全重指到 survivor 113 + 软删
        verify(repairMapper).mergeAliases(113L, 114L);
        verify(repairMapper).reassignSummariesTagId(114L, 113L);
        verify(repairMapper).reassignConflictTagId(114L, 113L);
        verify(repairMapper).reassignTurnsTagIds(114L, 113L);
        verify(repairMapper).reassignEntriesTagIds(114L, 113L);
        verify(repairMapper).reassignEntryCoverageTagId(114L, 113L);
        verify(repairMapper).reassignSummaryCoverageTagId(114L, 113L);
        verify(repairMapper).softDeleteTag(114L);
        // survivor 改大类 + 重生锚点
        verify(repairMapper).updateTopicAndAnchor(eq(113L), eq("旅行出行"), any(), any());
        // 孤儿 106 重生锚点
        verify(repairMapper).updateTopicAndAnchor(eq(106L), eq("SeedDance"), any(), any());
        // 单组 200 改大类
        verify(repairMapper).updateTopicAndAnchor(eq(200L), eq("技术技能"), any(), any());
    }
}
