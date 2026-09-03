// agent-platform/backend/src/test/java/com/superprogrammer/knowledge/global/KbSummaryWorkerTest.java
package com.superprogrammer.knowledge.global;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.knowledge.config.KbSummaryProperties;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.entity.KnowledgeBaseSummary;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.mapper.KnowledgeBaseMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeBaseSummaryMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C7 库级摘要 Worker（WP4 Step1）：触发矩阵（成本节流）/ map-reduce 分批版本化落表 /
 * 重试上限置 ERROR / 无 L1 库跳过 / 摘要不出库（本类无对外读路径——结构由 Service 层保证）。
 */
class KbSummaryWorkerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private KnowledgeBaseMapper baseMapper;
    private KnowledgeDocumentMapper documentMapper;
    private KnowledgeBaseSummaryMapper summaryMapper;
    private LlmGateway llmGateway;
    private KbSummaryProperties props;
    private KbSummaryWorker worker;
    private final KnowledgeBase kb = kb();

    @BeforeAll
    static void initMpLambdaCache() {
        for (Class<?> entity : List.of(KnowledgeBase.class, KnowledgeDocument.class,
                KnowledgeBaseSummary.class)) {
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(),
                    "test-kb-summary-" + entity.getSimpleName());
            assistant.setCurrentNamespace("test-kb-summary-" + entity.getSimpleName());
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @BeforeEach
    void setUp() {
        baseMapper = mock(KnowledgeBaseMapper.class);
        documentMapper = mock(KnowledgeDocumentMapper.class);
        summaryMapper = mock(KnowledgeBaseSummaryMapper.class);
        llmGateway = mock(LlmGateway.class);
        props = new KbSummaryProperties();
        worker = new KbSummaryWorker(baseMapper, documentMapper, summaryMapper,
                llmGateway, props, objectMapper);
    }

    // ---- 触发矩阵 ----

    @Test
    void triggerMatrix_firstGenerateChangeThresholdStaleAndErrorSkip() {
        // ① 首次（无历史行）→ 生成
        when(documentMapper.selectList(any())).thenReturn(docs(3));
        when(summaryMapper.selectOne(any())).thenReturn(null);
        when(llmGateway.chat(any(LlmRequest.class), anyLong())).thenReturn(resp("{\"summary\":\"总览\",\"topics\":[\"差旅\"]}"));
        assertThat(worker.generateIfDue(kb)).isTrue();
        verify(summaryMapper).insert(any(KnowledgeBaseSummary.class));

        // ② 变更 <10% 且 7 天内 → 跳过（零 LLM 调用）
        setUp();   // 重置 mock
        when(documentMapper.selectList(any())).thenReturn(docs(10));
        when(summaryMapper.selectOne(any())).thenReturn(readyRow(10, OffsetDateTime.now()));
        assertThat(worker.generateIfDue(kb)).isFalse();
        verify(llmGateway, never()).chat(any(), anyLong());
        verify(summaryMapper, never()).insert(any());

        // ③ 变更 ≥10%（10→12）→ 重生成
        setUp();
        when(documentMapper.selectList(any())).thenReturn(docs(12));
        when(summaryMapper.selectOne(any())).thenReturn(readyRow(10, OffsetDateTime.now()));
        when(llmGateway.chat(any(LlmRequest.class), anyLong())).thenReturn(resp("{\"summary\":\"总览2\",\"topics\":[]}"));
        assertThat(worker.generateIfDue(kb)).isTrue();

        // ④ 超 7 天未变 → 重生成
        setUp();
        when(documentMapper.selectList(any())).thenReturn(docs(10));
        when(summaryMapper.selectOne(any())).thenReturn(readyRow(10, OffsetDateTime.now().minusDays(8)));
        when(llmGateway.chat(any(LlmRequest.class), anyLong())).thenReturn(resp("{\"summary\":\"总览3\",\"topics\":[]}"));
        assertThat(worker.generateIfDue(kb)).isTrue();

        // ⑤ 上一版 ERROR → 待手动跳过
        setUp();
        when(documentMapper.selectList(any())).thenReturn(docs(10));
        KnowledgeBaseSummary error = readyRow(10, OffsetDateTime.now());
        error.setStatus("ERROR");
        when(summaryMapper.selectOne(any())).thenReturn(error);
        assertThat(worker.generateIfDue(kb)).isFalse();
        verify(llmGateway, never()).chat(any(), anyLong());

        // ⑥ 无 L1 文档库 → 跳过
        setUp();
        when(documentMapper.selectList(any())).thenReturn(List.of());
        assertThat(worker.generateIfDue(kb)).isFalse();
        verify(llmGateway, never()).chat(any(), anyLong());
    }

    // ---- map-reduce 分批 + 版本化落表 ----

    @Test
    void mapReduce_batchesL1sAndWritesVersionedReadyRow() {
        props.setBatchSize(20);
        when(documentMapper.selectList(any())).thenReturn(docs(45));   // 45 → 3 批
        when(summaryMapper.selectOne(any())).thenReturn(readyRow(10, OffsetDateTime.now().minusDays(9)));
        when(llmGateway.chat(any(LlmRequest.class), anyLong()))
                .thenReturn(resp("批1要点"))
                .thenReturn(resp("批2要点"))
                .thenReturn(resp("批3要点"))
                .thenReturn(resp("{\"summary\":\"库级总览：差旅与报销制度。\",\"topics\":[\"差旅\",\"报销\"]}"));

        assertThat(worker.generateIfDue(kb)).isTrue();

        // 3 次 map（每批一次）+ 1 次 reduce，串行低峰口径
        verify(llmGateway, times(4)).chat(any(LlmRequest.class), anyLong());
        ArgumentCaptor<KnowledgeBaseSummary> rows = ArgumentCaptor.forClass(KnowledgeBaseSummary.class);
        verify(summaryMapper).insert(rows.capture());
        KnowledgeBaseSummary row = rows.getValue();
        assertThat(row.getStatus()).isEqualTo("READY");
        assertThat(row.getVersion()).isEqualTo(2);                      // 上一 READY v1 → 新 v2
        assertThat(row.getSummary()).isEqualTo("库级总览：差旅与报销制度。");
        assertThat(row.getTopics()).isEqualTo("[\"差旅\",\"报销\"]");
        assertThat(row.getStats()).contains("\"docCount\":45").contains("\"batchCount\":3").contains("\"attempt\":1");
        assertThat(row.getKbId()).isEqualTo(7L);
    }

    // ---- 重试上限 → ERROR 行待手动 ----

    @Test
    void retryExhausted_writesErrorRowAfterMaxAttempts() {
        when(documentMapper.selectList(any())).thenReturn(docs(2));
        when(summaryMapper.selectOne(any())).thenReturn(null);
        when(llmGateway.chat(any(LlmRequest.class), anyLong()))
                .thenThrow(new RuntimeException("provider 503"));

        assertThat(worker.generateIfDue(kb)).isTrue();

        verify(llmGateway, times(props.getMaxAttempts())).chat(any(LlmRequest.class), anyLong());
        ArgumentCaptor<KnowledgeBaseSummary> rows = ArgumentCaptor.forClass(KnowledgeBaseSummary.class);
        verify(summaryMapper).insert(rows.capture());
        KnowledgeBaseSummary row = rows.getValue();
        assertThat(row.getStatus()).isEqualTo("ERROR");
        assertThat(row.getVersion()).isEqualTo(1);
        assertThat(row.getSummary()).isNull();
        assertThat(row.getStats()).contains("\"attempt\":3").contains("provider 503");
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
            doc.setId(100L + i);
            doc.setTitle("文档" + i);
            doc.setL1Metadata("{\"summary\":\"第" + i + "号制度摘要：报销与差旅规定。\"}");
            return doc;
        }).toList();
    }

    private static KnowledgeBaseSummary readyRow(int docCount, OffsetDateTime generatedAt) {
        KnowledgeBaseSummary row = new KnowledgeBaseSummary();
        row.setKbId(7L);
        row.setVersion(1);
        row.setStatus("READY");
        row.setSummary("旧总览");
        row.setStats("{\"docCount\":" + docCount + ",\"batchCount\":1}");
        row.setGeneratedAt(generatedAt);
        return row;
    }

    private static LlmResponse resp(String content) {
        LlmResponse resp = new LlmResponse();
        resp.setContent(content);
        return resp;
    }
}
