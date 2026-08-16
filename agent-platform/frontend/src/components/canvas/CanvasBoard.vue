<template>
  <div
    ref="boardRoot"
    class="canvas-board"
    tabindex="0"
    @dragover.prevent="onDragOver"
    @drop="onDrop"
    @dblclick="onDblClick"
    @contextmenu.prevent
    @keydown.delete.prevent="deleteSelected"
    @keydown.backspace.prevent="deleteSelected"
  >
    <VueFlow
      v-model:nodes="nodes"
      v-model:edges="edges"
      :node-types="nodeTypes"
      :edge-types="edgeTypes"
      :default-edge-options="defaultEdgeOptions"
      :connection-line-style="connectionLineStyle"
      :snap-to-grid="true"
      :snap-grid="[16, 16]"
      fit-view-on-init
      :delete-key-code="null"
      :pan-on-drag="dragMode === 'pan'"
      :selection-key-code="dragMode === 'select' ? true : 'Shift'"
      @selection-end="onSelectionEnd"
      @connect="onConnect"
      @connect-start="onConnectStart"
      @connect-end="onConnectEnd"
      @node-click="onNodeClick"
      @node-context-menu="onNodeContextMenu"
      @node-drag-stop="onNodeDragStop"
      @edge-click="onEdgeClick"
      @pane-click="onPaneClick"
    >
      <Background :gap="20" :size="1" pattern-color="rgba(255,255,255,0.05)" />
    </VueFlow>

    <!-- 缩放/适应 工具条 -->
    <div class="canvas-board__toolbar">
      <!-- 交互模式切换：pan=左键拖拽平移画布（默认）；select=左键拖框批量选节点（Windows 式，免按 Shift） -->
      <button
        class="canvas-board__btn"
        :class="{ 'canvas-board__btn--active': dragMode === 'pan' }"
        title="拖拽画布模式：左键拖动平移画布（Shift+拖框仍可临时框选）"
        :aria-pressed="dragMode === 'pan'"
        @click="setDragMode('pan')"
      >
        ✋
      </button>
      <button
        class="canvas-board__btn"
        :class="{ 'canvas-board__btn--active': dragMode === 'select' }"
        title="框选节点模式：左键按住拖出选框批量选中节点（同 Windows 框选）；滚轮缩放，平移请切回 ✋"
        :aria-pressed="dragMode === 'select'"
        @click="setDragMode('select')"
      >
        ▭
      </button>
      <span class="canvas-board__toolbar-sep" aria-hidden="true"></span>
      <button class="canvas-board__btn" title="放大" @click="() => vfZoomIn()">＋</button>
      <button class="canvas-board__btn" title="缩小" @click="() => vfZoomOut()">－</button>
      <button class="canvas-board__btn" title="适应视图" @click="() => vfFitView()">⤢</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { markRaw, nextTick, onMounted, onUnmounted, provide, ref, watch } from 'vue'
import { Background } from '@vue-flow/background'
import { VueFlow, useVueFlow } from '@vue-flow/core'
import type { Connection, EdgeMouseEvent, EdgeTypesObject, NodeMouseEvent, NodeTypesObject, OnConnectStartParams } from '@vue-flow/core'
import type { CanvasEdge, CanvasNode, CanvasSnapshot } from '@/types/canvas'
import { uniqueLabel } from '@/utils/interpolate'
import TextNode from './nodes/TextNode.vue'
import ImageNode from './nodes/ImageNode.vue'
import VideoNode from './nodes/VideoNode.vue'
import AudioNode from './nodes/AudioNode.vue'
import ScriptNode from './nodes/ScriptNode.vue'
import StoryboardNode from './nodes/StoryboardNode.vue'
import DeletableEdge from './edges/DeletableEdge.vue'

/** 6 类节点 shape 注册（markRaw 规避响应式包裹组件对象，同 FlowCanvas 范式）。 */
const nodeTypes = {
  text: markRaw(TextNode),
  image: markRaw(ImageNode),
  video: markRaw(VideoNode),
  audio: markRaw(AudioNode),
  script: markRaw(ScriptNode),
  storyboard: markRaw(StoryboardNode)
} as unknown as NodeTypesObject

