<script setup lang="ts">
import { computed, ref, watch, onMounted, markRaw } from 'vue'
import {
  VueFlow,
  useVueFlow,
  type Edge,
  type Node,
  type NodeTypesObject,
  type NodeMouseEvent
} from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { MiniMap } from '@vue-flow/minimap'
import { useTheme } from '@/theme/useTheme'
import type { MockNode, MockEdge, NodeKind } from '@/mocks/types'
import TextNode from './nodes/TextNode.vue'
import ImageNode from './nodes/ImageNode.vue'
import VideoNode from './nodes/VideoNode.vue'
import AudioNode from './nodes/AudioNode.vue'
import ScriptNode from './nodes/ScriptNode.vue'
import StoryboardNode from './nodes/StoryboardNode.vue'

const props = defineProps<{
  mockNodes: MockNode[]
  mockEdges: MockEdge[]
}>()

const emit = defineEmits<{ 'select-nodes': [MockNode[]] }>()

const { current } = useTheme()

// markRaw 规避响应式包裹组件对象；as unknown 绕 NodeComponent 签名差异（同 frontend 范式）
const nodeTypes = {
  text: markRaw(TextNode),
  image: markRaw(ImageNode),
  video: markRaw(VideoNode),
  audio: markRaw(AudioNode),
  script: markRaw(ScriptNode),
  storyboard: markRaw(StoryboardNode)
} as unknown as NodeTypesObject

const nodes = ref(
  props.mockNodes.map((n) => ({ id: n.id, type: n.type, position: n.position, data: n.data })) as Node[]
)

// 给每条边挂源节点种类 class：选中时 CSS 按类着类型色
const kindById = computed(() => {
  const m = new Map<string, NodeKind>()
  props.mockNodes.forEach((n) => m.set(n.id, n.type))
  return m
})

const edges = ref<Edge[]>(
  props.mockEdges.map((e) => ({
    id: e.id,
    source: e.source,
    target: e.target,
    type: 'flow',
    class: `edge-kind-${kindById.value.get(e.source) ?? 'text'}`
  }))
)

// 用 store 的 removeEdges 而非手写 filter（Edge 泛型在 filter 回调里触发 TS2589 深度实例化）
const { getSelectedNodes, removeEdges } = useVueFlow()

function onRemoveEdge(id: string) {
  removeEdges([id])
}
const selectedIds = ref<string[]>([])

function emitSelected() {
  emit(
    'select-nodes',
    props.mockNodes.filter((m) => selectedIds.value.includes(m.id))
  )
}

function onNodeClick(e: NodeMouseEvent) {
  selectedIds.value = [e.node.id]
  emitSelected()
}

function onPaneClick() {
  selectedIds.value = []
  emitSelected()
}

watch(getSelectedNodes, (sel) => {
  if (!sel.length && selectedIds.value.length <= 1) return // 单击流已处理，避免重复 emit
  selectedIds.value = sel.map((n) => n.id)
  emitSelected()
})

// 小地图颜色：CSS 变量进不了 SVG 属性，从 getComputedStyle 读实值；主题切换时重算
const minimapNodeColor = ref('#7c5cff')
const minimapMask = ref('rgba(7,11,20,0.7)')

function refreshMinimapColors() {
  const cs = getComputedStyle(document.documentElement)
  minimapNodeColor.value = cs.getPropertyValue('--accent').trim() || '#7c5cff'
  const sf0 = cs.getPropertyValue('--sf-0').trim()
  minimapMask.value = sf0.startsWith('#') ? `${sf0}b3` : 'rgba(7,11,20,0.7)'
}

onMounted(refreshMinimapColors)
watch(current, () => requestAnimationFrame(refreshMinimapColors))
</script>

<template>
  <div class="canvas-board">
    <VueFlow
      v-model:nodes="nodes"
      v-model:edges="edges"
      :node-types="nodeTypes"
      :only-render-visible-elements="true"
      :min-zoom="0.2"
      :max-zoom="2"
      fit-view-on-init
      :fit-view-options="{ padding: 0.25, maxZoom: 1 }"
      @node-click="onNodeClick"
      @pane-click="onPaneClick"
    >
      <Background :gap="24" :size="1.5" class="canvas-board__bg" />
      <MiniMap
        :node-color="minimapNodeColor"
        :mask-color="minimapMask"
        class="canvas-board__minimap"
        pannable
        zoomable
      />
      <template #edge-flow="edgeProps">
        <slot name="edge" v-bind="{ ...edgeProps, onRemove: onRemoveEdge }" />
      </template>
    </VueFlow>
  </div>
</template>

<style lang="scss">
@import '@vue-flow/core/dist/style.css';
@import '@vue-flow/core/dist/theme-default.css';
@import '@vue-flow/minimap/dist/style.css';

.canvas-board {
  height: 100%;
  background: var(--sf-0);

  &__bg {
    color: var(--line-1);
  }

  &__minimap {
    border-radius: var(--r-md);
    overflow: hidden;
    border: 1px solid var(--line-1);
  }
}

// 选中边取源节点类型色（class 挂在 vue-flow 的 edge 包裹 g 上）
.vue-flow__edge.selected.edge-kind-text path { stroke: var(--kind-text); }
.vue-flow__edge.selected.edge-kind-image path { stroke: var(--kind-image); }
.vue-flow__edge.selected.edge-kind-video path { stroke: var(--kind-video); }
.vue-flow__edge.selected.edge-kind-audio path { stroke: var(--kind-audio); }
.vue-flow__edge.selected.edge-kind-script path { stroke: var(--kind-script); }
.vue-flow__edge.selected.edge-kind-storyboard path { stroke: var(--kind-storyboard); }

// vue-flow 深色适配：容器透明化，让 var(--sf-0) 透出
.vue-flow {
  background: transparent;
  color: var(--tx-1);
}
.vue-flow__minimap {
  background: var(--sf-2);
}
.vue-flow__controls {
  display: none; // 用自写 CanvasToolbar
}
</style>
