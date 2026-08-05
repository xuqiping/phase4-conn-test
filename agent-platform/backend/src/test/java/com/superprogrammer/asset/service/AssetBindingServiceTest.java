package com.superprogrammer.asset.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.superprogrammer.asset.dto.AssetUsageVO;
import com.superprogrammer.asset.entity.AssetBinding;
import com.superprogrammer.asset.mapper.AssetBindingMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AssetBindingService 单测：PRODUCED/REFERENCE 绑定写入 + 重复入库检测 + 使用记录列表（plan §S7 验证）。
 */
@ExtendWith(MockitoExtension.class)
class AssetBindingServiceTest {

    private static final Long ASSET_ID = 100L;
    private static final Long CANVAS_ID = 7L;
    private static final String NODE_ID = "node-1";
    private static final Long USER_ID = 10L;

    @Mock private AssetBindingMapper bindingMapper;

    private AssetBindingService service;

    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper 须填充 MP lambda 缓存（沉淀规范：纯 Mockito 测须 initTableInfo）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AssetBinding.class);
    }

    @BeforeEach
    void setUp() {
        service = new AssetBindingService(bindingMapper);
    }

    @Test
    void recordProduced_insertsProducedBinding() {
        Long id = service.recordProduced(ASSET_ID, 1, CANVAS_ID, NODE_ID, USER_ID);

        assertNull(id, "mock insert 不回填 id，返回 null 为预期（实际由 DB 回填）");
        ArgumentCaptor<AssetBinding> cap = ArgumentCaptor.forClass(AssetBinding.class);
        verify(bindingMapper).insert(cap.capture());
        AssetBinding b = cap.getValue();
        assertEquals(ASSET_ID, b.getAssetId());
        assertEquals(1, b.getAssetVersion());
        assertEquals(CANVAS_ID, b.getCanvasId());
        assertEquals(NODE_ID, b.getNodeId());
        assertEquals(AssetBinding.BIND_PRODUCED, b.getBindType());
        assertEquals(USER_ID, b.getCreatedBy());
    }

    @Test
    void recordReference_insertsReferenceBinding() {
        service.recordReference(ASSET_ID, 2, CANVAS_ID, "node-2", USER_ID);

        ArgumentCaptor<AssetBinding> cap = ArgumentCaptor.forClass(AssetBinding.class);
        verify(bindingMapper).insert(cap.capture());
        assertEquals(AssetBinding.BIND_REFERENCE, cap.getValue().getBindType());
        assertEquals("node-2", cap.getValue().getNodeId());
    }

    @Test
    void findProduced_returnsExistingBinding() {
        AssetBinding existing = new AssetBinding();
        existing.setAssetId(ASSET_ID);
        existing.setCanvasId(CANVAS_ID);
        existing.setNodeId(NODE_ID);
        existing.setBindType(AssetBinding.BIND_PRODUCED);
        when(bindingMapper.selectOne(any())).thenReturn(existing);

        AssetBinding found = service.findProduced(CANVAS_ID, NODE_ID);

        assertNotNull(found);
        assertEquals(ASSET_ID, found.getAssetId());
    }

    @Test
    void findProduced_nullArgs_returnsNull() {
        assertNull(service.findProduced(null, NODE_ID));
        assertNull(service.findProduced(CANVAS_ID, null));
    }

    @Test
    void listUsages_returnsAllBindingsForAsset() {
        AssetBinding produced = binding(1L, AssetBinding.BIND_PRODUCED, 1);
        AssetBinding ref = binding(2L, AssetBinding.BIND_REFERENCE, 2);
        when(bindingMapper.selectList(any())).thenReturn(List.of(produced, ref));

        List<AssetUsageVO> usages = service.listUsages(ASSET_ID);

        assertEquals(2, usages.size());
        assertTrue(usages.stream().anyMatch(u -> AssetBinding.BIND_PRODUCED.equals(u.getBindType())));
        assertTrue(usages.stream().anyMatch(u -> AssetBinding.BIND_REFERENCE.equals(u.getBindType())));
    }

    private AssetBinding binding(Long id, String bindType, Integer version) {
        AssetBinding b = new AssetBinding();
        b.setId(id);
        b.setAssetId(ASSET_ID);
        b.setAssetVersion(version);
        b.setCanvasId(CANVAS_ID);
        b.setNodeId(NODE_ID);
        b.setBindType(bindType);
        b.setCreatedBy(USER_ID);
        b.setCreatedAt(OffsetDateTime.now());
        return b;
    }
}