/** 自定义边：贝塞尔弧线 + 中点「×」删除按钮（点按钮即删，无需键盘，可发现性强）。 */
const edgeTypes = {
  deletable: markRaw(DeletableEdge)
} as unknown as EdgeTypesObject

const {
  project,
  zoomIn: vfZoomIn,
  zoomOut: vfZoomOut,
  fitView: vfFitView,
  getViewport,
  getSelectedNodes,
  vueFlowRef
} = useVueFlow({ id: 'infinite-canvas' })

const nodes = ref<CanvasNode[]>([])
const edges = ref<CanvasEdge[]>([])
/** 节点 id 自增序号（防批量 addNode 同毫秒撞 id）。 */
let seqCounter = 0
/** 手选模式（同 FlowCanvas：onNodeClick 跟踪 id，规避 vue-flow Node.selected 联合类型不可达）。 */
const selectedNodeId = ref('')
const selectedEdgeId = ref('')
/**
 * 3x-C1 多选集：Shift 框选（vue-flow 原生 selectionKeyCode='Shift'）结束时同步。
 * 库内部 selected 只做视觉高亮；应用层以此集合驱动批量工具条/批量删除。
 * delete-key-code 已置 null —— 删除只走应用层，杜绝「库 Backspace 静默删 + 应用单删」双删。
 */
const multiSelectedIds = ref<string[]>([])
const boardRoot = ref<HTMLElement | null>(null)

/**
 * 交互模式（拖拽画布 vs 框选节点）：
 * pan（默认）= 左键拖拽平移画布，Shift+拖框仍可临时框选（selectionKeyCode='Shift'）；
 * select = 左键按住拖出选框批量选中节点（Windows 式框选，免按 Shift）。
 * @vue-flow/core 1.48 无 selectionOnDrag prop：select 态用 selectionKeyCode=true（恒选）
 * + panOnDrag=false（任何拖拽都不再平移）实现；此模式下游走画布靠滚轮缩放/适应视图，平移切回 ✋ 模式。
 */
type DragMode = 'pan' | 'select'
const dragMode = ref<DragMode>('pan')
function setDragMode(mode: DragMode) {
  dragMode.value = mode
}

const emit = defineEmits<{
  (e: 'node-selected', node: CanvasNode | null): void
  /** 3x-C1：框选结束/清空 → 同步多选集给父（≥2 驱动批量工具条；[] 表示回到单选/空选）。 */
  (e: 'nodes-selected', ids: string[]): void
  /** S12：节点右键 → 父开「存入资产库」弹窗（L5）。 */
  (e: 'node-context-menu', node: CanvasNode): void
  /** C6：双击画布空白处 → 父开「快速加节点」搜索框（坐标已转画布坐标系）。 */
  (e: 'quick-add', position: { x: number; y: number }): void
  /**
   * 2x-6：从节点输出句柄拉线到空白处松手 → 同 quick-add，但携带拉线起点 nodeId，
   * 父组件建完节点后自动连线（ComfyUI 式拖线建节点）。
   */
  (e: 'quick-add', position: { x: number; y: number }, sourceNodeId: string): void
  /** 结构变更（连线增/删、节点拖动结束）→ 父 scheduleSave 落库。 */
  (e: 'structure-changed'): void
}>()

const defaultEdgeOptions = {
  type: 'deletable', // 自定义边：贝塞尔 + 中点删除按钮（原 default 无删除入口）
  animated: false,
  style: { stroke: 'var(--color-primary)', strokeWidth: 1.5 }
}
const connectionLineStyle = { stroke: 'var(--color-primary)', strokeWidth: 1.5 }

/**
 * 选中边视觉反馈：watch selectedEdgeId → 给选中边打 class（高亮描边，提示可 Delete 删除）。
 * vue-flow 边渲染在子组件内，scoped 样式需 :deep 穿透（同 A1 chip 修复范式）。
 */
watch(selectedEdgeId, (id) => {
  for (const e of edges.value) {
    e.class = e.id === id ? 'canvas-edge--selected' : ''
  }
})

/**
 * 3x-C1：节点被外部程序删除（父组件批量删/拆分镜整批替换）时，修剪多选集防悬挂 id
 * （悬挂 id 再走批量删除=空操作无害，但工具条计数会错）。
 */
