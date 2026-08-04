<template>
  <div
    ref="boardRoot"
    class="canvas-board"
    tabindex="0"
    @dragover.prevent="onDragOver"
    @drop="onDrop"
    @keydown.delete.prevent="deleteSelected"
    @keydown.backspace.prevent="deleteSelected"
  >
    <VueFlow
      v-model:nodes="nodes"
      v-model:edges="edges"
      :default-edge-options="defaultEdgeOptions"
      :connection-line-style="connectionLineStyle"
      :snap-to-grid="true"
      :snap-grid="[16, 16]"
      fit-view-on-init
      @connect="onConnect"
      @node-click="onNodeClick"
      @edge-click="onEdgeClick"
      @pane-click="onPaneClick"
    >
      <Background :gap="20" :size="1" pattern-color="rgba(255,255,255,0.05)" />
    </VueFlow>

    <!-- 缩放/适应 工具条 -->
    <div class="canvas-board__toolbar">
      <button class="canvas-board__btn" title="放大" @click="() => vfZoomIn()">＋</button>
      <button class="canvas-board__btn" title="缩小" @click="() => vfZoomOut()">－</button>
      <button class="canvas-board__btn" title="适应视图" @click="() => vfFitView()">⤢</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Background } from '@vue-flow/background'
import { VueFlow, useVueFlow } from '@vue-flow/core'
import type { Connection, EdgeMouseEvent, NodeMouseEvent } from '@vue-flow/core'
import type { CanvasEdge, CanvasNode, CanvasSnapshot } from '@/types/canvas'

const {
  project,
  zoomIn: vfZoomIn,
  zoomOut: vfZoomOut,
  fitView: vfFitView,
  getViewport,
  vueFlowRef
} = useVueFlow({ id: 'infinite-canvas' })

const nodes = ref<CanvasNode[]>([])
const edges = ref<CanvasEdge[]>([])
/** 手选模式（同 FlowCanvas：onNodeClick 跟踪 id，规避 vue-flow Node.selected 联合类型不可达）。 */
const selectedNodeId = ref('')
const selectedEdgeId = ref('')
const boardRoot = ref<HTMLElement | null>(null)

const defaultEdgeOptions = {
  type: 'smoothstep',
  animated: false,
  style: { stroke: 'var(--color-primary)', strokeWidth: 2 }
}
const connectionLineStyle = { stroke: 'var(--color-primary)', strokeWidth: 2 }

/** 从节点调色板拖入：dataTransfer 带 {label}，落点转画布坐标。 */
function onDragOver(event: DragEvent) {
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
}

function onDrop(event: DragEvent) {
  const data = event.dataTransfer?.getData('application/vueflow')
  if (!data) return
  const parsed = JSON.parse(data)
  const { left, top } = (vueFlowRef.value as HTMLElement).getBoundingClientRect()
  const position = project({ x: event.clientX - left, y: event.clientY - top })
  addNode({
    type: parsed.type ?? 'default',
    position,
    data: { label: parsed.label ?? '新节点' }
  })
}

/** 新增节点（父组件调色板点击亦可调）。本地 CanvasNode 扁平类型，规避 vue-flow Node 泛型深递归（TS2589）。 */
function addNode(partial: { type?: string; position?: { x: number; y: number }; data?: Record<string, unknown> }) {
  const node: CanvasNode = {
    id: `node-${Date.now()}`,
    type: partial.type ?? 'default',
    position: partial.position ?? { x: Math.random() * 200 + 80, y: Math.random() * 120 + 80 },
    data: { label: '新节点', ...(partial.data ?? {}) }
  }
  nodes.value.push(node)
}

function onConnect(connection: Connection) {
  if (!connection.source || !connection.target) return
  const edge: CanvasEdge = {
    id: `edge-${connection.source}-${connection.target}-${Date.now()}`,
    source: connection.source,
    target: connection.target,
    sourceHandle: connection.sourceHandle || undefined,
    targetHandle: connection.targetHandle || undefined,
    type: 'smoothstep',
    style: { stroke: 'var(--color-primary)', strokeWidth: 2 }
  }
  edges.value.push(edge)
}

function onNodeClick({ node }: NodeMouseEvent) {
  selectedNodeId.value = node.id
  selectedEdgeId.value = ''
  boardRoot.value?.focus()
}

function onEdgeClick({ edge }: EdgeMouseEvent) {
  selectedNodeId.value = ''
  selectedEdgeId.value = edge.id
  boardRoot.value?.focus()
}

function onPaneClick() {
  selectedNodeId.value = ''
  selectedEdgeId.value = ''
  boardRoot.value?.focus()
}

function deleteSelected() {
  if (selectedNodeId.value) {
    removeNodes([selectedNodeId.value])
    selectedNodeId.value = ''
  } else if (selectedEdgeId.value) {
    removeEdges([selectedEdgeId.value])
    selectedEdgeId.value = ''
  }
}

function removeNodes(nodeIds: string[]) {
  const removeSet = new Set(nodeIds)
  nodes.value = nodes.value.filter(n => !removeSet.has(n.id))
  edges.value = edges.value.filter(e => !removeSet.has(e.source) && !removeSet.has(e.target))
}

function removeEdges(edgeIds: string[]) {
  const removeSet = new Set(edgeIds)
  edges.value = edges.value.filter(e => !removeSet.has(e.id))
}

/** 载入快照（从后端加载画布时调）。 */
function loadSnapshot(snap: CanvasSnapshot) {
  nodes.value = snap.nodes ?? []
  edges.value = snap.edges ?? []
}

/** 序列化快照（保存时调）。getViewport 是函数非 ref（@vue-flow/core 1.41）。 */
function getSnapshot(): CanvasSnapshot {
  const vp = getViewport()
  return {
    nodes: nodes.value,
    edges: edges.value,
    viewport: { x: vp.x, y: vp.y, zoom: vp.zoom }
  }
}

defineExpose({ addNode, loadSnapshot, getSnapshot })
</script>

<style lang="scss" scoped>
.canvas-board {
  flex: 1;
  height: 100%;
  position: relative;
  background: var(--color-bg);

  :deep(.vue-flow) {
    background: transparent;
  }

  :deep(.vue-flow__handle) {
    width: 10px;
    height: 10px;
    border: 2px solid var(--color-bg);
    background: var(--color-primary);
  }

  :deep(.vue-flow__edge-text) {
    fill: var(--color-text-primary);
    font-size: var(--font-size-xs);
  }
}

.canvas-board__toolbar {
  position: absolute;
  right: var(--spacing-3);
  bottom: var(--spacing-3);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-1);
  z-index: 10;
}

.canvas-board__btn {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-base);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  font-size: 16px;
  cursor: pointer;
  transition: all var(--duration-instant) var(--ease-in-out);

  &:hover {
    color: var(--color-primary);
    border-color: var(--color-primary);
  }
}
</style>
