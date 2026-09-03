// agent-platform/backend/src/test/java/com/superprogrammer/knowledge/global/GlobalAnswerStrategyTest.java
package com.superprogrammer.knowledge.global;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.knowledge.config.GlobalAnswerProperties;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.entity.KnowledgeBaseSummary;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.mapper.KnowledgeBaseSummaryMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.service.internal.CitationChecker;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.llm.dto.LlmResponse;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C7 GLOBAL map-reduce 分支（WP4 Step2）：map 分批并行+reduce 合成 / 概览段拼装 /
 * 文档级引用白名单与越界降级 / 超时降级 / 可见集过滤 / 多库提示 / kill switch。
 */
class GlobalAnswerStrategyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private KnowledgeDocumentMapper documentMapper;
    private KnowledgeBaseSummaryMapper summaryMapper;
    private LlmGateway llmGateway;
    private GlobalAnswerProperties props;
    private GlobalAnswerStrategy strategy;
    private final KnowledgeBase kb = kb();

    @BeforeAll
    static void initMpLambdaCache() {
        for (Class<?> entity : List.of(KnowledgeDocument.class, KnowledgeBaseSummary.class)) {
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(),
                    "test-global-answer-" + entity.getSimpleName());
            assistant.setCurrentNamespace("test-global-answer-" + entity.getSimpleName());
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @BeforeEach
    void setUp() {
        documentMapper = mock(KnowledgeDocumentMapper.class);
        summaryMapper = mock(KnowledgeBaseSummaryMapper.class);
        llmGateway = mock(LlmGateway.class);
        props = new GlobalAnswerProperties();
        strategy = new GlobalAnswerStrategy(documentMapper, summaryMapper, llmGateway,
                props, new CitationChecker(), objectMapper);
    }

    // ---- map 分批 + reduce 合成 + 概览段 + 引用校验 ----

    @Test
    void mapReduce_batchesParallelReducesAndComposesOverviewSegment() {
        props.setBatchSize(15);
        when(documentMapper.selectList(any())).thenReturn(docs(40));       // 40 → 3 批
        when(summaryMapper.selectOne(any())).thenReturn(readyOverview());
        when(llmGateway.chat(any(LlmRequest.class), eq(9L)))
                .thenReturn(resp("批A要点"))
                .thenReturn(resp("批B要点"))
                .thenReturn(resp("批C要点"))
                .thenReturn(resp("库内主要覆盖差旅与报销 [1]，其中 [3] 规定了审批权限。"));

        GlobalAnswerStrategy.GlobalResult r = strategy.answer(kb, "总结全库主题", 9L, true, List.of(), false);

        assertThat(r.degraded()).isFalse();
        assertThat(r.docs()).hasSize(40);
        assertThat(r.batchCount()).isEqualTo(3);
        assertThat(r.cited()).containsExactly(1, 3);
        assertThat(r.overviewUsed()).isTrue();
        assertThat(r.answer())
                .startsWith("【库概览】（来自库级摘要 L-KB，生成于 ")
                .contains("旧总览：差旅与报销制度。")
                .contains("【要点综述】\n库内主要覆盖差旅与报销 [1]");
        // 3 次 map（并行池内）+ 1 次 reduce；计费归户当前用户
        verify(llmGateway, times(4)).chat(any(LlmRequest.class), eq(9L));
        ArgumentCaptor<LlmRequest> cap = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmGateway, atLeast(1)).chat(cap.capture(), eq(9L));
        assertThat(cap.getValue().getTimeoutMs()).isPositive();
    }

    // ---- 引用越界：重生成仍失败 → 降级（仅概览+提示） ----

    @Test
    void citationOutOfRange_retryThenDegrade() {
        when(documentMapper.selectList(any())).thenReturn(docs(2));       // 1 批
        when(summaryMapper.selectOne(any())).thenReturn(readyOverview());
        when(llmGateway.chat(any(LlmRequest.class), eq(9L)))
                .thenReturn(resp("map 要点"))
                .thenReturn(resp("引用了不存在的 [99] 文档"))                // 首次越界
                .thenReturn(resp("还是越界 [98]"));                          // 重生成仍越界

        GlobalAnswerStrategy.GlobalResult r = strategy.answer(kb, "总结全库", 9L, true, List.of(), false);

        assertThat(r.degraded()).isTrue();
        assertThat(r.cited()).isEmpty();
        verify(llmGateway, times(3)).chat(any(LlmRequest.class), eq(9L));   // map + reduce×2（重试一次）
        assertThat(r.answer())
                .contains("【库概览】")
                .contains("已降级为仅库级概览")
                .doesNotContain("【要点综述】");
    }

    @Test
    void citationOutOfRange_retrySucceeds() {
        when(documentMapper.selectList(any())).thenReturn(docs(2));
        when(summaryMapper.selectOne(any())).thenReturn(null);             // 无 L-KB → 概览段缺席
        when(llmGateway.chat(any(LlmRequest.class), eq(9L)))
                .thenReturn(resp("map 要点"))
                .thenReturn(resp("越界 [88]"))
                .thenReturn(resp("正确引用 [2]"));

        GlobalAnswerStrategy.GlobalResult r = strategy.answer(kb, "总结全库", 9L, true, List.of(), false);

        assertThat(r.degraded()).as("重生成成功不降级").isFalse();
        assertThat(r.cited()).containsExactly(2);
        assertThat(r.overviewUsed()).isFalse();
        assertThat(r.answer()).startsWith("【要点综述】\n正确引用 [2]");
    }

    // ---- 超时降级 ----

    @Test
    void timeout_degradesToOverviewOnly() {
        props.setTimeoutMs(200);
        when(documentMapper.selectList(any())).thenReturn(docs(4));        // 1 批
        when(summaryMapper.selectOne(any())).thenReturn(readyOverview());
        when(llmGateway.chat(any(LlmRequest.class), anyLong())).thenAnswer(inv -> {
            Thread.sleep(600);   // 模拟 provider 慢
            return resp("太晚的要点");
        });

        GlobalAnswerStrategy.GlobalResult r = strategy.answer(kb, "总结全库", 9L, true, List.of(), false);

        assertThat(r.degraded()).isTrue();
        assertThat(r.answer())
                .contains("【库概览】")
                .contains("建议缩小问题范围");
    }

    // ---- 可见集过滤 / 空 L1 降级 ----

    @Test
    void visibilityFilter_queryScopesToVisibleDocs() {
        when(documentMapper.selectList(any())).thenReturn(docs(3));
        when(summaryMapper.selectOne(any())).thenReturn(readyOverview());
        when(llmGateway.chat(any(LlmRequest.class), eq(9L)))
                .thenReturn(resp("map"))
                .thenReturn(resp("基于 [2]"));

        GlobalAnswerStrategy.GlobalResult r =
                strategy.answer(kb, "总结全库", 9L, false, List.of(101L, 103L), false);

        // 纯 Mockito 不执行 SQL → 验证 wrapper：非 allDocs 时必须带 IN(可见 docIds)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<KnowledgeDocument>> w =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(documentMapper).selectList(w.capture());
        String sql = w.getValue().getSqlSegment();   // 先取 sqlSegment 触发 IN 参数惰性物化（WP3 坑）
        assertThat(sql).contains("IN");
        assertThat(w.getValue().getParamNameValuePairs().values().toString())
                .contains("101").contains("103");
        assertThat(r.cited()).containsExactly(2);
    }

    @Test
    void emptyVisibleSet_degradesWithoutLlm() {
        GlobalAnswerStrategy.GlobalResult r = strategy.answer(kb, "总结全库", 9L, false, List.of(), false);

        assertThat(r.degraded()).isTrue();
        assertThat(r.answer()).contains("暂无可用的文档摘要");
        verify(llmGateway, never()).chat(any(), anyLong());
    }

    // ---- 多库取首库提示 / kill switch / 勘察 ----

    @Test
    void multiKbNarrowed_hintAppended() {
        when(documentMapper.selectList(any())).thenReturn(docs(1));
        when(summaryMapper.selectOne(any())).thenReturn(readyOverview());
        when(llmGateway.chat(any(LlmRequest.class), eq(9L)))
                .thenReturn(resp("map"))
                .thenReturn(resp("要点 [1]"));

        GlobalAnswerStrategy.GlobalResult r =
                strategy.answer(kb, "总结全库", 9L, true, List.of(), true);

        assertThat(r.answer()).contains("已仅对首个知识库《制度库》生成全局概览");
    }

    @Test
    void killSwitchOff_returnsNull() {
        props.setEnabled(false);
        assertThat(strategy.answer(kb, "总结全库", 9L, true, List.of(), false)).isNull();
        verify(llmGateway, never()).chat(any(), anyLong());
    }

    @Test
    void inspect_countsDocsAndBatchesWithoutLlm() {
        props.setBatchSize(15);
        when(documentMapper.selectList(any())).thenReturn(docs(37));
        when(summaryMapper.selectOne(any())).thenReturn(readyOverview());

        GlobalAnswerStrategy.Inspection insp = strategy.inspect(7L, true, List.of());

        assertThat(insp.docCount()).isEqualTo(37);
        assertThat(insp.batchCount()).isEqualTo(3);
        assertThat(insp.overviewReady()).isTrue();
        verify(llmGateway, never()).chat(any(), anyLong());
    }

    // ---- 夹具 ----

    private static KnowledgeBase kb() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(7L);
        kb.setName("制度库");
        kb.setCreatedBy(42L);
        return kb;
    }

    private static List<KnowledgeDocument> docs(int n) {
        return IntStream.range(0, n).mapToObj(i -> {
            KnowledgeDocument doc = new KnowledgeDocument();
            doc.setId(101L + i);
            doc.setTitle("文档" + i);
            doc.setL1Metadata("{\"summary\":\"第" + i + "号制度摘要：差旅与报销规定。\"}");
            return doc;
        }).toList();
    }

    private static KnowledgeBaseSummary readyOverview() {
        KnowledgeBaseSummary row = new KnowledgeBaseSummary();
        row.setKbId(7L);
        row.setVersion(1);
        row.setStatus("READY");
        row.setSummary("旧总览：差旅与报销制度。");
        row.setGeneratedAt(OffsetDateTime.now());
        return row;
    }

    private static LlmResponse resp(String content) {
        LlmResponse resp = new LlmResponse();
        resp.setContent(content);
        return resp;
    }
}