watch(nodes, (list) => {
  if (!multiSelectedIds.value.length) return
  const alive = new Set(list.map(n => n.id))
  const next = multiSelectedIds.value.filter(id => alive.has(id))
  if (next.length !== multiSelectedIds.value.length) {
    multiSelectedIds.value = next
    emit('nodes-selected', next)
  }
}, { deep: false })

/**
 * 全局 Delete/Backspace 删除（focus 无关）。boardRoot 上的 @keydown 仅在 boardRoot 聚焦时触发，
 * 用户点边后若焦点被属性面板/输入框/IME 抢占则 Delete 失效——window 监听兜底。
 * 排除可编辑元素（input/textarea/contenteditable/naive 控件），避免误删用户输入。
 */
function onWindowKeydown(e: KeyboardEvent) {
  // Esc：清空多选（不删）——框选误操作最顺手的退出键
  if (e.key === 'Escape' && multiSelectedIds.value.length) {
    clearMultiSelection()
    return
  }
  if (e.key !== 'Delete' && e.key !== 'Backspace') return
  if (!selectedNodeId.value && !selectedEdgeId.value && !multiSelectedIds.value.length) return
  const t = e.target as HTMLElement | null
  if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable
    || t.closest('.n-input,.n-base-selection,.mention-ta'))) return
  e.preventDefault()
  deleteSelected()
}
onMounted(() => window.addEventListener('keydown', onWindowKeydown))
onUnmounted(() => window.removeEventListener('keydown', onWindowKeydown))

/** 暴露给自定义边 DeletableEdge 的删除回调（统一走 removeEdges → emit structure-changed 落库）。 */
provide('canvasRemoveEdge', (id: string) => {
  removeEdges([id])
  if (selectedEdgeId.value === id) selectedEdgeId.value = ''
})

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

/**
 * C6：双击画布空白处 → emit 坐标给父开「快速加节点」搜索框（ComfyUI 式）。
 * 仅空白处触发：点节点(.vue-flow__node)/连线(.vue-flow__edge)/句柄(.vue-flow__handle)不弹，避免误加。
 * 坐标复用 onDrop 的 project 范式（clientXY − vueFlow 容器偏移 → 画布坐标系，兼容缩放/平移）。
 */
function onDblClick(event: MouseEvent) {
  const tgt = event.target as HTMLElement | null
  if (
    tgt?.closest('.vue-flow__node') ||
    tgt?.closest('.vue-flow__edge') ||
    tgt?.closest('.vue-flow__handle')
  ) {
    return
  }
  const vf = vueFlowRef.value as HTMLElement | null
  if (!vf) return
  const { left, top } = vf.getBoundingClientRect()
  const position = project({ x: event.clientX - left, y: event.clientY - top })
  emit('quick-add', position)
}

/**
 * 新增节点（父组件调色板点击亦可调）。本地 CanvasNode 扁平类型，规避 vue-flow Node 泛型深递归（TS2589）。
 * L9 节点命名唯一：label 撞名自动追加序号（图片 → 图片 2），覆盖新建/粘贴两入口
 * （重命名查重在 PropertyPanel.onRenameBlur，占位符存 id 不受 label 改名影响）。
 */
function addNode(partial: { type?: string; position?: { x: number; y: number }; data?: Record<string, unknown> }): string {
  const baseLabel = String(partial.data?.label ?? '新节点')
  const existing = nodes.value.map((n) => String(n.data.label ?? ''))
  // label 放 spread 之后，确保去重值覆盖 partial.data 自带 label（L9 三入口）
  // id 加 seqCounter 后缀防批量撞：脚本拆分镜同毫秒内连调 N 次 addNode，
  // Date.now() 相同会撞 id → vue-flow 重复告警 + 渲染错乱。
  const node: CanvasNode = {
    id: `node-${Date.now()}-${seqCounter++}`,
    type: partial.type ?? 'text',
    position: partial.position ?? { x: Math.random() * 200 + 80, y: Math.random() * 120 + 80 },
    data: { ...(partial.data ?? {}), label: uniqueLabel(baseLabel, existing) }
  }
  nodes.value.push(node)
  return node.id
}

