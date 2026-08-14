<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import CanvasBoard from '@/components/canvas/CanvasBoard.vue'
import CanvasToolbar from '@/components/canvas/CanvasToolbar.vue'
import FlowEdge from '@/components/canvas/edges/FlowEdge.vue'
import { demoNodes, demoEdges, genStressNodes, resolveNodeCount } from '@/mocks/canvas'
import { KIND_LABEL } from '@/mocks/canvas'
import type { MockNode } from '@/mocks/types'
import type { EdgeProps } from '@vue-flow/core'

// ?nodes=N 压测开关（运维入口）：非法回退演示流
const route = useRoute()
const stressN = resolveNodeCount(route.query.nodes as string | null)
const stress = stressN ? genStressNodes(stressN) : null

const mockNodes = stress?.nodes ?? demoNodes
const mockEdges = stress?.edges ?? demoEdges

// 右侧属性面板壳：选中联动（L3）
const selected = ref<MockNode[]>([])
function onSelectNodes(list: MockNode[]) {
  selected.value = list
}

const STATUS_LABEL: Record<string, string> = {
  idle: '待生成',
  running: '生成中',
  success: '完成',
  failed: '失败'
}

const panelTitle = computed(() =>
  selected.value.length === 0
    ? '属性'
    : selected.value.length === 1
      ? selected.value[0].data.label || '未命名'
      : `已选 ${selected.value.length} 个节点`
)
</script>

<template>
  <div class="canvas-view">
    <div class="canvas-view__board">
      <CanvasBoard :mock-nodes="mockNodes" :mock-edges="mockEdges" @select-nodes="onSelectNodes">
        <template #edge="edgeProps">
          <FlowEdge
            v-bind="(edgeProps as EdgeProps)"
            @remove="edgeProps.onRemove"
          />
        </template>
      </CanvasBoard>
      <CanvasToolbar :node-count="mockNodes.length" />
    </div>

    <aside class="canvas-view__panel">
      <h3 class="canvas-view__panel-title">{{ panelTitle }}</h3>
      <template v-if="selected.length === 1">
        <dl class="canvas-view__kv">
          <dt>类型</dt>
          <dd>{{ KIND_LABEL[selected[0].data.kind] }}</dd>
          <dt>状态</dt>
          <dd>{{ STATUS_LABEL[selected[0].data.status] }}</dd>
          <dt>序号</dt>
          <dd class="mono">{{ selected[0].data.sceneNo ?? '—' }}</dd>
          <dt>耗时</dt>
          <dd class="mono">{{ selected[0].data.durationMs ? (selected[0].data.durationMs / 1000).toFixed(1) + 's' : '—' }}</dd>
        </dl>
      </template>
      <p v-else-if="selected.length > 1" class="canvas-view__hint">多选状态：批量操作入口（演示壳）</p>
      <p v-else class="canvas-view__hint">点击画布节点查看属性</p>
    </aside>
  </div>
</template>

<style lang="scss" scoped>
.canvas-view {
  display: flex;
  flex: 1;
  min-height: 0;

  &__board {
    position: relative;
    flex: 1;
    min-width: 0;
  }

  &__panel {
    width: 260px;
    flex-shrink: 0;
    border-left: 1px solid var(--line-1);
    background: var(--sf-1);
    padding: var(--sp-4);
  }

  &__panel-title {
    margin: 0 0 var(--sp-3);
    font-size: var(--fs-md);
    color: var(--tx-1);
  }

  &__kv {
    margin: 0;
    display: grid;
    grid-template-columns: 56px 1fr;
    row-gap: var(--sp-2);
    font-size: var(--fs-sm);

    dt {
      color: var(--tx-3);
    }
    dd {
      margin: 0;
      color: var(--tx-1);
    }
    .mono {
      font-family: var(--font-mono);
    }
  }

  &__hint {
    color: var(--tx-3);
    font-size: var(--fs-sm);
  }
}
</style>
