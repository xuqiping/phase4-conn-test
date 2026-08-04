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
      :node-types="nodeTypes"
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
import { markRaw, ref } from 'vue'
import { Background } from '@vue-flow/background'
import { VueFlow, useVueFlow } from '@vue-flow/core'
import type { Connection, EdgeMouseEvent, NodeMouseEvent, NodeTypesObject } from '@vue-flow/core'
import type { CanvasEdge, CanvasNode, CanvasSnapshot } from '@/types/canvas'
import TextNode from './nodes/TextNode.vue'
import ImageNode from './nodes/ImageNode.vue'
import VideoNode from './nodes/VideoNode.vue'
import AudioNode from './nodes/AudioNode.vue'
import ScriptNode from './nodes/ScriptNode.vue'

/** 5 类节点 shape 注册（markRaw 规避响应式包裹组件对象，同 FlowCanvas 范式）。 */
const nodeTypes = {
  text: markRaw(TextNode),
  image: markRaw(ImageNode),
  video: markRaw(VideoNode),
  audio: markRaw(AudioNode),
  script: markRaw(ScriptNode)
} as unknown as NodeTypesObject

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

const emit = defineEmits<{
  (e: 'node-selected', node: CanvasNode | null): void
}>()

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
    type: parsed.type ?? 'text',
    position,
    data: { label: parsed.label ?? '新节点' }
  })
}

/** 新增节点（父组件调色板点击亦可调）。本地 CanvasNode 扁平类型，规避 vue-flow Node 泛型深递归（TS2589）。 */
function addNode(partial: { type?: string; position?: { x: number; y: number }; data?: Record<string, unknown> }) {
  const node: CanvasNode = {
    id: `node-${Date.now()}`,
    type: partial.type ?? 'text',
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
  // emit 数组中的真实 CanvasNode 引用，供属性面板直编 data（reactive 即时反映到画布）
  emit('node-selected', nodes.value.find(n => n.id === node.id) ?? null)
}

function onEdgeClick({ edge }: EdgeMouseEvent) {
  selectedNodeId.value = ''
  selectedEdgeId.value = edge.id
  boardRoot.value?.focus()
  emit('node-selected', null)
}

function onPaneClick() {
  selectedNodeId.value = ''
  selectedEdgeId.value = ''
  boardRoot.value?.focus()
  emit('node-selected', null)
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

/** 取节点真实引用（数组中的对象，reactive 即时反映画布）。 */
function getNode(nodeId: string): CanvasNode | null {
  return nodes.value.find(n => n.id === nodeId) ?? null
}

/** 取全部连线（C8 数据流解析 + C9 拓扑重跑用）。 */
function getEdges(): CanvasEdge[] {
  return edges.value
}

/** 取全部节点（C9 拓扑重跑用）。 */
function getNodes(): CanvasNode[] {
  return nodes.value
}

/**
 * 合并补丁进 node.data（C4+ 节点运行结果写回用）。
 * 直编数组中真实引用的 data，reactive 即时反映到画布渲染（同 PropertyPanel 范式）。
 */
function updateNodeData(nodeId: string, patch: Record<string, unknown>) {
  const n = nodes.value.find(x => x.id === nodeId)
  if (n) Object.assign(n.data, patch)
}

/** 程序化加边（焦点编辑/抽帧产新节点自动连源用）。 */
function addEdge(source: string, target: string) {
  if (source === target) return
  if (edges.value.some(e => e.source === source && e.target === target)) return
  edges.value.push({
    id: `edge-${source}-${target}-${Date.now()}`,
    source,
    target,
    type: 'smoothstep',
    style: { stroke: 'var(--color-primary)', strokeWidth: 2 }
  })
}

defineExpose({ addNode, addEdge, loadSnapshot, getSnapshot, getNode, getEdges, getNodes, updateNodeData })
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