function onConnect(connection: Connection) {
  if (!connection.source || !connection.target) return
  const edge: CanvasEdge = {
    id: `edge-${connection.source}-${connection.target}-${Date.now()}`,
    source: connection.source,
    target: connection.target,
    sourceHandle: connection.sourceHandle || undefined,
    targetHandle: connection.targetHandle || undefined,
    type: 'deletable', // 贝塞尔 + 中点删除按钮（同 defaultEdgeOptions）
    style: { stroke: 'var(--color-primary)', strokeWidth: 1.5 }
  }
  edges.value.push(edge)
  justConnected = true
  emit('structure-changed')
}

/**
 * 2x-6：拉线建节点支持。connect-start 记下起点（仅 source 句柄），
 * connect-end 时若本次拖拽没有成功连上（onConnect 未触发）且落点在空白处，
 * 就地弹「快速加节点」并携带起点 id——父组件建完节点自动连线。
 */
const connectStartParams = ref<OnConnectStartParams | null>(null)
let justConnected = false

function onConnectStart(params: OnConnectStartParams) {
  connectStartParams.value = params
  justConnected = false
}

function onConnectEnd(event: MouseEvent | TouchEvent | undefined) {
  const start = connectStartParams.value
  connectStartParams.value = null
  const connected = justConnected
  justConnected = false
  if (!start || connected) return
  if (start.handleType !== 'source') return // 只支持从输出句柄向前拉
  if (!start.nodeId) return
  const clientX = event instanceof MouseEvent ? event.clientX
    : event && 'changedTouches' in event && event.changedTouches.length ? event.changedTouches[0].clientX : null
  const clientY = event instanceof MouseEvent ? event.clientY
    : event && 'changedTouches' in event && event.changedTouches.length ? event.changedTouches[0].clientY : null
  if (clientX == null || clientY == null) return
  const tgt = event ? (event.target as HTMLElement | null) : null
  // 落在节点/句柄上（没对准目标句柄）不开弹窗，按 vue-flow 原语义放弃本次连线
  if (tgt?.closest('.vue-flow__node') || tgt?.closest('.vue-flow__handle')) return
  const vf = vueFlowRef.value as HTMLElement | null
  if (!vf) return
  const { left, top } = vf.getBoundingClientRect()
  const position = project({ x: clientX - left, y: clientY - top })
  emit('quick-add', position, start.nodeId)
}

function onNodeClick({ node }: NodeMouseEvent) {
  selectedNodeId.value = node.id
  selectedEdgeId.value = ''
  clearMultiSelection()
  boardRoot.value?.focus()
  // emit 数组中的真实 CanvasNode 引用，供属性面板直编 data（reactive 即时反映到画布）
  emit('node-selected', nodes.value.find(n => n.id === node.id) ?? null)
}

/**
 * 3x-C1：Shift 框选结束（vue-flow 原生 UserSelection 拖框）。nextTick 等库把 selected
 * 写回节点后读 getSelectedNodes。1 个 → 归一回单选面板；≥2 → 多选集上报（父显批量工具条）。
 * 库在普通单击/点空白时会自行重置内部选中，这里只同步应用层状态。
 */
function onSelectionEnd() {
  nextTick(() => {
    const ids = getSelectedNodes.value.map((n: { id: string }) => n.id)
    if (ids.length >= 2) {
      selectedNodeId.value = ''
      selectedEdgeId.value = ''
      multiSelectedIds.value = ids
      emit('node-selected', null)
      emit('nodes-selected', ids)
    } else if (ids.length === 1) {
      clearMultiSelection()
      selectedNodeId.value = ids[0]
      emit('node-selected', nodes.value.find(n => n.id === ids[0]) ?? null)
    } else {
      clearMultiSelection()
    }
  })
}

/** 清空多选集并通知父（回单选/空选模式）。 */
function clearMultiSelection() {
  if (!multiSelectedIds.value.length) return
  multiSelectedIds.value = []
  emit('nodes-selected', [])
}

/**
 * A1：@chip 点击 → 按 id 聚焦节点（选中 + 居中视口）。
 * 复用 onNodeClick 的选中语义（selectedNodeId + emit node-selected → 属性面板切到该节点），
 * 叠加 vfFitView({nodes:[id]}) 把该节点滚入视口中心（maxZoom 限制防过度放大）。
 */
