package com.superprogrammer.knowledge.connector;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.superprogrammer.knowledge.entity.KnowledgeConnector;
import com.superprogrammer.knowledge.mapper.KnowledgeConnectorDocMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeConnectorMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeDocEmbeddingMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeEmbeddingMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeImageEmbeddingMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeNodeMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.OffsetDateTime;
import java.util.Map;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP6 Step3：同步 tx 记账——连续错误第 3 轮置 ERROR（前两轮不置）/成功清零/
 * 认领乐观复核（last_sync_at 已被并发推进 → 放弃）。
 * 坑（MP）：LambdaUpdateWrapper 参数惰性物化——须先 getSqlSegment() 再读 paramNameValuePairs。
 */
class ConnectorSyncTxServiceTest {

    private final KnowledgeConnectorMapper connectors = mock(KnowledgeConnectorMapper.class);
    private final ConnectorSyncTxService service = new ConnectorSyncTxService(
            connectors,
            mock(KnowledgeConnectorDocMapper.class),
            mock(KnowledgeDocumentMapper.class),
            mock(KnowledgeNodeMapper.class),
            mock(KnowledgeEmbeddingMapper.class),
            mock(KnowledgeDocEmbeddingMapper.class),
            mock(KnowledgeImageEmbeddingMapper.class),
            mock(ApplicationEventPublisher.class));

    @BeforeAll
    static void initLambdaCache() {
        // MP 纯单测语境无 TableInfo：Lambda 列名解析依赖 lambda cache，须显式注册（AssetServiceTest 同款）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), KnowledgeConnector.class);
    }

    private static Map<String, Object> applied(LambdaUpdateWrapper<KnowledgeConnector> w) {
        w.getSqlSegment();   // 触发参数物化（坑：不调则 paramNameValuePairs 空）
        return w.getParamNameValuePairs();
    }

    @Test
    void recordError_thirdStreak_setsErrorStatus() {
        KnowledgeConnector row = new KnowledgeConnector();
        row.setId(5L);
        row.setSyncErrorStreak(2);   // 已连败 2 轮，本次第 3 轮
        when(connectors.selectById(5L)).thenReturn(row);

        service.recordError(5L, "连接超时");

        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeConnector>> cap =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(connectors).update(any(), cap.capture());
        Map<String, Object> sets = applied(cap.getValue());
        assertTrue(sets.containsValue(3), "streak 应 +1 → 3");
        assertTrue(sets.containsValue(KnowledgeConnector.STATUS_ERROR), "第 3 轮应置 ERROR");
        assertTrue(sets.values().stream().anyMatch(v -> String.valueOf(v).contains("连接超时")));
    }

    @Test
    void recordError_beforeThirdStreak_keepsEnabled() {
        KnowledgeConnector row = new KnowledgeConnector();
        row.setId(5L);
        row.setSyncErrorStreak(1);   // 第 2 轮
        when(connectors.selectById(5L)).thenReturn(row);

        service.recordError(5L, "偶发失败");

        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeConnector>> cap =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(connectors).update(any(), cap.capture());
        Map<String, Object> sets = applied(cap.getValue());
        assertTrue(sets.containsValue(2));
        assertFalse(sets.containsValue(KnowledgeConnector.STATUS_ERROR));
    }

    @Test
    void finishSuccess_resetsStreakAndStoresSummary() {
        service.finishSuccess(5L, "新增1/更新0");

        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeConnector>> cap =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(connectors).update(any(), cap.capture());
        Map<String, Object> sets = applied(cap.getValue());
        assertTrue(sets.containsValue(0), "成功清零 streak");
        assertTrue(sets.containsValue("新增1/更新0"));
    }

    @Test
    void tryClaim_lastSyncAdvancedByPeer_givesUp() {
        KnowledgeConnector row = new KnowledgeConnector();
        row.setId(5L);
        row.setStatus(KnowledgeConnector.STATUS_ENABLED);
        row.setLastSyncAt(OffsetDateTime.now());   // 已被并发节点推进
        when(connectors.selectOne(any())).thenReturn(row);

        boolean claimed = service.tryClaim(5L, OffsetDateTime.now().minusMinutes(5));

        assertFalse(claimed);
        verify(connectors, never()).update(any(), any());
    }

    @Test
    void tryClaim_rowMissing_givesUp() {
        when(connectors.selectOne(any())).thenReturn(null);
        assertFalse(service.tryClaim(5L, null));
        verify(connectors, never()).update(any(), any());
    }
}