function focusNodeById(id: string) {
  const n = nodes.value.find((x) => x.id === id)
  if (!n) return
  selectedNodeId.value = id
  selectedEdgeId.value = ''
  clearMultiSelection()
  emit('node-selected', n)
  nextTick(() => {
    vfFitView({ nodes: [id], padding: 0.4, duration: 300, maxZoom: 1.2 })
  })
}

/**
 * S12：节点右键（@node-context-menu）→ emit 真实节点引用给父开「存入资产库」弹窗。
 * boardRoot 上的 @contextmenu.prevent 已拦掉浏览器默认菜单。
 */
function onNodeContextMenu({ node }: NodeMouseEvent) {
  selectedNodeId.value = node.id
  selectedEdgeId.value = ''
  clearMultiSelection()
  const real = nodes.value.find(n => n.id === node.id)
  if (real) emit('node-context-menu', real)
}

function onEdgeClick({ edge }: EdgeMouseEvent) {
  selectedNodeId.value = ''
  selectedEdgeId.value = edge.id
  clearMultiSelection()
  boardRoot.value?.focus()
  emit('node-selected', null)
}

function onPaneClick() {
  selectedNodeId.value = ''
  selectedEdgeId.value = ''
  clearMultiSelection()
  boardRoot.value?.focus()
  emit('node-selected', null)
}

function deleteSelected() {
  // 3x-C1：多选批量删除优先（removeNodes 连带删边 + structure-changed 落库）
  if (multiSelectedIds.value.length) {
    removeNodes([...multiSelectedIds.value])
    clearMultiSelection()
    return
  }
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
  emit('structure-changed')
}

function removeEdges(edgeIds: string[]) {
  const removeSet = new Set(edgeIds)
  edges.value = edges.value.filter(e => !removeSet.has(e.id))
  emit('structure-changed')
}

/** 节点拖动结束 → 落库新位置（vue-flow @node-drag-stop）。 */
function onNodeDragStop() {
  emit('structure-changed')
}

/** 载入快照（从后端加载画布时调）。 */
function loadSnapshot(snap: CanvasSnapshot) {
  nodes.value = snap.nodes ?? []
  // 旧画布边为 smoothstep/default 无删除入口 → 统一归一为 deletable（贝塞尔+删除按钮）
  edges.value = (snap.edges ?? []).map(e => ({ ...e, type: 'deletable' }))
}

/** 序列化快照（保存时调）。getViewport 是函数非 ref（@vue-flow/core 1.41）。 */
function getSnapshot(): CanvasSnapshot {
  const vp = getViewport()
  return {
    nodes: nodes.value,
    // 剥离选中态 class（纯前端视觉，不入库；重载后由 watch 按 selectedEdgeId='' 重置）
    edges: edges.value.map(({ class: _class, ...rest }) => rest),
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
    type: 'deletable', // 贝塞尔 + 中点删除按钮（同 defaultEdgeOptions）
    style: { stroke: 'var(--color-primary)', strokeWidth: 1.5 }
  })
}

defineExpose({ addNode, addEdge, removeNodes, loadSnapshot, getSnapshot, getNode, getEdges, getNodes, updateNodeData, focusNodeById, dragMode, setDragMode })
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

  /* 选中边高亮：红色加粗描边 + 手型，提示点 Delete 可删（class 由 watch 注入 edge.class） */
  :deep(.canvas-edge--selected .vue-flow__edge-path) {
    stroke: #ef4444;
    stroke-width: 3;
    cursor: pointer;
  }

  :deep(.canvas-edge--selected) {
    cursor: pointer;
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

  /* 当前激活的交互模式按钮：主题色高亮 */
  &--active {
    color: var(--color-primary);
    border-color: var(--color-primary);
    background: color-mix(in srgb, var(--color-primary) 12%, transparent);
  }
}

/* 模式按钮与缩放按钮之间的分隔线 */
.canvas-board__toolbar-sep {
  width: 20px;
  height: 1px;
  margin: 2px auto;
  background: var(--color-border);
}
</style>
