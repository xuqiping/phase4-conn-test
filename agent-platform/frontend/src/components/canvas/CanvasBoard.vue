<template>
  <div
    ref="boardRoot"
    class="canvas-board"
    :class="{ 'canvas-board--connecting': connectingEdge }"
    tabindex="0"
    @dragover.prevent="onDragOver"
    @drop="onDrop"
    @dblclick="onDblClick"
    @contextmenu="onRootContextMenu"
    @mousemove="onRootMouseMove"
    @paste="onPaste"
    @keydown.delete.prevent="deleteSelected"
    @keydown.backspace.prevent="deleteSelected"
    @keydown="onKeydownUndo"
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
      @node-drag-start="onNodeDragStart"
      @node-drag-stop="onNodeDragStop"
      @nodes-change="onNodesChange"
      @edge-click="onEdgeClick"
      @pane-click="onPaneClick"
    >
      <Background :gap="20" :size="1" pattern-color="rgba(255,255,255,0.05)" />
    </VueFlow>

    <!-- 2x 四轮 S9：组包围盒渲染层（rAF 合帧；框体穿透不可点，头部可改名/解组）。
         修复VIII（VIII-1 ①⑤）：组端口（右缘中点=输出/聚合、左缘中点=输入/广播）+ 组边 SVG 层。 -->
    <div class="canvas-board__groups">
      <div
        v-for="b in groupBoxes"
        :key="b.id"
        class="canvas-board__groupbox"
        :class="{ 'canvas-board__groupbox--selected': b.id === groupSelectedId }"
        :style="{
          left: `${b.left}px`,
          top: `${b.top}px`,
          width: `${b.width}px`,
          height: `${b.height}px`,
          borderColor: b.color
        }"
      >
        <div
          class="canvas-board__groupbox-head"
          :style="{ background: b.color }"
          @pointerdown.self="selectGroup(b.id)"
        >
          <span
            class="canvas-board__groupbox-name"
            role="button"
            tabindex="0"
            title="点击重命名组"
            @click="onGroupRenameClick(b.id)"
            @keydown.enter.prevent="onGroupRenameClick(b.id)"
          >{{ b.name }} · {{ b.count }}</span>
          <button type="button" class="canvas-board__groupbox-x" title="解散分组" @click="ungroupGroup(b.id)">✕</button>
        </div>
        <!-- 修复VIII（VIII-1 ①）：组端口——pointer-events:auto 同组头（框体其余穿透）；
             拖线走自绘 overlay（非 vue-flow 连接线，坑 4），松手进 A3 分派。 -->
        <button
          type="button"
          class="canvas-board__groupbox-port canvas-board__groupbox-port--source"
          :class="{ 'canvas-board__groupbox-port--dragging': isGroupPortDragging(b.id, 'source') }"
          aria-label="组输出端口"
          title="组输出端口：从整组向外拖线（聚合：组内全部成员的产物都喂给对端）"
          :data-group-id="b.id"
          @pointerdown.prevent="onGroupPortPointerDown($event, b.id, 'source')"
        ></button>
        <button
          type="button"
          class="canvas-board__groupbox-port canvas-board__groupbox-port--target"
          :class="{ 'canvas-board__groupbox-port--dragging': isGroupPortDragging(b.id, 'target') }"
          aria-label="组输入端口"
          title="组输入端口：从外部拖线到整组（广播：外部输入喂给组内全部成员）"
          :data-group-id="b.id"
          @pointerdown.prevent="onGroupPortPointerDown($event, b.id, 'target')"
        ></button>
      </div>
    </div>

    <!-- 修复VIII（VIII-1 ⑤）：组边 SVG 覆盖层（组层同栈）——贝塞尔 + hover 辉光 + 选中红粗
         （selectedEdgeId 复用）+ 中点 × 删除（canvasRemoveEdge 同款链）。层容器穿透，
         仅路径与 × 可点；几何随 scheduleGroupBounds 的 rAF 脏标记重绘（坑 5）。 -->
    <svg class="canvas-board__groupedges" aria-hidden="true">
      <g
        v-for="v in groupEdgeViews"
        :key="v.id"
        class="canvas-board__groupedge"
        :class="{ 'canvas-board__groupedge--selected': v.id === selectedEdgeId }"
      >
        <path
          class="canvas-board__groupedge-path"
          :d="v.d"
          @click.stop="onGroupEdgeClick(v.id)"
        ></path>
        <g
          class="canvas-board__groupedge-del"
          :transform="`translate(${v.mx}, ${v.my})`"
          @click.stop="onGroupEdgeDelete(v.id)"
        >
          <circle r="9"></circle>
          <text y="3.5" text-anchor="middle">×</text>
        </g>
      </g>
      <!-- 组端口拖线临时贝塞尔（pointer capture 会话；pointercancel 兜底清理，联动点 7） -->
      <path v-if="groupDragPath" class="canvas-board__groupedge-ghost" :d="groupDragPath"></path>
    </svg>

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
      <!-- 2x 五轮：撤回/重做（结构操作 50 步；Ctrl+Z / Ctrl+Shift+Z） -->
      <button
        class="canvas-board__btn"
        :disabled="!canUndo"
        title="撤回（Ctrl+Z）"
        :aria-disabled="!canUndo"
        @click="undo()"
      >
        ↩︎
      </button>
      <button
        class="canvas-board__btn"
        :disabled="!canRedo"
        title="重做（Ctrl+Shift+Z）"
        :aria-disabled="!canRedo"
        @click="redo()"
      >
        ↪︎
      </button>
      <span class="canvas-board__toolbar-sep" aria-hidden="true"></span>
      <!-- 2x 四轮 S5：只看关联——藏无关节点（visibility，布局不动可逆）；无节点选中时禁用 -->
      <button
        class="canvas-board__btn"
        :class="{ 'canvas-board__btn--active': relatedOnly }"
        :disabled="!relatedInfo"
        title="只看关联：隐藏与选中节点无连线的节点（再点恢复）"
        :aria-pressed="relatedOnly"
        @click="relatedOnly = !relatedOnly"
      >
        🔗
      </button>
      <!-- 修复IX-2（Q4 拍板）：连线保留总开关——一个开关治理两处（复制粘贴跨集边 / 创建副本连线克隆）。
           开=副本带连线（允许平行重复边）；关=两处都不保留原节点连线。粘贴按粘贴当下开关态生效。 -->
      <button
        class="canvas-board__btn"
        :class="{ 'canvas-board__btn--active': keepLinksOnCopy }"
        title="连线保留（复制粘贴/创建副本）：开=副本保留原节点连线（跨集边接入原上下文，允许平行重复边）；关=副本不带原节点连线（全新独立）。粘贴时按当下开关态生效"
        aria-label="连线保留开关（复制粘贴与创建副本是否保留原节点连线）"
        :aria-pressed="keepLinksOnCopy"
        @click="toggleKeepLinksOnCopy()"
      >
        ⛓
      </button>
      <!-- 修复VII（2x 增补②）：一键整理布局——dagre LR 分层；选中时只排选中子图（组整组拉入） -->
      <button
        class="canvas-board__btn"
        :disabled="!nodes.length"
        title="一键整理布局：按上游→下游从左到右重排（选中节点时只排选中，Ctrl+Z 可撤回）"
        aria-label="一键整理布局（从左到右重排，可撤回）"
        :aria-disabled="!nodes.length"
        @click="onAutoLayout"
      >
        ✨
      </button>
      <button class="canvas-board__btn" title="放大" @click="() => vfZoomIn()">＋</button>
      <button class="canvas-board__btn" title="缩小" @click="() => vfZoomOut()">－</button>
      <button class="canvas-board__btn" title="适应视图" @click="() => vfFitView()">⤢</button>
    </div>

    <!-- 修复XI A2（2x 未解决①）：画布空白右键菜单——「添加节点」7 类（paletteItems 单源）
         +「画布操作」（粘贴/撤销/重做/一键整理，零新逻辑纯入口接线）。范式照 FlowCanvas：
         全屏透明 overlay（左键他处关；右键他处=冒泡到根 handler 挪位置不叠两层，spec ⑦）；
         菜单 clientX/Y 定位贴边翻转。Esc 关菜单不外传（逐层退，Lightbox R7 口径）。 -->
    <div
      v-if="ctxMenu.visible"
      class="canvas-board__ctx-overlay"
      @click="closeContextMenu"
      @contextmenu.prevent
    >
      <div
        class="canvas-board__ctx-menu"
        role="menu"
        aria-label="画布右键菜单"
        :style="ctxMenuStyle"
        @click.stop
      >
        <div class="canvas-board__ctx-title">添加节点</div>
        <button
          v-for="item in PALETTE_ITEMS"
          :key="item.type"
          type="button"
          class="canvas-board__ctx-item"
          role="menuitem"
          @click="ctxAddNode(item.type, item.label)"
        >
          <n-icon size="14" :component="item.icon" />
          <span>{{ item.label }}</span>
        </button>
        <div class="canvas-board__ctx-sep" aria-hidden="true"></div>
        <div class="canvas-board__ctx-title">画布操作</div>
        <button
          type="button"
          class="canvas-board__ctx-item"
          role="menuitem"
          :disabled="!clipboard"
          :aria-disabled="!clipboard"
          @click="ctxPaste()"
        >
          <span class="canvas-board__ctx-glyph">📋</span>
          <span>粘贴</span>
          <span class="canvas-board__ctx-kbd">Ctrl+V</span>
        </button>
        <button
          type="button"
          class="canvas-board__ctx-item"
          role="menuitem"
          :disabled="!canUndo"
          :aria-disabled="!canUndo"
          @click="ctxUndo()"
        >
          <span class="canvas-board__ctx-glyph">↩︎</span>
          <span>撤销</span>
          <span class="canvas-board__ctx-kbd">Ctrl+Z</span>
        </button>
        <button
          type="button"
          class="canvas-board__ctx-item"
          role="menuitem"
          :disabled="!canRedo"
          :aria-disabled="!canRedo"
          @click="ctxRedo()"
        >
          <span class="canvas-board__ctx-glyph">↪︎</span>
          <span>重做</span>
          <span class="canvas-board__ctx-kbd">Ctrl+Shift+Z</span>
        </button>
        <button
          type="button"
          class="canvas-board__ctx-item"
          role="menuitem"
          :disabled="!nodes.length"
          :aria-disabled="!nodes.length"
          @click="ctxAutoLayout()"
        >
          <span class="canvas-board__ctx-glyph">✨</span>
          <span>一键整理布局</span>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, markRaw, nextTick, onMounted, onUnmounted, provide, reactive, ref, watch } from 'vue'
import { Background } from '@vue-flow/background'
import { VueFlow, useVueFlow } from '@vue-flow/core'
import type { Connection, EdgeMouseEvent, EdgeTypesObject, NodeChange, NodeMouseEvent, NodeTypesObject, OnConnectStartParams } from '@vue-flow/core'
import type { CanvasEdge, CanvasGroup, CanvasNode, CanvasNodeData, CanvasSnapshot } from '@/types/canvas'
import { NIcon } from 'naive-ui'
import { PALETTE_ITEMS } from './paletteItems'
import { uniqueLabel } from '@/utils/interpolate'
import { relatedClosure, type GraphClosure } from '@/utils/graphClosure'
import { MAX_GROUP_MEMBERS, nextGroupColor } from '@/utils/groupCandidates'
import { isEditableTarget } from '@/utils/mediaLimits'
import { computeAutoLayout } from '@/utils/autoLayout'
import {
  decideDropTarget,
  groupEndpointOf,
  groupIdOf,
  isGroupEndpoint,
  mergeSnapshotEdges,
  resolveEdgesForFlow,
  splitSnapshotEdges,
  type GroupRectLike
} from '@/utils/groupEdges'
import { buildCopySet, planLabels, planPastePositions, remapCrossEdges, remapEdges, type CanvasClipboard } from './canvasClipboard'
import { keepLinksOnCopy, toggleKeepLinksOnCopy } from '@/utils/canvasPrefs'
import TextNode from './nodes/TextNode.vue'
import ImageNode from './nodes/ImageNode.vue'
import VideoNode from './nodes/VideoNode.vue'
import AudioNode from './nodes/AudioNode.vue'
import ScriptNode from './nodes/ScriptNode.vue'
import StoryboardNode from './nodes/StoryboardNode.vue'
import DirectorNode from './nodes/DirectorNode.vue'
import DeletableEdge from './edges/DeletableEdge.vue'
import { DIRECTOR_BRIDGE_KEY } from './directorBridge'

/** 7 类节点 shape 注册（markRaw 规避响应式包裹组件对象，同 FlowCanvas 范式）。 */
const nodeTypes = {
  text: markRaw(TextNode),
  image: markRaw(ImageNode),
  video: markRaw(VideoNode),
  audio: markRaw(AudioNode),
  script: markRaw(ScriptNode),
  storyboard: markRaw(StoryboardNode),
  director: markRaw(DirectorNode)
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
  onMove,
  vueFlowRef,
  setNodes: vfSetNodes,
  setEdges: vfSetEdges,
  getNodes: vfGetNodes,
  getEdges: vfGetEdges
} = useVueFlow({ id: 'infinite-canvas' })

const nodes = ref<CanvasNode[]>([])
const edges = ref<CanvasEdge[]>([])

/**
 * 2x 六轮 #2：vue-flow v-model 双向同步的「回写暂停窗」丢同步兜底。
 * useWatchProps 的 store→model 回写（models.nodes.value = [...store.nodes]）会 pauseModel 到 nextTick；
 * 同一拍内连发的第二次 addNode/addEdge（标注→AI 修改链实测）正落进该窗口 → push 进父数组但
 * store 永远收不到（节点不渲染、连线不见，删任一节点触发重同步才现形；快照持久化却是全的）。
 * 每个结构写后挂宏任务对账：宏任务时所有 microtask（含暂停解除/回写）已排空，
 * store 与本地数组数量不符即整量对齐（正常路径恒等 → no-op）。
 */
let storeReconcileScheduled = false
function scheduleStoreReconcile() {
  if (storeReconcileScheduled) return
  storeReconcileScheduled = true
  setTimeout(() => {
    storeReconcileScheduled = false
    try {
      if (vfGetNodes.value.length !== nodes.value.length) vfSetNodes([...nodes.value])
      if (vfGetEdges.value.length !== edges.value.length) vfSetEdges([...edges.value])
    } catch {
      /* store 未就绪（挂载前）静默跳过——applySnapshot 会走引用替换正常同步 */
    }
  }, 0)
}
/** 2x 四轮 S9：节点组（框选成组）。成员关系只存组侧，节点 data 零感知。 */
const groups = ref<CanvasGroup[]>([])
/**
 * 修复VIII（VIII-1 ②）：组边集合（source/target 含 `group:{groupId}` 伪 id）。
 * **绝不进 VueFlow v-model edges**——伪 id 引用不存在节点，进库渲染断裂/告警刷屏（坑 1）；
 * getSnapshot 合并落库、applySnapshot 拆分恢复、历史快照随 getSnapshot 的 merge 口径
 * 一并定格（坑 10：解散组 Ctrl+Z 组边随快照齐恢复）。组成员增删不动组边（广播/聚合
 * 随 memberIds 动态生效，VIII-1 ⑦）。
 */
const groupEdges = ref<CanvasEdge[]>([])
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
/**
 * 修复XI（XI-4 D1）：组选态——点组框空白=选整组（高亮+整组拖动/带组复制复用）。
 * 与节点选中/多选互斥（selectGroup 清其余，节点选中链反清组选）；Delete 不接组选（Q5 拍板）。
 */
const groupSelectedId = ref<string | null>(null)
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
  /** 修复III C6（2x-6）：单击已有产物的媒体节点 → 父层开 Lightbox 统一预览。 */
  (e: 'preview-media', payload: { kind: 'image' | 'video'; src: string; poster?: string }): void
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
  /** 结构变更（连线增/删、节点拖动结束/组建删改名）→ 父 scheduleSave 落库。 */
  (e: 'structure-changed'): void
  /** 2x 四轮 S9：组名点击重命名 → 父开改名弹窗（输入交互在 CanvasView），确认后回调 renameGroup。 */
  (e: 'group-rename-request', group: CanvasGroup): void
  /** 导演台 Step 7：节点卡片按钮/双击 → 父开导演台 modal（节点 emit 不冒泡，走桥+DOM 双路）。 */
  (e: 'open-director', nodeId: string): void
  /**
   * 修复VI（2x 未解决②）：OS 本地文件拖入画布 → 抛给父分流（image/video/audio 各建对应
   * 节点，未知类型/超限由父 toast 拒）。坐标已转画布坐标系。与 palette 内部拖拽
   * （application/vueflow MIME）互斥：先查内部 MIME 再查 files。
   */
  (e: 'pane-drop-files', payload: { files: File[]; position: { x: number; y: number } }): void
  /** 修复VI（2x 未解决①）：画布空白处 Ctrl+V 剪贴板图片 → 抛给父上传建图片节点。 */
  (e: 'pane-paste-files', payload: { files: File[]; position: { x: number; y: number } }): void
  /** 修复VII（2x 增补①）：Ctrl+C 复制节点成功 → 父层 toast（Board 无 message 上下文）。 */
  (e: 'nodes-copied', count: number): void
}>()

/** 导演台节点 → 画布桥：节点组件 inject 调 openEditor，本组件上抛父（Vue Flow 节点 emit 不冒泡）。 */
provide(DIRECTOR_BRIDGE_KEY, {
  openEditor: (nodeId: string) => emit('open-director', nodeId)
})

const defaultEdgeOptions = {
  type: 'deletable', // 自定义边：贝塞尔 + 中点删除按钮（原 default 无删除入口）
  animated: false,
  style: { stroke: 'var(--color-primary)', strokeWidth: 1.5 }
}
const connectionLineStyle = { stroke: 'var(--color-primary)', strokeWidth: 1.5 }

/**
 * 2x 四轮 S5：关联高亮集合（memo）。computed 只依赖选集与边集——节点拖动改 position
 * 不触发重算（BFS 闭包纯函数在 utils/graphClosure.ts，菱形/环单测覆盖）。
 * 无节点选中（含只选了边）→ null，还原全部视觉态。
 */
const relatedInfo = computed<GraphClosure | null>(() => {
  const seeds = multiSelectedIds.value.length
    ? [...multiSelectedIds.value]
    : selectedNodeId.value ? [selectedNodeId.value] : []
  // 修复VIII P4 人工反馈：闭包输入=普通边+组边展开合并集（resolveEdgesForFlow 广播/聚合
  // 口径，与 CanvasView resolvedFlowEdges 同源）——否则点组边对端节点时组员被误判无关
  // 而半透明（组边 source/target 是 group:{id} 伪端点，普通 BFS 摸不到）。
  return relatedClosure(seeds, resolveEdgesForFlow(
    [...edges.value, ...groupEdges.value],
    groups.value
  ))
})

/** 「只看关联」开关（无关节点 visibility:hidden，可逆；无选中时按钮禁用）。 */
const relatedOnly = ref(false)

/**
 * 视觉态 class 统一注入（2x 四轮 S5 起取代单选边 watch）：
 * - 边：选中(红/可删) > 关联(--related 加粗辉光) > 高亮态下无关(--dimmed) > 无高亮('');
 * - 节点：高亮态下无关节点 --dimmed（透明 0.25），开关再叠 --hidden（visibility 藏，布局不动）。
 * class 均为会话态，getSnapshot 剥离不入快照。
 */
/** 2x 四轮 S9：节点 id → 所属组（一节点仅属一组；建组时自动移出旧组）。 */
const nodeGroupMap = computed<Map<string, CanvasGroup>>(() => {
  const m = new Map<string, CanvasGroup>()
  for (const g of groups.value) for (const id of g.memberIds) m.set(id, g)
  return m
})

/**
 * 修复VIII（VIII-1 ⑨）：选中组边 → 端点展开为节点集（组端=全部成员、节点端=该节点）。
 * 非组边选中（普通边）→ null（走原 relatedClosure 口径，不叠加）。
 */
const groupEdgeGlowIds = computed<Set<string> | null>(() => {
  const id = selectedEdgeId.value
  if (!id) return null
  const e = groupEdges.value.find(x => x.id === id)
  if (!e) return null
  const ids = new Set<string>()
  for (const endpoint of [e.source, e.target]) {
    if (isGroupEndpoint(endpoint)) {
      const g = groups.value.find(x => x.id === groupIdOf(endpoint))
      if (g) for (const mid of g.memberIds) ids.add(mid)
    } else {
      ids.add(endpoint)
    }
  }
  return ids
})

function applyVisualClasses() {
  const info = relatedInfo.value
  for (const e of edges.value) {
    e.class = e.id === selectedEdgeId.value
      ? 'canvas-edge--selected'
      : !info ? ''
        : info.edgeIds.has(e.id) ? 'canvas-edge--related'
        : 'canvas-edge--dimmed'
  }
  const gmap = nodeGroupMap.value
  // 修复VIII（VIII-1 ⑨）：选中组边 → 组成员+对端节点 related 辉光（端点展开为节点集）
  const glow = groupEdgeGlowIds.value
  for (const n of nodes.value) {
    const cls: string[] = []
    if (glow?.has(n.id)) cls.push('canvas-node--related')
    else if (info && !info.nodeIds.has(n.id)) {
      cls.push('canvas-node--dimmed')
      if (relatedOnly.value) cls.push('canvas-node--hidden')
    }
    const g = gmap.get(n.id)
    if (g) cls.push('canvas-node--grouped')
    n.class = cls.join(' ')
    // 组色 ring：CSS 变量注入 wrapper style（会话态，getSnapshot 剥离；宽度仍由 nodeSizeStyle 真源推导）
    if (g) n.style = { ...nodeSizeStyle(n.data), '--group-color': g.color }
    else if (n.style && '--group-color' in n.style) n.style = nodeSizeStyle(n.data)
  }
}
watch([selectedNodeId, selectedEdgeId, multiSelectedIds, relatedOnly, relatedInfo, nodeGroupMap, groupEdgeGlowIds], applyVisualClasses)

// ---- 2x 四轮 S9：节点组（建/解/改名 + 成员修剪 + 包围盒渲染层） ----

/**
 * 框选建组（批量工具条「设为组」）。成员先按存活节点过滤+去重；
 * 入新组自动移出旧组（一节点仅属一组）；旧组被掏空即解散。上限 50。
 * 返回 {ok:false,reason} 由父组件 message 提示（Board 无 message 服务）。
 */
function createGroup(name: string, memberIds: string[]): { ok: boolean; reason?: string } {
  const alive = new Set(nodes.value.map(n => n.id))
  const ids = [...new Set(memberIds)].filter(id => alive.has(id))
  if (ids.length < 2) return { ok: false, reason: '组内至少需要 2 个存活节点' }
  if (ids.length > MAX_GROUP_MEMBERS) {
    return { ok: false, reason: `组成员上限 ${MAX_GROUP_MEMBERS}（本次 ${ids.length}）` }
  }
  pushHistory('group') // 校验通过才入栈（失败入栈=无变化垃圾撤回步）
  const g: CanvasGroup = {
    id: `group-${Date.now()}-${seqCounter++}`,
    name: name.trim() || `组${groups.value.length + 1}`,
    memberIds: ids,
    color: nextGroupColor(groups.value)
  }
  const memberSet = new Set(ids)
  const emptied: string[] = []
  for (const other of groups.value) {
    if (other.id === g.id) continue
    const next = other.memberIds.filter(id => !memberSet.has(id))
    if (next.length !== other.memberIds.length) other.memberIds = next
    if (!next.length) emptied.push(other.id)
  }
  groups.value = groups.value.filter(x => x.memberIds.length > 0) // 掏空即解散
  dropGroupEdgesOfGroups(emptied) // 修复VIII（VIII-1 ⑦）：连带解散的旧组组边级联删
  groups.value.push(g)
  emit('structure-changed')
  return { ok: true }
}

/**
 * 修复VIII（VIII-1 ⑦）：组解散/删除 → 该组全部组边级联删（VIII-1 ⑦）。
 * 选中态正挂被删组边 → 一并清空（Delete/红粗态不悬挂）；返回是否有删动。
 */
function dropGroupEdgesOfGroups(groupIds: string[]): boolean {
  if (!groupIds.length || !groupEdges.value.length) return false
  const pseudo = new Set(groupIds.map(groupEndpointOf))
  const kept: CanvasEdge[] = []
  let removedSelected = false
  for (const e of groupEdges.value) {
    if (pseudo.has(e.source) || pseudo.has(e.target)) {
      if (e.id === selectedEdgeId.value) removedSelected = true
    } else {
      kept.push(e)
    }
  }
  if (kept.length === groupEdges.value.length) return false
  groupEdges.value = kept
  if (removedSelected) selectedEdgeId.value = ''
  return true
}

/** 修复VIII（VIII-1 ⑦）：对端节点删除 → 该节点名下组边级联删（与普通边 removeNodes 同口径）。 */
function dropGroupEdgesOfNodes(nodeIds: string[]): boolean {
  if (!nodeIds.length || !groupEdges.value.length) return false
  const ids = new Set(nodeIds)
  const kept: CanvasEdge[] = []
  let removedSelected = false
  for (const e of groupEdges.value) {
    if (ids.has(e.source) || ids.has(e.target)) {
      if (e.id === selectedEdgeId.value) removedSelected = true
    } else {
      kept.push(e)
    }
  }
  if (kept.length === groupEdges.value.length) return false
  groupEdges.value = kept
  if (removedSelected) selectedEdgeId.value = ''
  return true
}

/** 解组（包围盒头部 ✕）：删组不删节点。修复VIII：该组组边级联删（VIII-1 ⑦）。 */
function ungroupGroup(groupId: string) {
  if (!groups.value.some(g => g.id === groupId)) return
  pushHistory('group')
  groups.value = groups.value.filter(g => g.id !== groupId)
  dropGroupEdgesOfGroups([groupId])
  emit('structure-changed')
}

/** 组改名（父组件弹窗确认后回调）。 */
function renameGroup(groupId: string, name: string) {
  const g = groups.value.find(x => x.id === groupId)
  const n = name.trim()
  if (!g || !n || g.name === n) return
  pushHistory('group')
  g.name = n
  emit('structure-changed')
}

/** 取全部组（父组件 @候选并集用）。 */
function getGroups(): CanvasGroup[] {
  return groups.value
}

/** 组名点击 → 上抛父开改名弹窗（携带组实体）。 */
function onGroupRenameClick(groupId: string) {
  const g = groups.value.find(x => x.id === groupId)
  if (g) emit('group-rename-request', g)
}

/** 视口变换（onMove 同步；包围盒屏幕定位 = 画布坐标 × zoom + 平移）。 */
const vpTransform = ref({ x: 0, y: 0, zoom: 1 })
onMove(({ flowTransform }) => {
  vpTransform.value = { x: flowTransform.x, y: flowTransform.y, zoom: flowTransform.zoom }
  scheduleGroupBounds()
})

/** 组包围盒屏幕矩形（rAF 合帧重算——拖节点/缩放平移每帧只算一次）。 */
interface GroupBox {
  id: string
  name: string
  color: string
  count: number
  left: number
  top: number
  width: number
  height: number
}
const groupBoxes = ref<GroupBox[]>([])
let boundsRaf = 0
function scheduleGroupBounds() {
  if (boundsRaf) return
  boundsRaf = requestAnimationFrame(() => {
    boundsRaf = 0
    const vp = vpTransform.value
    const boxes: GroupBox[] = []
    for (const g of groups.value) {
      let minX = Infinity
      let minY = Infinity
      let maxX = -Infinity
      let maxY = -Infinity
      let count = 0
      for (const id of g.memberIds) {
        const n = nodes.value.find(x => x.id === id)
        if (!n) continue
        count++
        const { w, h } = nodeSizeOf(n)
        minX = Math.min(minX, n.position.x)
        minY = Math.min(minY, n.position.y)
        maxX = Math.max(maxX, n.position.x + w)
        maxY = Math.max(maxY, n.position.y + h)
      }
      if (!count) continue
      const pad = 12
      boxes.push({
        id: g.id,
        name: g.name,
        color: g.color,
        count,
        left: (minX - pad) * vp.zoom + vp.x,
        top: (minY - pad) * vp.zoom + vp.y,
        width: (maxX - minX + pad * 2) * vp.zoom,
        height: (maxY - minY + pad * 2) * vp.zoom
      })
    }
    groupBoxes.value = boxes
    // 修复VIII（VIII-1 ⑤）：组边几何同帧重算（bounds/节点位置/组员变动才会走到这——
    // 脏标记合帧，静止不重绘，坑 5）；选中/hover 是响应式 class 绑定，不进 rAF。
    rebuildGroupEdgeViews(boxes)
  })
}
watch([nodes, groups, vpTransform, groupEdges], scheduleGroupBounds, { deep: true })

// ---- 修复VIII（VIII-1 ⑤）：组边 SVG 覆盖层（几何派生，数据真源=groupEdges） ----

/** 组边渲染视图（rAF 产物：path d + 中点 × 定位；id 对齐 groupEdges 条目）。 */
interface GroupEdgeView {
  id: string
  d: string
  mx: number
  my: number
}
const groupEdgeViews = ref<GroupEdgeView[]>([])

/** 贝塞尔 path + 中点（控制点水平外伸，观感对齐 DeletableEdge 的 getBezierPath）。 */
function groupEdgePath(x1: number, y1: number, x2: number, y2: number): { d: string; mx: number; my: number } {
  const c = Math.max(40, Math.abs(x2 - x1) / 2)
  const c1x = x1 + c
  const c2x = x2 - c
  return {
    d: `M ${x1},${y1} C ${c1x},${y1} ${c2x},${y2} ${x2},${y2}`,
    mx: (x1 + 3 * c1x + 3 * c2x + x2) / 8,
    my: (y1 + y2) / 2
  }
}

/**
 * 端点锚点（board px）：组=包围盒边缘中点（source 右缘=输出端口位、target 左缘=输入端口位，
 * 与组端口渲染位置一致）；节点=handle 侧中点（position+nodeSizeOf 测量尺寸，同包围盒口径）。
 * 解析失败（组已散/节点已删）→ null 跳过该边渲染（数据层级联已保证罕见，双保险）。
 */
function anchorOf(
  endpoint: string,
  side: 'source' | 'target',
  boxById: Map<string, GroupBox>,
  nodeById: Map<string, CanvasNode>
): { x: number; y: number } | null {
  const vp = vpTransform.value
  if (isGroupEndpoint(endpoint)) {
    const b = boxById.get(groupIdOf(endpoint))
    if (!b) return null
    return side === 'source'
      ? { x: b.left + b.width, y: b.top + b.height / 2 }
      : { x: b.left, y: b.top + b.height / 2 }
  }
  const n = nodeById.get(endpoint)
  if (!n) return null
  const { w, h } = nodeSizeOf(n)
  const x = side === 'source' ? n.position.x + w : n.position.x
  return { x: x * vp.zoom + vp.x, y: (n.position.y + h / 2) * vp.zoom + vp.y }
}

/** 组边几何重算（rAF 内调用，boxes 已含视口换算）。 */
function rebuildGroupEdgeViews(boxes: GroupBox[]) {
  const boxById = new Map(boxes.map(b => [b.id, b]))
  const nodeById = new Map(nodes.value.map(n => [n.id, n]))
  const views: GroupEdgeView[] = []
  for (const e of groupEdges.value) {
    const p1 = anchorOf(e.source, 'source', boxById, nodeById)
    const p2 = anchorOf(e.target, 'target', boxById, nodeById)
    if (!p1 || !p2) continue
    const { d, mx, my } = groupEdgePath(p1.x, p1.y, p2.x, p2.y)
    views.push({ id: e.id, d, mx, my })
  }
  groupEdgeViews.value = views
}

/**
 * 组包围盒（画布坐标系，连线落点 hit 判定用）——与渲染盒同口径（pad 12、nodeSizeOf 尺寸），
 * 但不乘视口（screenToFlow 后的坐标在 flow 空间）。只在松手一瞬同步计算，频率极低（坑 5 不缓存）。
 */
function groupFlowRects(): GroupRectLike[] {
  const out: GroupRectLike[] = []
  for (const g of groups.value) {
    let minX = Infinity
    let minY = Infinity
    let maxX = -Infinity
    let maxY = -Infinity
    let count = 0
    for (const id of g.memberIds) {
      const n = nodes.value.find(x => x.id === id)
      if (!n) continue
      count++
      const { w, h } = nodeSizeOf(n)
      minX = Math.min(minX, n.position.x)
      minY = Math.min(minY, n.position.y)
      maxX = Math.max(maxX, n.position.x + w)
      maxY = Math.max(maxY, n.position.y + h)
    }
    if (!count) continue
    const pad = 12
    out.push({ id: g.id, left: minX - pad, top: minY - pad, right: maxX + pad, bottom: maxY + pad })
  }
  return out
}

/** 组边选中（复用 selectedEdgeId 红粗/关联辉光链；清节点选中同 onEdgeClick 口径）。 */
function onGroupEdgeClick(id: string) {
  selectedNodeId.value = ''
  selectedEdgeId.value = id
  clearMultiSelection()
  boardRoot.value?.focus()
  emit('node-selected', null)
}

/** 组边中点 × 删除（canvasRemoveEdge 同款链：removeEdges 双集过滤 + 清选中 + structure-changed 落库）。 */
function onGroupEdgeDelete(id: string) {
  removeEdges([id])
  if (selectedEdgeId.value === id) selectedEdgeId.value = ''
}

// ---- 修复VIII（VIII-1 ③）：组端口拖线会话（自绘 overlay，坑 4；A3 分派收口） ----

/** 拖线会话（pointer capture + window 监听双保险；pointercancel/卸载兜底零残留）。 */
interface GroupDragState {
  groupId: string
  side: 'source' | 'target'
}
const groupDrag = ref<GroupDragState | null>(null)
/** 拖线末端（board px；null=无会话）。 */
const groupDragPos = ref<{ x: number; y: number } | null>(null)

function isGroupPortDragging(groupId: string, side: 'source' | 'target'): boolean {
  return groupDrag.value?.groupId === groupId && groupDrag.value.side === side
}

/** client → board px（临时线/锚点同一坐标空间）。 */
function pointerToBoardPx(clientX: number, clientY: number): { x: number; y: number } {
  const rect = boardRoot.value?.getBoundingClientRect()
  if (!rect) return { x: clientX, y: clientY }
  return { x: clientX - rect.left, y: clientY - rect.top }
}

/** 拖线起点锚点（=该组输出/输入端口的渲染位置）。 */
function groupDragAnchor(st: GroupDragState): { x: number; y: number } | null {
  const b = groupBoxes.value.find(x => x.id === st.groupId)
  if (!b) return null
  return st.side === 'source'
    ? { x: b.left + b.width, y: b.top + b.height / 2 }
    : { x: b.left, y: b.top + b.height / 2 }
}

/** 拖线临时贝塞尔（board px；null=无会话）。 */
const groupDragPath = computed(() => {
  const st = groupDrag.value
  const pos = groupDragPos.value
  if (!st || !pos) return null
  const anchor = groupDragAnchor(st)
  if (!anchor) return null
  return groupEdgePath(anchor.x, anchor.y, pos.x, pos.y).d
})

function onGroupPortPointerDown(event: PointerEvent, groupId: string, side: 'source' | 'target') {
  if (event.button !== 0) return
  if (groupDrag.value) return
  groupDrag.value = { groupId, side }
  groupDragPos.value = pointerToBoardPx(event.clientX, event.clientY)
  connectingEdge.value = true // 拖线中：淡化节点悬停恢复（CSS 同款口径）
  try {
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
  } catch {
    /* capture 拿不到不影响：window 监听兜底收 pointerup/cancel */
  }
  window.addEventListener('pointermove', onGroupDragMove)
  window.addEventListener('pointerup', onGroupDragUp)
  window.addEventListener('pointercancel', onGroupDragCancel)
  window.addEventListener('blur', onGroupDragCancel) // review 补：窗外松手丢 pointerup 时防悬挂（下次点击幻影建边）
}

function onGroupDragMove(event: PointerEvent) {
  if (!groupDrag.value) return
  event.preventDefault()
  groupDragPos.value = pointerToBoardPx(event.clientX, event.clientY)
}

function detachGroupDragListeners() {
  window.removeEventListener('pointermove', onGroupDragMove)
  window.removeEventListener('pointerup', onGroupDragUp)
  window.removeEventListener('pointercancel', onGroupDragCancel)
  window.removeEventListener('blur', onGroupDragCancel)
}

/** 拖线取消（pointercancel/组件卸载兜底）：零残留（联动点 7）。 */
function cancelGroupDrag() {
  detachGroupDragListeners()
  groupDrag.value = null
  groupDragPos.value = null
  connectingEdge.value = false
}
const onGroupDragCancel = () => cancelGroupDrag()

function onGroupDragUp(event: PointerEvent) {
  const st = groupDrag.value
  if (!st) {
    cancelGroupDrag()
    return
  }
  detachGroupDragListeners()
  groupDrag.value = null
  groupDragPos.value = null
  connectingEdge.value = false
  dispatchGroupPortDrop(st, event)
}
onUnmounted(() => {
  if (boundsRaf) cancelAnimationFrame(boundsRaf)
  cancelGroupDrag() // 修复VIII：组端口拖线会话兜底清理（window 监听不悬挂）
})

/** 节点渲染尺寸：优先 vue-flow 实测 dimensions，回落 data.width/height（默认 200×120）。 */
function nodeSizeOf(n: CanvasNode): { w: number; h: number } {
  const dims = (n as CanvasNode & { dimensions?: { width: number; height: number } }).dimensions
  if (dims && dims.width > 0 && dims.height > 0) return { w: dims.width, h: dims.height }
  return {
    w: typeof n.data.width === 'number' ? n.data.width : 200,
    h: typeof n.data.height === 'number' ? n.data.height : 120
  }
}

/**
 * 3x-C1：节点被外部程序删除（父组件批量删/拆分镜整批替换）时，修剪多选集防悬挂 id
 * （悬挂 id 再走批量删除=空操作无害，但工具条计数会错）。
 * 2x 四轮 S9：同步修剪组员引用（删成员→组减员；组内全删→组自解散，L5）。
 */
watch(nodes, (list) => {
  const alive = new Set(list.map(n => n.id))
  if (multiSelectedIds.value.length) {
    const next = multiSelectedIds.value.filter(id => alive.has(id))
    if (next.length !== multiSelectedIds.value.length) {
      multiSelectedIds.value = next
      emit('nodes-selected', next)
    }
  }
  if (groups.value.length) {
    let pruned = false
    const dissolved: string[] = []
    for (const g of groups.value) {
      const next = g.memberIds.filter(id => alive.has(id))
      if (next.length !== g.memberIds.length) {
        g.memberIds = next
        pruned = true
      }
      if (!next.length) dissolved.push(g.id)
    }
    const before = groups.value.length
    groups.value = groups.value.filter(g => g.memberIds.length > 0)
    // 修复VIII（VIII-1 ⑦）：成员全删自解散 → 该组组边级联删（联动点 1）
    const droppedEdges = dropGroupEdgesOfGroups(dissolved)
    if (pruned || groups.value.length !== before || droppedEdges) {
      scheduleGroupBounds()
      emit('structure-changed')
    }
  }
  // 修复XI D1：组选态正挂被解散组（含 loadSnapshot 整体换 groups 清空的路径）→ 清空防悬挂
  if (groupSelectedId.value && !groups.value.some(g => g.id === groupSelectedId.value)) {
    groupSelectedId.value = null
  }
}, { deep: false })

/**
 * 全局 Delete/Backspace 删除（focus 无关）。boardRoot 上的 @keydown 仅在 boardRoot 聚焦时触发，
 * 用户点边后若焦点被属性面板/输入框/IME 抢占则 Delete 失效——window 监听兜底。
 * 排除可编辑元素（input/textarea/contenteditable/naive 控件），避免误删用户输入。
 */
function onWindowKeydown(e: KeyboardEvent) {
  // Esc：清空多选/组选（不删、不吞事件——组选非模态，菜单/灯箱才吞）——框选误操作最顺手的退出键
  if (e.key === 'Escape' && (multiSelectedIds.value.length || groupSelectedId.value)) {
    clearMultiSelection()
    groupSelectedId.value = null
    return
  }
  // 修复VII（2x 增补①）：Ctrl/Cmd+C 复制 / V 粘贴节点子图（VII-1）。先于 Delete 分支，
  // 可编辑守卫在各 handler 内（焦点在输入框=正常复制/粘贴文本，联动点 2 半选边界）。
  if ((e.ctrlKey || e.metaKey) && !e.altKey) {
    const k = e.key.toLowerCase()
    if (k === 'c') { onCopyKeydown(e); return }
    if (k === 'v') { onPasteKeydown(e); return }
  }
  if (e.key !== 'Delete' && e.key !== 'Backspace') return
  if (!selectedNodeId.value && !selectedEdgeId.value && !multiSelectedIds.value.length) return
  // 修复XI D1 实测暴露的存量隐患：target 可能是 window/document（无 closest，isTypingTarget
  // 注释早有预警）——统一走 isTypingTarget（instanceof 守卫 + 同款可编辑全集），不再裸调 closest。
  if (isTypingTarget(e.target)) return
  e.preventDefault()
  deleteSelected()
}
onMounted(() => {
  window.addEventListener('keydown', onWindowKeydown)
  // 修复XI D1：捕获段预判组框空白点击（先于 vue-flow pane 处理；boardRoot 内任何 target 均过此）
  boardRoot.value?.addEventListener('pointerdown', onBoardPointerDownCapture, { capture: true })
})
onUnmounted(() => {
  window.removeEventListener('keydown', onWindowKeydown)
  boardRoot.value?.removeEventListener('pointerdown', onBoardPointerDownCapture, { capture: true })
})

// ---- 修复VII（2x 增补①）：节点复制粘贴（VII-1，Q1 多选子图 / Q2 鼠标落点） ----

/** 组件内剪贴板（会话态：重进画布即清，不跨画布——规格 §6/§8）。null=外部图片粘贴通道。 */
const clipboard = ref<CanvasClipboard | null>(null)

/** 正在打字判定（Delete 守卫同款全集：原生可编辑 + naive 控件 + @输入框，防复制粘贴劫持输入）。
 * target 可能是 window/document（无 closest）——非 Element 直接按非打字处理。 */
function isTypingTarget(t: EventTarget | null): boolean {
  const el = t instanceof HTMLElement ? t : null
  if (!el) return false
  return isEditableTarget(el) || !!el.closest('.n-input,.n-base-selection,.mention-ta')
}

/** Ctrl/Cmd+C：选中集（多选优先退单选）→ buildCopySet；无选中=清剪贴板且不拦（联动点 1 反向恢复）。 */
function onCopyKeydown(e: KeyboardEvent) {
  if (isTypingTarget(e.target)) return
  const selected = multiSelectedIds.value.length
    ? [...multiSelectedIds.value]
    : (selectedNodeId.value ? [selectedNodeId.value] : [])
  if (!selected.length) {
    clipboard.value = null
    return
  }
  // 修复X（X-3）：普通边+组边混合传入——组端点跨集边（节点↔组）进剪贴板（plan 细化3）。
  const clip = buildCopySet(nodes.value, [...edges.value, ...groupEdges.value], selected)
  if (!clip) {
    clipboard.value = null
    return
  }
  clipboard.value = clip
  e.preventDefault()
  emit('nodes-copied', clip.items.length)
}

/**
 * Ctrl/Cmd+V：剪贴板非空 → preventDefault + 粘贴子图；为空 → 不拦（原生 paste 事件落
 * onPaste 图片链，修复VI）。keydown preventDefault 后 paste 事件不再触发——天然防
 * 「节点+图片双建」（坑 2，三级优先链：内部剪贴板 > 图片文件 > 浏览器默认）。
 */
function onPasteKeydown(e: KeyboardEvent) {
  if (isTypingTarget(e.target)) return
  if (!clipboard.value) return
  e.preventDefault()
  pasteSubgraph()
}

/**
 * 成批原子粘贴（坑 1）：**禁走 addNode/appendEdges**（逐个 pushHistory('add')+'edge'
 * 两次入栈 → 一次粘贴拆两步撤回、中间态「节点没了边还在」）。此处一次 pushHistory
 * ('paste') + 批写 nodes/edges + 单次 structure-changed = 一步撤回，pasteCount 逐次
 * +32 错开（Q2），落点=鼠标画布坐标（无记录回落视口中心，onPaste 同款口径）。
 */
function pasteSubgraph(atClient?: { x: number; y: number }) {
  const clip = clipboard.value
  if (!clip) return
  const rect = (vueFlowRef.value as HTMLElement | null)?.getBoundingClientRect()
    ?? { left: 0, top: 0, width: 0, height: 0 }
  // 修复XI A2（XI-1⑤）：落点参数化——右键菜单「粘贴」传右键点坐标；键盘 Ctrl+V 不传，
  // 维持修复VI 口径（最近鼠标位置 > 视口中心回落）。
  const cx = atClient ? atClient.x : (hasPointer ? lastClient.x : rect.left + rect.width / 2)
  const cy = atClient ? atClient.y : (hasPointer ? lastClient.y : rect.top + rect.height / 2)
  const target = project({ x: cx - rect.left, y: cy - rect.top })
  const positions = planPastePositions(clip, target)
  const labels = planLabels(clip, nodes.value.map(n => String(n.data.label ?? '')))
  pushHistory('paste')
  const keyToNewId = new Map<string, string>()
  positions.forEach((p, i) => {
    const item = clip.items[i]
    const id = `node-${Date.now()}-${seqCounter++}`
    keyToNewId.set(item.key, id)
    // JSON 深拷贝（P4 交叉 review Y2）：浅展开会让同批粘贴体/剪贴板条目共享嵌套对象
    // （cropRect 等），未来任一嵌套原地写会串写全部副本——违背「副本完全独立」口径。
    // 同 buildCopySet/nodeClone 的深拷贝范式。
    const data = JSON.parse(JSON.stringify(item.data)) as CanvasNodeData
    data.label = labels[i]
    nodes.value.push({
      id,
      type: item.type,
      position: { x: p.x, y: p.y },
      data,
      style: nodeSizeStyle(data)
    })
  })
  for (const e of remapEdges(clip, keyToNewId)) edges.value.push(e)
  // 修复IX-2 B2（Q4 拍板）：粘贴时点判定——开关开 → 跨集边单侧重映射补连线（集内端→新节点，
  // 集外端保原 id=副本接入原上下文）；关 → 零跨集边。alive 集含刚 push 的新节点（悬挂防护
  // 只滤「集外端点已删」）。平行重复边允许并存（Q4 口径，不 dedup）。
  // 修复X（X-3）：alive 增组集（groups 粘贴时点快照——复制后解散的组丢边不产断边）；
  // 产物分流——组端点边进 groupEdges 池（伪 id 绝不进 v-model），普通边照旧。
  if (keepLinksOnCopy.value && clip.crossEdges.length) {
    const aliveNodes = new Set(nodes.value.map(n => n.id))
    const aliveGroups = new Set(groups.value.map(g => g.id))
    for (const e of remapCrossEdges(clip, keyToNewId, aliveNodes, aliveGroups)) {
      if (isGroupEndpoint(e.source) || isGroupEndpoint(e.target)) groupEdges.value.push(e)
      else edges.value.push(e)
    }
  }
  scheduleStoreReconcile()
  emit('structure-changed')
  clip.pasteCount++
}

/** 暴露给自定义边 DeletableEdge 的删除回调（统一走 removeEdges → emit structure-changed 落库）。 */
provide('canvasRemoveEdge', (id: string) => {
  removeEdges([id])
  if (selectedEdgeId.value === id) selectedEdgeId.value = ''
})

/**
 * 修复III C1 复验补缺（2x-1）：节点子组件 CanvasNodeBase 拖角柄 resize-end 只写
 * data.width/height，此前无保存触发链——拉完尺寸不做别的操作直接关页即丢（快照真源
 * 已改但 PUT 没跑）。resize 由 node-resizer d3-drag 驱动，不触发 vue-flow node-drag-stop，
 * 故经 provide/inject 显式通知（同 canvasRemoveEdge 范式）→ structure-changed 落库。
 */
provide('canvasNodeResized', () => {
  emit('structure-changed')
})

/**
 * 修复IV B1（C-1 两段式）：媒体节点子组件在「已选中」后二段点击时回调此处 → 弹 Lightbox。
 * provide/inject 同 canvasNodeResized 范式；payload 口径与原 onNodeClick 直弹分支一致
 * （修复III C6 的 kind/src/poster 不变，只换触发时机）。
 */
provide('canvasMediaPreview', (nodeId: string) => {
  const hit = nodes.value.find(n => n.id === nodeId)
  if (hit && (hit.type === 'image' || hit.type === 'video') && hit.data.previewUrl) {
    emit('preview-media', {
      kind: hit.type,
      src: String(hit.data.previewUrl),
      poster: hit.type === 'video' && hit.data.coverPreviewUrl ? String(hit.data.coverPreviewUrl) : undefined
    })
  }
})

/** 从节点调色板拖入：dataTransfer 带 {label}，落点转画布坐标。 */
function onDragOver(event: DragEvent) {
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
}

/**
 * 修复VI（2x 未解决①）：粘贴落点=最近鼠标的画布坐标（boardRoot 上 mousemove 记录；
 * 无记录回落视口中心——如刚进页面未动鼠标就 Ctrl+V）。
 */
let lastClient = { x: 0, y: 0 }
let hasPointer = false
function onRootMouseMove(event: MouseEvent) {
  lastClient = { x: event.clientX, y: event.clientY }
  hasPointer = true
}

/**
 * 修复VI（2x 未解决①）：画布空白 Ctrl+V 剪贴板图片 → emit 父上传建节点。
 * 焦点在 input/textarea/contentEditable 时直接 return（事件会冒泡到 board 根，
 * 不拦=正常粘文本；守卫函数 isEditableTarget 单测覆盖）。
 */
function onPaste(event: ClipboardEvent) {
  if (isEditableTarget(event.target as HTMLElement | null)) return
  const files = Array.from(event.clipboardData?.items ?? [])
    .filter(it => it.kind === 'file' && it.type.startsWith('image/'))
    .map(it => it.getAsFile())
    .filter((f): f is File => !!f)
  if (!files.length) return
  const vf = vueFlowRef.value as HTMLElement | null
  if (!vf) return
  event.preventDefault()
  const { left, top, width, height } = vf.getBoundingClientRect()
  const cx = hasPointer ? lastClient.x : left + width / 2
  const cy = hasPointer ? lastClient.y : top + height / 2
  emit('pane-paste-files', { files, position: project({ x: cx - left, y: cy - top }) })
}

function onDrop(event: DragEvent) {
  const data = event.dataTransfer?.getData('application/vueflow')
  if (!data) {
    // 修复VI（2x 未解决②）：无内部拖拽 MIME → OS 本地文件拖入，原样抛父分流建节点
    const files = Array.from(event.dataTransfer?.files ?? [])
    if (files.length) {
      event.preventDefault()
      const vf = vueFlowRef.value as HTMLElement | null
      if (!vf) return
      const { left, top } = vf.getBoundingClientRect()
      emit('pane-drop-files', { files, position: project({ x: event.clientX - left, y: event.clientY - top }) })
    }
    return
  }
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
 * 2x 四轮 S2：由 data.width/height 推导 wrapper style（vue-flow 应用到 .vue-flow__node）。
 * 默认宽 200（老节点/新节点统一口径）；高仅在用户拉过（data.height 存在）才定死，否则随内容。
 * style 是会话态——保存时剥离（getSnapshot），真源只有 data。
 */
function nodeSizeStyle(data: Record<string, unknown> | undefined): Record<string, string> {
  const width = typeof data?.width === 'number' ? data.width : 200
  const style: Record<string, string> = { width: `${width}px` }
  if (typeof data?.height === 'number') style.height = `${data.height}px`
  return style
}

/**
 * C6：双击画布空白处 → emit 坐标给父开「快速加节点」搜索框（ComfyUI 式）。
 * 仅空白处触发：点节点(.vue-flow__node)/连线(.vue-flow__edge)/句柄(.vue-flow__handle)不弹，避免误加。
 * 坐标复用 onDrop 的 project 范式（clientXY − vueFlow 容器偏移 → 画布坐标系，兼容缩放/平移）。
 * 导演台 Step 7：双击 director 节点 = 打开导演台（wrapper data-id 反查节点类型）。
 */
function onDblClick(event: MouseEvent) {
  const tgt = event.target as HTMLElement | null
  const nodeEl = tgt?.closest('.vue-flow__node') as HTMLElement | null
  if (nodeEl) {
    const nodeId = nodeEl.dataset.id
    const hit = nodeId ? nodes.value.find(n => n.id === nodeId) : null
    if (hit?.type === 'director' && nodeId) emit('open-director', nodeId)
    else if (nodeId) focusNodeById(nodeId) // 修复III C6（2x-6）：双击普通节点 = fitView 聚焦（与单击预览不冲突）
    return
  }
  if (
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
  pushHistory('add') // 2x 五轮撤回（批量建节点同 tag 800ms 合并=一步）
  const baseLabel = String(partial.data?.label ?? '新节点')
  const existing = nodes.value.map((n) => String(n.data.label ?? ''))
  const type = partial.type ?? 'text'
  // label 放 spread 之后，确保去重值覆盖 partial.data 自带 label（L9 三入口）
  const data: Record<string, unknown> = { ...(partial.data ?? {}), label: uniqueLabel(baseLabel, existing) }
  // 修复IV C2（C-7）：媒体节点新建即定型 320×320——生成完成瞬间不再跳变尺寸。
  // 携带宽高的入口（粘贴/创建副本）不覆盖；存量无值节点仍由 updateNodeData
  // 完成分支兜底（勿删：老画布节点加载无 data.width/height）。
  if ((type === 'image' || type === 'video')
      && typeof data.width !== 'number' && typeof data.height !== 'number') {
    data.width = 320
    data.height = 320
  }
  // id 加 seqCounter 后缀防批量撞：脚本拆分镜同毫秒内连调 N 次 addNode，
  // Date.now() 相同会撞 id → vue-flow 重复告警 + 渲染错乱。
  const node: CanvasNode = {
    id: `node-${Date.now()}-${seqCounter++}`,
    type,
    position: partial.position ?? { x: Math.random() * 200 + 80, y: Math.random() * 120 + 80 },
    data: data as CanvasNodeData,
    // 2x 四轮 S2：默认/携带的宽高落 wrapper style（含粘贴携带 width/height 的场景）
    style: nodeSizeStyle(data)
  }
  nodes.value.push(node)
  scheduleStoreReconcile()
  // 修复IV C1a（C-4 缺口1）：三路新增（调色板/拖入/快速加）统一进自动保存——此前仅
  // 连线路径落库（onConnect / CanvasView 有连线分支 scheduleSave），裸新增关页即丢。
  emit('structure-changed')
  return node.id
}

/**
 * 建边统一链（修复VIII 收口，VIII-1 ④ / VIII-2）：自环与同向重复校验（普通边/组边
 * 各查各池并查，VIII-2 顺带收口——现状拖拽可建自环边）→ pushHistory('edge') → 入对应
 * 集合（组边绝不进 v-model，坑 1）→ scheduleStoreReconcile + structure-changed 落库。
 * 校验未过返回 false（不建不弹，静默口径同程序化 addEdge）。
 */
function addEdgeInternal(
  source: string,
  target: string,
  handles?: { sourceHandle?: string | null; targetHandle?: string | null }
): boolean {
  if (!source || !target) return false
  if (source === target) return false // 自环：节点连自身 / 组连自己（同伪 id）
  const isGroup = isGroupEndpoint(source) || isGroupEndpoint(target)
  const pool = isGroup ? groupEdges.value : edges.value
  if (pool.some(e => e.source === source && e.target === target)) return false // 同向去重
  pushHistory('edge') // 校验通过才入栈（失败入栈=无变化垃圾撤回步）
  const edge: CanvasEdge = {
    id: `edge-${source}-${target}-${Date.now()}`,
    source,
    target,
    sourceHandle: handles?.sourceHandle || undefined,
    targetHandle: handles?.targetHandle || undefined,
    type: 'deletable', // 贝塞尔 + 中点删除按钮（同 defaultEdgeOptions）
    style: { stroke: 'var(--color-primary)', strokeWidth: 1.5 }
  }
  pool.push(edge)
  if (!isGroup) scheduleStoreReconcile() // 组边不进 v-model，无需 store 对账
  emit('structure-changed')
  return true
}

function onConnect(connection: Connection) {
  if (!connection.source || !connection.target) return
  // 修复VIII（VIII-2 顺带收口）：拖拽建边补自环/同向重复校验（对齐程序化 addEdge 口径）
  addEdgeInternal(connection.source, connection.target, {
    sourceHandle: connection.sourceHandle,
    targetHandle: connection.targetHandle
  })
  // 坑 2 双发防护：无论是否建成，本手势都算「库已处理」——connectEnd 不再走直连/quick-add
  justConnected = true
}

/**
 * 2x-6：拉线建节点支持。connect-start 记下起点（仅 source 句柄），
 * connect-end 时若本次拖拽没有成功连上（onConnect 未触发）且落点在空白处，
 * 就地弹「快速加节点」并携带起点 id——父组件建完节点自动连线。
 *
 * connectingEdge（2x 五轮）：连线拖拽进行中——关联高亮淡化的节点被指针（=连线末端）
 * 悬停时恢复不透明（CSS :hover），解决「拖线看不清目标节点」。
 */
const connectStartParams = ref<OnConnectStartParams | null>(null)
const connectingEdge = ref(false)
let justConnected = false

function onConnectStart(params: OnConnectStartParams) {
  connectStartParams.value = params
  justConnected = false
  connectingEdge.value = true
}

/** 落点判定专用坐标：不吸附换算（project() 带 snap-to-grid 16px 量化，组边缘 12px pad 内会误判；仅判定用，建点仍走 project 拿吸附坐标）。 */
function clientToFlowUnsnapped(clientX: number, clientY: number): { x: number; y: number } | null {
  const vf = vueFlowRef.value as HTMLElement | null
  if (!vf) return null
  const { left, top } = vf.getBoundingClientRect()
  const vp = getViewport()
  return { x: (clientX - left - vp.x) / vp.zoom, y: (clientY - top - vp.y) / vp.zoom }
}

function onConnectEnd(event: MouseEvent | TouchEvent | undefined) {
  const start = connectStartParams.value
  connectStartParams.value = null
  connectingEdge.value = false
  const connected = justConnected
  justConnected = false
  if (!start || connected) return // 坑 2：库已发 connect（handle 命中）→ 防双建
  if (!start.nodeId) return
  const clientX = event instanceof MouseEvent ? event.clientX
    : event && 'changedTouches' in event && event.changedTouches.length ? event.changedTouches[0].clientX : null
  const clientY = event instanceof MouseEvent ? event.clientY
    : event && 'changedTouches' in event && event.changedTouches.length ? event.changedTouches[0].clientY : null
  if (clientX == null || clientY == null) return
  const vf = vueFlowRef.value as HTMLElement | null
  if (!vf) return
  const flowPos = clientToFlowUnsnapped(clientX, clientY)
  if (!flowPos) return
  // 修复VIII（VIII-2 + VIII-1 ③）：落点分派树——handle→库原路径；节点本体→两方向直连；
  // 组包围盒（坐标∩判定，坑 3：组框体穿透 target 是 pane）→ 外部→组边；空白→quick-add 不变
  const drop = decideDropTarget({
    target: event ? (event.target as HTMLElement | null) : null,
    flowPos,
    groupRects: groupFlowRects()
  })
  if (drop.kind === 'handle') return // handle 落点：库 onConnect 已处理（或未吸附＝放弃，原语义）
  if (drop.kind === 'node') {
    // VIII-2 本体松手直连：落自身本体静默；方向=起拖 handle 类型决定
    if (drop.nodeId === start.nodeId) return
    const source = start.handleType === 'source' ? start.nodeId : drop.nodeId
    const target = start.handleType === 'source' ? drop.nodeId : start.nodeId
    addEdgeInternal(source, target)
    return
  }
  if (drop.kind === 'group') {
    // VIII-1 ③ 外部→组：仅 source 句柄起拖（target 句柄落组不建反向边，口径同「向前拉」）
    if (start.handleType !== 'source') return
    addEdgeInternal(start.nodeId, groupEndpointOf(drop.groupId))
    return
  }
  // 落空白：维持现状 quick-add（仅输出句柄向前拉建点，2x-6 原语义）
  if (start.handleType !== 'source') return
  emit('quick-add', flowPos, start.nodeId)
}

/**
 * A3 组端口松手分派（VIII-1 ③ 组→外部）：落节点（本体/handle 均认，口径同 VIII-2）
 * → 组边；落另一组包围盒 → 组→组边；落自身组 → 静默；落空白 → quick-add 复用现有链
 * （addEdge 已支持伪 id source=组→新节点；target 端口落空白无「新节点在前」语义 → 静默）。
 * 落点元素经 elementFromPoint 取——pointer capture 会把事件 target 重定向到端口自身（坑 4）。
 */
function dispatchGroupPortDrop(st: GroupDragState, event: PointerEvent) {
  // review 补：拖线中途组被异步解散（成员批量替换触发自解散等）→ 松手分派前验组存活，防引用死组的组边永久落库
  if (!groups.value.some(g => g.id === st.groupId)) return
  const under = document.elementFromPoint(event.clientX, event.clientY)
  const flowPos = clientToFlowUnsnapped(event.clientX, event.clientY)
  if (!flowPos) return
  const drop = decideDropTarget({
    target: under instanceof HTMLElement ? under : null,
    flowPos,
    groupRects: groupFlowRects()
  })
  const self = groupEndpointOf(st.groupId)
  if (drop.kind === 'node' || drop.kind === 'handle') {
    // 落节点（本体或 handle 均认）：side 决定组端方向
    const source = st.side === 'source' ? self : drop.nodeId
    const target = st.side === 'source' ? drop.nodeId : self
    addEdgeInternal(source, target)
    return
  }
  if (drop.kind === 'group') {
    if (drop.groupId === st.groupId) return // 落自身组：静默（防组自环）
    const other = groupEndpointOf(drop.groupId)
    const source = st.side === 'source' ? self : other
    const target = st.side === 'source' ? other : self
    addEdgeInternal(source, target) // 组→组（广播+聚合自然组合，VIII-1 ②）
    return
  }
  if (st.side === 'source') {
    // 落空白：quick-add 新节点并连 组→新节点（复用现有链，addEdge 传伪 id）
    emit('quick-add', flowPos, self)
  }
}

function onNodeClick({ node }: NodeMouseEvent) {
  groupSelectedId.value = null
  selectedNodeId.value = node.id
  selectedEdgeId.value = ''
  clearMultiSelection()
  boardRoot.value?.focus()
  // emit 数组中的真实 CanvasNode 引用，供属性面板直编 data（reactive 即时反映到画布）
  emit('node-selected', nodes.value.find(n => n.id === node.id) ?? null)
  // 修复IV B1（C-1 两段式，决策 6）：单击不再直接弹 Lightbox——未选中第一击只选中（本函数），
  // 已选中后再点媒体本体（Image/Video 节点 inject canvasMediaPreview）才弹。原先「点节点即弹」
  // 打断拖动/看属性（修复III C6 复验反馈，2x-1）。
}

/**
 * 3x-C1：Shift 框选结束（vue-flow 原生 UserSelection 拖框）。nextTick 等库把 selected
 * 写回节点后读 getSelectedNodes。1 个 → 归一回单选面板；≥2 → 多选集上报（父显批量工具条）。
 * 库在普通单击/点空白时会自行重置内部选中，这里只同步应用层状态。
 */
function onSelectionEnd() {
  nextTick(() => {
    groupSelectedId.value = null // 修复XI D1：框选起手=离开组选态（互斥）
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
 * 2x 四轮 S5 修复：Vue Flow 内部选中变化即同步应用层（单选真相源收敛）。
 * 背景：此前 selectedNodeId 只靠 @node-click 维护——点击被拖拽打断（微小位移即判拖拽）
 * 或被卡片内元素吞掉时，库内部已选中新节点（wrapper selected 类切换）而应用层仍持旧值，
 * 关联高亮闭包用旧种子算 → 「选中 A 无关节点淡化、但 B 的无关节点没淡化」错乱。
 * 挂点说明：@vue-flow/core 无 selectionChange 事件（实测不触发），选中变化统一走
 * nodes-change 的 {type:'select'} 变更——onNodesChange 捕获后调本函数，nextTick 等库
 * 写回 node.selected 再读 getSelectedNodes（onSelectionEnd 同范式）。
 * 以库选中为准兜底（幂等，与 node-click/selection-end 重复触发无副作用）；
 * 0 选中不处理（pane/edge 点击由各自 handler 负责清）。
 */
function syncSelectionFromLibrary() {
  nextTick(() => {
    const ids = getSelectedNodes.value.map((n: { id: string }) => n.id)
    if (ids.length >= 2) {
      const next = ids.join(' ')
      if (next !== multiSelectedIds.value.join(' ')) {
        selectedNodeId.value = ''
        selectedEdgeId.value = ''
        multiSelectedIds.value = ids
        emit('node-selected', null)
        emit('nodes-selected', ids)
      }
    } else if (ids.length === 1 && selectedNodeId.value !== ids[0]) {
      clearMultiSelection()
      selectedNodeId.value = ids[0]
      selectedEdgeId.value = ''
      emit('node-selected', nodes.value.find(n => n.id === ids[0]) ?? null)
    } else if (ids.length === 0) {
      // 修复VII P4 实测缺口：vue-flow 也会自发清空选中（如 Esc），不经过 pane/edge 点击
      // handler——原「0 选中不处理」让 selectedNodeId 残留（视觉已无选中，属性面板仍挂旧节点、
      // ✨ 一键整理被隐形单选拉进子图模式）。0 选中同样同步清应用层（幂等，有选中才 emit）。
      if (selectedNodeId.value || selectedEdgeId.value || multiSelectedIds.value.length) {
        selectedNodeId.value = ''
        selectedEdgeId.value = ''
        multiSelectedIds.value = []
        emit('node-selected', null)
        emit('nodes-selected', [])
      }
    }
  })
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
  groupSelectedId.value = null
  selectedNodeId.value = node.id
  selectedEdgeId.value = ''
  clearMultiSelection()
  const real = nodes.value.find(n => n.id === node.id)
  if (real) emit('node-context-menu', real)
}

function onEdgeClick({ edge }: EdgeMouseEvent) {
  groupSelectedId.value = null
  selectedNodeId.value = ''
  selectedEdgeId.value = edge.id
  clearMultiSelection()
  boardRoot.value?.focus()
  emit('node-selected', null)
}

function onPaneClick() {
  // 修复XI（D1）：组框空白点击转正组选——pointerdown 捕获段已判定落点在某组包围盒空白
  // （框体穿透设计不变），此处不进清选中链；拖框（selection）则不会走到 pane-click。
  if (groupClickCandidate) {
    selectGroup(groupClickCandidate)
    groupClickCandidate = null
    boardRoot.value?.focus()
    return
  }
  groupSelectedId.value = null
  selectedNodeId.value = ''
  selectedEdgeId.value = ''
  clearMultiSelection()
  boardRoot.value?.focus()
  emit('node-selected', null)
}

// ---- 修复XI（XI-4 D1）：组选态与「点组框空白=选整组」 ----

/** 选中整组：清节点单选/多选（互斥），高亮 groupbox--selected；Delete 链对组选态无动作（Q5）。 */
function selectGroup(id: string) {
  groupSelectedId.value = id
  selectedNodeId.value = ''
  selectedEdgeId.value = ''
  clearMultiSelection()
  emit('node-selected', null)
}

/**
 * 组框空白点击候选（pointerdown 捕获段预判 → pane-click 转正）：
 * - 组框体 pointer-events:none 穿透（修复VIII 设计），空白点击的 DOM 落点是 pane——
 *   在 boardRoot 捕获段（先于 vue-flow pane 处理）按坐标命中 groupBoxes 判定。
 * - 排除：组头/组端口/组边（自有交互）、节点/普通边（成员点击=现状单选链，反清组选）、
 *   Shift 起手（框选）、非左键。
 * - 只记候选不直接选：若随后是拖框/平移，pane-click 不来，候选自然作废（onSelectionEnd 亦清）。
 */
let groupClickCandidate: string | null = null
function onBoardPointerDownCapture(e: PointerEvent) {
  groupClickCandidate = null
  if (e.button !== 0 || e.shiftKey) return
  const t = e.target as HTMLElement | null
  if (!(t instanceof HTMLElement)) return
  if (t.closest('.canvas-board__groupbox-head, .canvas-board__groupbox-port, '
    + '.canvas-board__groupedge, .vue-flow__node, .vue-flow__edge')) return
  if (!t.closest('.vue-flow__pane')) return
  const rect = boardRoot.value?.getBoundingClientRect()
  if (!rect) return
  const x = e.clientX - rect.left
  const y = e.clientY - rect.top
  const hit = groupBoxes.value.find(
    b => x >= b.left && x <= b.left + b.width && y >= b.top && y <= b.top + b.height
  )
  groupClickCandidate = hit?.id ?? null
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
  pushHistory('remove')
  const removeSet = new Set(nodeIds)
  nodes.value = nodes.value.filter(n => !removeSet.has(n.id))
  edges.value = edges.value.filter(e => !removeSet.has(e.source) && !removeSet.has(e.target))
  // 修复VIII（VIII-1 ⑦）：对端节点名下组边级联删；成员修剪/自解散由 nodes watch 接力
  dropGroupEdgesOfNodes(nodeIds)
  scheduleStoreReconcile()
  emit('structure-changed')
}

function removeEdges(edgeIds: string[]) {
  pushHistory('remove')
  const removeSet = new Set(edgeIds)
  edges.value = edges.value.filter(e => !removeSet.has(e.id))
  // 修复VIII（VIII-1 ⑤）：组边 ×/Delete 走同款删除链（canvasRemoveEdge 语义扩展到双集）
  groupEdges.value = groupEdges.value.filter(e => !removeSet.has(e.id))
  scheduleStoreReconcile()
  emit('structure-changed')
}

/** 节点拖动开始 → 变更前入撤回栈（位置快照）。 */
function onNodeDragStart() {
  pushHistory('move')
}

/** 节点拖动结束 → 落库新位置（vue-flow @node-drag-stop）。 */
function onNodeDragStop() {
  emit('structure-changed')
}

/**
 * 2x 四轮 S2：尺寸变更通知（node-resizer 拖动走 nodesChange dimensions）。
 * 只认拖动结束标记（resizing:false）——拖动中每帧都是 updateStyle:true+resizing:true，
 * 逐帧 emit 会把 800ms 防抖落库搅成保存风暴。结束时 CanvasNodeBase 已把宽高写进
 * node.data（resizeEnd 事件先于该通知），此处只补「结构变更」让父组件防抖落库。
 */
function onNodesChange(changes: NodeChange[]) {
  const sizeSettled = changes.some(c => c.type === 'dimensions' && c.resizing === false)
  if (sizeSettled) {
    emit('structure-changed')
    resizeGestureOpen = false // 手势结束，下次 resize 重开门
  }
  // 2x 五轮：resize 手势首帧（resizing:true）入栈一次——拖动中每帧 dimensions 变更不重复推
  if (!resizeGestureOpen && changes.some(c => c.type === 'dimensions' && c.resizing === true)) {
    resizeGestureOpen = true
    pushHistory('resize')
  }
  // 2x 四轮 S5：库内选中变化（含拖拽吞 click / 卡片内元素吞 click 的场景）→ 同步应用层单选真相
  if (changes.some(c => c.type === 'select')) syncSelectionFromLibrary()
}

/** resize 手势开合标记（onNodesChange 内去重入栈用）。 */
let resizeGestureOpen = false

/** 集合重建（载入/撤回复用）：nodes 重挂尺寸 style、edges 归一 deletable、组深拷贝。 */
function applySnapshot(snap: CanvasSnapshot) {
  // 2x 四轮 S2：data.width/height → wrapper style（含默认 200 兜底），老快照无字段即默认宽
  nodes.value = (snap.nodes ?? []).map(n => ({ ...n, style: nodeSizeStyle(n.data) }))
  // 修复VIII（VIII-1 ②）：快照边按端点拆分——普通边进 v-model（归一 deletable），
  // 组边进独立集合（伪 id 绝不进 v-model，坑 1）；class 会话态一并剥（防旧选中态烤入）
  const { flowEdges, groupEdges: gEdges } = splitSnapshotEdges(snap.edges ?? [])
  edges.value = flowEdges.map(e => ({ ...e, type: 'deletable' }))
  groupEdges.value = gEdges.map(({ class: _class, ...rest }) => rest as CanvasEdge)
  // 2x 四轮 S9：组（老快照无 groups 字段 = 空数组语义，零报错）
  groups.value = (snap.groups ?? []).map(g => ({ ...g, memberIds: [...(g.memberIds ?? [])] }))
  scheduleStoreReconcile()
}

/** 载入快照（从后端加载画布时调）。换画布/恢复版本=新时间线起点，清撤回栈。 */
function loadSnapshot(snap: CanvasSnapshot) {
  applySnapshot(snap)
  undoStack.length = 0
  redoStack.length = 0
}

/** 序列化快照（保存时调）。getViewport 是函数非 ref（@vue-flow/core 1.41）。 */
function getSnapshot(): CanvasSnapshot {
  const vp = getViewport()
  return {
    // 2x 四轮 S2/S5：剥 wrapper style 与视觉态 class（均会话态）——持久化真源只有 data
    nodes: nodes.value.map(({ style: _style, class: _class, ...rest }) => rest),
    // 剥离选中态 class（纯前端视觉，不入库；重载后由 watch 按 selectedEdgeId='' 重置）。
    // 修复VIII（VIII-1 ②）：组边合并落库（class 同剥；快照 JSON 结构不变、老快照零迁移）
    edges: mergeSnapshotEdges(
      edges.value.map(({ class: _class, ...rest }) => rest),
      groupEdges.value.map(({ class: _class, ...rest }) => rest)
    ),
    groups: groups.value.map(g => ({ ...g, memberIds: [...g.memberIds] })),
    viewport: { x: vp.x, y: vp.y, zoom: vp.zoom }
  }
}

// ---- 2x 五轮：撤回/重做（结构操作历史栈，同导演台 sceneModel 50 深口径） ----

/**
 * 历史快照 = 结构快照（复用 getSnapshot 序列化规则：剥 style/class 会话态，不带 viewport
 * ——撤回不应劫持视口）。JSON 深拷贝与画布响应式对象彻底断链。
 * 覆盖面：增删节点/连线、拖动、改尺寸、组 CRUD。文本内容编辑不单独入栈（编辑器内
 * 有原生 undo），但会随下一次结构操作的快照一并定格。
 */
interface HistoryEntry { snap: CanvasSnapshot; tag: string; ts: number }
// reactive：canUndo/canRedo 依赖 length，普通数组变更不会触发 computed/按钮重渲染
const undoStack = reactive<HistoryEntry[]>([])
const redoStack = reactive<HistoryEntry[]>([])
const HISTORY_MAX = 50
const canUndo = computed(() => undoStack.length > 0)
const canRedo = computed(() => redoStack.length > 0)

/** 变更前入栈。tag 合并规则：同 tag 且 800ms 内的连续变更只留一步（脚本拆分镜批量建 N 节点=一次撤回）。 */
function pushHistory(tag: string) {
  const now = Date.now()
  const top = undoStack[undoStack.length - 1]
  if (top && top.tag === tag && now - top.ts < 800) {
    top.ts = now
    return
  }
  undoStack.push({ snap: cloneHistoryState(), tag, ts: now })
  if (undoStack.length > HISTORY_MAX) undoStack.shift()
  redoStack.length = 0
}

/** 当前结构深拷贝（不入栈，undo/redo 前互备现态用）。 */
function cloneHistoryState(): CanvasSnapshot {
  const { viewport: _vp, ...rest } = getSnapshot()
  // 节点/边来自 reactive ref（Vue Proxy），structuredClone 会抛 DataCloneError；
  // 快照本就走 JSON 持久化，JSON 深拷贝即正确形状。
  return JSON.parse(JSON.stringify(rest)) as CanvasSnapshot
}

/** 应用历史快照：重建三类集合 + 清选中 + 通知父组件落库（不动撤回栈）。 */
function applyHistoryState(snap: CanvasSnapshot) {
  applySnapshot(snap)
  selectedNodeId.value = ''
  selectedEdgeId.value = ''
  clearMultiSelection()
  applyVisualClasses()
  emit('structure-changed')
}

function undo() {
  const entry = undoStack.pop()
  if (!entry) return
  redoStack.push({ snap: cloneHistoryState(), tag: entry.tag, ts: Date.now() })
  applyHistoryState(entry.snap)
}

function redo() {
  const entry = redoStack.pop()
  if (!entry) return
  undoStack.push({ snap: cloneHistoryState(), tag: entry.tag, ts: Date.now() })
  applyHistoryState(entry.snap)
}

/** 键盘撤回：Ctrl/Cmd+Z 撤回、+Shift 重做。输入框内放行浏览器原生文本 undo。 */
function onKeydownUndo(e: KeyboardEvent) {
  const tgt = e.target as HTMLElement | null
  if (tgt?.closest('input, textarea, [contenteditable="true"')) return
  if (!(e.ctrlKey || e.metaKey) || e.key.toLowerCase() !== 'z') return
  e.preventDefault()
  if (e.shiftKey) redo()
  else undo()
}

/** 取节点真实引用（数组中的对象，reactive 即时反映画布）。 */
function getNode(nodeId: string): CanvasNode | null {
  return nodes.value.find(n => n.id === nodeId) ?? null
}

/** 取全部连线（C8 数据流解析 + C9 拓扑重跑用）。仅普通边（v-model 集，无伪 id）。 */
function getEdges(): CanvasEdge[] {
  return edges.value
}

/** 修复VIII：取组边集合（CanvasView 数据流解析与 getEdges 合并展开用，VIII-1 ⑥）。 */
function getGroupEdges(): CanvasEdge[] {
  return groupEdges.value
}

/** 取全部节点（C9 拓扑重跑用）。 */
function getNodes(): CanvasNode[] {
  return nodes.value
}

/**
 * 合并补丁进 node.data（C4+ 节点运行结果写回用）。
 * 直编数组中真实引用的 data，reactive 即时反映到画布渲染（同 PropertyPanel 范式）。
 * 修复III C5（2x-5）：媒体节点（image/video）完成定型统一 320×320 盒——仅 data.height
 * 缺失（用户从未手拉）时写入；手拉过的节点尊重用户尺寸不覆盖。宽度一并带出（旧节点
 * 可能只写了 height）。收口在此一处 = 覆盖所有完成路径（文本/图片/视频/媒体任务回调）。
 */
function updateNodeData(nodeId: string, patch: Record<string, unknown>) {
  const n = nodes.value.find(x => x.id === nodeId)
  if (!n) return
  Object.assign(n.data, patch)
  if (patch.status === 'success' && (n.type === 'image' || n.type === 'video')
      && typeof n.data.height !== 'number') {
    n.data.width = 320
    n.data.height = 320
    n.style = { ...nodeSizeStyle(n.data) }
  }
}

/**
 * 程序化加边（焦点编辑/抽帧产新节点自动连源用）。
 * 修复VIII：端点含组伪 id → 入组边集合（quick-add 组→新节点链传伪 id source，VIII-1 ③）；
 * 与 addEdgeInternal 的差异：不 emit structure-changed（调用方自带 scheduleSave，维持原契约）。
 */
function addEdge(source: string, target: string) {
  if (source === target) return
  const isGroup = isGroupEndpoint(source) || isGroupEndpoint(target)
  const pool = isGroup ? groupEdges.value : edges.value
  if (pool.some(e => e.source === source && e.target === target)) return
  pushHistory('edge')
  pool.push({
    id: `edge-${source}-${target}-${Date.now()}`,
    source,
    target,
    type: 'deletable', // 贝塞尔 + 中点删除按钮（同 defaultEdgeOptions）
    style: { stroke: 'var(--color-primary)', strokeWidth: 1.5 }
  })
  if (!isGroup) scheduleStoreReconcile() // 组边不进 v-model，无需 store 对账
}

/**
 * 修复VI（2x 未解决③）：程序化批量追加边（创建副本连线克隆用）。
 * 与 addEdge 的差异：caller 已备好完整 CanvasEdge（含 handles/样式），直接入集；
 * 整批一条历史步（副本操作=用户心智一步）；结构变更上抛父落库。
 */
function appendEdges(list: CanvasEdge[]) {
  // 修复X（X-3）：批次分流——组端点边（副本连原组）进组池（浅拷贝剥会话 class，
  // loadSnapshot :1537 同口径，防选中态烤进新组边）；普通边照旧进 v-model。
  // store 对账只跑普通边（组边不进 v-model）；structure-changed 无论哪池都上抛
  // （getSnapshot 合并组边落库）。
  const flowEdges: CanvasEdge[] = []
  const groupBatch: CanvasEdge[] = []
  for (const e of list) {
    if (isGroupEndpoint(e.source) || isGroupEndpoint(e.target)) {
      const { class: _sessionClass, ...rest } = e
      groupBatch.push(rest as CanvasEdge)
    } else {
      flowEdges.push(e)
    }
  }
  if (!flowEdges.length && !groupBatch.length) return
  pushHistory('edge')
  for (const e of flowEdges) edges.value.push({ ...e })
  for (const e of groupBatch) groupEdges.value.push({ ...e })
  if (flowEdges.length) scheduleStoreReconcile()
  emit('structure-changed')
}

/**
 * 修复VII（2x 增补②）：一键整理布局（LibTV 式，dagre LR 分层，VII-2）。
 * 范围裁决（Q4）：有选中（多选优先，退单选）→ 只排选中子图；选中含组成员 → 整组拉入
 * （组员并集，联动点 7）；无选中 → 全图（「只看关联」隐藏节点照排——可见性是会话态，
 * 布局是结构操作）。一次 pushHistory('layout') + 一次 structure-changed（整理=一步撤回）；
 * 组 memberIds 零改动（包围盒 rAF 派生自动跟随）；位置直编数组引用（updateNodeData 同款
 * 响应式范式）；fitView 收尾在 nextTick 后（位置先进渲染周期，防读旧布局，坑 9）。
 */
function onAutoLayout() {
  if (!nodes.value.length) return
  const selected = multiSelectedIds.value.length
    ? [...multiSelectedIds.value]
    : (selectedNodeId.value ? [selectedNodeId.value] : [])
  let includeIds: Set<string> | undefined
  if (selected.length) {
    const ids: Set<string> = new Set(selected) // const 局部：闭包内保住非空收窄（let 会丢）
    includeIds = ids
    for (const g of groups.value) {
      if (g.memberIds.some(id => ids.has(id))) {
        for (const id of g.memberIds) ids.add(id)
      }
    }
  }
  const positions = computeAutoLayout(nodes.value, edges.value, includeIds ? { includeIds } : {})
  if (!positions.size) return
  pushHistory('layout')
  for (const n of nodes.value) {
    const p = positions.get(n.id)
    if (p) n.position = { ...p }
  }
  scheduleStoreReconcile()
  emit('structure-changed')
  nextTick(() => vfFitView({ padding: 0.15, duration: 300 }))
}

/**
 * 修复XI A2（2x 未解决①，spec XI-1）：画布空白右键菜单。
 * 状态：visible/x/y=overlay 定位（client 坐标）；canvasAt=右键点 flow 坐标（添加/粘贴落点）。
 */
const ctxMenu = reactive({ visible: false, x: 0, y: 0, canvasAt: { x: 0, y: 0 } })
/** 菜单占位估值（贴边翻转用；样式 min-width 180 + 两组 11 项上限保守取 200×420）。 */
const CTX_MENU_W = 200
const CTX_MENU_H = 420
const ctxMenuStyle = computed(() => ({
  left: `${Math.min(ctxMenu.x, Math.max(0, window.innerWidth - CTX_MENU_W - 8))}px`,
  top: `${Math.min(ctxMenu.y, Math.max(0, window.innerHeight - CTX_MENU_H - 8))}px`
}))

/**
 * 根元素 contextmenu（原 @contextmenu.prevent 等价升级）：
 * 命中节点/普通边/工具条/组框 → 只拦浏览器默认菜单不开自绘菜单（节点右键=现状「存入
 * 资产库」链由 @node-context-menu 承接，不叠加）；空白 → 记录 client 定位 + project 换算
 * flow 落点后开菜单。菜单开着再右键=overlay 拦默认后冒泡到此处 → 坐标覆写=挪位置（⑦）。
 */
function onRootContextMenu(event: MouseEvent) {
  event.preventDefault()
  const tgt = event.target as HTMLElement | null
  if (
    tgt?.closest('.vue-flow__node') ||
    tgt?.closest('.vue-flow__edge') ||
    tgt?.closest('.canvas-board__toolbar')
  ) {
    closeContextMenu()
    return
  }
  if (tgt?.closest('.canvas-board__groupbox')) {
    // 修复XI 细化1：组框右键分支先立（含组头/组端口）——当前与边同口径不开菜单，
    // D1「组框点选=组大节点」落地后此处接组级操作（选中整组等）。
    closeContextMenu()
    return
  }
  const vf = vueFlowRef.value as HTMLElement | null
  if (!vf) return
  const { left, top } = vf.getBoundingClientRect()
  ctxMenu.x = event.clientX
  ctxMenu.y = event.clientY
  ctxMenu.canvasAt = project({ x: event.clientX - left, y: event.clientY - top })
  ctxMenu.visible = true
  window.addEventListener('keydown', onCtxMenuEsc, { capture: true })
}

/** Esc 关菜单且不外传（⑥逐层退，Lightbox R7 教训：开着 Esc 只关菜单，不再触发画布清多选/n-modal 关闭链）。 */
function onCtxMenuEsc(e: KeyboardEvent) {
  if (e.key !== 'Escape') return
  e.preventDefault()
  e.stopPropagation()
  closeContextMenu()
}

function closeContextMenu() {
  if (!ctxMenu.visible) return
  ctxMenu.visible = false
  window.removeEventListener('keydown', onCtxMenuEsc, { capture: true })
}

/** 菜单项：添加节点——落点=右键点 flow 坐标（④），label 带类型名走 addNode 唯一化去重。 */
function ctxAddNode(type: string, label: string) {
  closeContextMenu()
  addNode({ type, position: { ...ctxMenu.canvasAt }, data: { label } })
}

/** 菜单项：粘贴=Ctrl+V 同链，落点强制=右键点（⑤ 参数化落点）。 */
function ctxPaste() {
  if (!clipboard.value) return
  const at = { x: ctxMenu.x, y: ctxMenu.y }
  closeContextMenu()
  pasteSubgraph(at)
}

/** 菜单项：撤销/重做/一键整理=工具条同 handler 纯入口（⑤零新增逻辑）。 */
function ctxUndo() {
  closeContextMenu()
  undo()
}

function ctxRedo() {
  closeContextMenu()
  redo()
}

function ctxAutoLayout() {
  closeContextMenu()
  onAutoLayout()
}

/** 卸载兜底摘 Esc 监听（修复X P4 教训：路由切走组件随父卸载，window 捕获监听必须显式摘）。 */
onUnmounted(() => {
  window.removeEventListener('keydown', onCtxMenuEsc, { capture: true })
})

/**
 * 修复XI B3（spec XI-2⑤）：视口中心建节点（官方库插入落点）——rect 中心换算 flow 坐标
 * （同 pasteSubgraph 无鼠标回落口径）。返回新节点 id 供调用方接 resolve 写回。
 */
function addNodeAtCenter(partial: { type?: string; data?: Record<string, unknown> }): string {
  const rect = (vueFlowRef.value as HTMLElement | null)?.getBoundingClientRect()
    ?? { left: 0, top: 0, width: 0, height: 0 }
  const target = project({ x: rect.width / 2, y: rect.height / 2 })
  return addNode({ ...partial, position: target })
}

/**
 * 修复XI B3（plan 细化4）：官方库插入失败回滚——静默删节点**不入撤销栈**，并弹出该次
 * add 留下的历史步（add 入栈的是「加节点前」快照，弹掉后撤销链不留「撤了没变化」的空步）。
 * 连带清边/组边（对端组边级联同 removeNodes 口径），structure-changed 照发落库防残留。
 */
function abortNodeAdd(nodeId: string) {
  nodes.value = nodes.value.filter(n => n.id !== nodeId)
  edges.value = edges.value.filter(e => e.source !== nodeId && e.target !== nodeId)
  dropGroupEdgesOfNodes([nodeId])
  const top = undoStack[undoStack.length - 1]
  if (top && top.tag === 'add') undoStack.pop()
  scheduleStoreReconcile()
  emit('structure-changed')
}

defineExpose({
  addNode, addEdge, appendEdges, removeNodes, loadSnapshot, getSnapshot, getNode, getEdges, getNodes,
  // 修复XI B3：官方库插入链（中心建节点 + 失败静默回滚）
  addNodeAtCenter, abortNodeAdd,
  // 修复VIII：组边只读出口（CanvasView resolveEdgesForFlow 合并入口用）
  getGroupEdges,
  updateNodeData, focusNodeById, dragMode, setDragMode,
  // 2x 四轮 S9：组 CRUD（父组件批量工具条「设为组」/改名弹窗回调/@候选并集）
  createGroup, ungroupGroup, renameGroup, getGroups,
  // 2x 五轮：撤回/重做（版本恢复由父组件 loadSnapshot 承接并清栈）
  undo, redo, canUndo, canRedo,
  // 修复VII：子图粘贴（测试/父组件可调）+ 剪贴板态（只读断言用）
  pasteSubgraph, clipboard
})
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
    // 修复IV B3（C-3）：连线命中优先于节点拖边热区（与 CanvasNodeBase 内 line z-index:2 成对）
    z-index: 4;
  }

  :deep(.vue-flow__edge-text) {
    fill: var(--color-text-primary);
    font-size: var(--font-size-xs);
  }

  /* 选中边高亮：红色加粗描边 + 手型，提示点 Delete 可删（class 由 applyVisualClasses 注入） */
  :deep(.canvas-edge--selected .vue-flow__edge-path) {
    stroke: #ef4444;
    stroke-width: 3;
    cursor: pointer;
  }

  :deep(.canvas-edge--selected) {
    cursor: pointer;
  }

  /* 2x 四轮 S5：关联边高亮——加粗 2.5px + 辉光（accent 同主色，靠粗细/辉光区分默认 1.5px 细线） */
  :deep(.canvas-edge--related .vue-flow__edge-path) {
    stroke: var(--color-primary);
    stroke-width: 2.5;
    filter: drop-shadow(0 0 5px rgba(var(--color-primary-rgb), 0.55));
  }

  /* 高亮态下的无关边组（路径+中点×一起降透明度，避免「暗线亮按钮」） */
  :deep(.vue-flow__edge.canvas-edge--dimmed) {
    opacity: 0.25;
  }

  /* 高亮态下的无关节点淡化；「只看关联」再叠 visibility 藏（布局不动、可逆，class 注入 node.class 落 wrapper） */
  :deep(.vue-flow__node.canvas-node--dimmed) {
    opacity: 0.25;
  }

  :deep(.vue-flow__node.canvas-node--hidden) {
    visibility: hidden;
  }

  /* 2x 五轮：连线拖拽进行中，淡化节点被指针（=连线末端）悬停 → 恢复不透明，
     解决「拖线连向淡化节点看不清」。只看关联隐藏的节点不在此恢复（仍不可连）。 */
  &.canvas-board--connecting :deep(.vue-flow__node.canvas-node--dimmed:hover) {
    opacity: 1;
  }

  /* 2x 四轮 S9：组员色 ring（--group-color 由 applyVisualClasses 注入 wrapper style） */
  :deep(.vue-flow__node.canvas-node--grouped) {
    border-radius: var(--radius-base);
    box-shadow: 0 0 0 2px var(--group-color, var(--color-primary));
  }

  /* 修复VIII（VIII-1 ⑨）：选中组边 → 组成员+对端节点 related 辉光 */
  :deep(.vue-flow__node.canvas-node--related) {
    border-radius: var(--radius-base);
    box-shadow: 0 0 0 2px rgba(var(--color-primary-rgb), 0.55);
    opacity: 1;
  }
}

/* 2x 四轮 S9：组包围盒层（框体 pointer-events:none 穿透；头部可交互改名/解组） */
.canvas-board__groups {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 5;
}

.canvas-board__groupbox {
  position: absolute;
  border: 1.5px dashed;
  border-radius: 10px;
  pointer-events: none;
}

/* 修复XI D1：组选高亮（点组框空白/组头空白选中整组）——实线+外辉光，与节点选中态同语言 */
.canvas-board__groupbox--selected {
  border-style: solid;
  border-width: 2px;
  box-shadow: 0 0 0 4px rgba(91, 141, 239, 0.18), 0 0 18px rgba(91, 141, 239, 0.25);
}

.canvas-board__groupbox-head {
  position: absolute;
  left: -1.5px;
  top: -22px;
  display: flex;
  align-items: center;
  gap: 2px;
  max-width: 100%;
  padding: 1px 6px;
  border-radius: 6px 6px 0 0;
  color: #10131a;
  font-size: 11px;
  line-height: 18px;
  pointer-events: auto;
  white-space: nowrap;
}

.canvas-board__groupbox-name {
  font-weight: 600;
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  cursor: pointer;
}

.canvas-board__groupbox-x {
  background: transparent;
  border: 0;
  color: inherit;
  cursor: pointer;
  font-size: 11px;
  line-height: 18px;
  padding: 0 2px;

  &:hover {
    color: #7f1d1d;
  }
}

/* 修复VIII（VIII-1 ①）：组端口——右缘中点=输出（聚合）、左缘中点=输入（广播）。
   pointer-events:auto 同组头；样式对齐 .vue-flow__handle（主色圆点+bg 描边）。 */
.canvas-board__groupbox-port {
  position: absolute;
  top: 50%;
  width: 12px;
  height: 12px;
  margin-top: -6px;
  padding: 0;
  border: 2px solid var(--color-bg);
  border-radius: 50%;
  background: var(--color-primary);
  cursor: crosshair;
  pointer-events: auto;
  transition: transform var(--duration-instant) var(--ease-in-out),
    box-shadow var(--duration-instant) var(--ease-in-out);

  &:hover,
  &--dragging {
    transform: scale(1.4);
    box-shadow: 0 0 6px rgba(var(--color-primary-rgb), 0.6);
  }

  /* 右缘中点（骑在框线上） */
  &--source {
    left: calc(100% - 6px);
  }

  /* 左缘中点（骑在框线上） */
  &--target {
    left: -6px;
  }
}

/* 修复VIII（VIII-1 ⑤）：组边 SVG 覆盖层（组层同栈；层穿透，仅路径与 × 可点） */
.canvas-board__groupedges {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  z-index: 5;
  overflow: visible;
}

.canvas-board__groupedge-path {
  fill: none;
  stroke: var(--color-primary);
  stroke-width: 1.5;
  pointer-events: stroke;
  cursor: pointer;

  &:hover {
    stroke-width: 2.5;
    filter: drop-shadow(0 0 5px rgba(var(--color-primary-rgb), 0.55));
  }
}

/* 选中组边红粗（口径同普通边 canvas-edge--selected，selectedEdgeId 复用） */
.canvas-board__groupedge--selected .canvas-board__groupedge-path {
  stroke: #ef4444;
  stroke-width: 3;
}

.canvas-board__groupedge-del {
  pointer-events: all;
  cursor: pointer;
  opacity: 0.55;
  transition: opacity var(--duration-instant) var(--ease-in-out);

  circle {
    fill: var(--color-surface, #1f2937);
    stroke: var(--color-border, #374151);
    stroke-width: 1;
  }

  text {
    fill: var(--color-text-secondary, #9ca3af);
    font-size: 13px;
    line-height: 1;
    pointer-events: none;
  }

  &:hover {
    opacity: 1;

    circle {
      fill: #ef4444;
      stroke: #ef4444;
    }

    text {
      fill: #fff;
    }
  }
}

/* 组端口拖线临时贝塞尔（虚线区分已建边；穿透不挡落点判定） */
.canvas-board__groupedge-ghost {
  fill: none;
  stroke: var(--color-primary);
  stroke-width: 1.5;
  stroke-dasharray: 6 4;
  pointer-events: none;
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

  /* 只看关联：无节点选中时禁用（无关联集可言） */
  &:disabled {
    opacity: 0.35;
    cursor: not-allowed;
    color: var(--color-text-tertiary);
    border-color: var(--color-border);
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

/* 修复XI A2（2x 未解决①）：画布空白右键菜单——全屏透明 overlay + 光标定位菜单。
   overlay 高于画布各层（工具条 10），低于 Lightbox(3000)/n-modal 弹层，不遮系统层。 */
.canvas-board__ctx-overlay {
  position: fixed;
  inset: 0;
  z-index: 40;
}

.canvas-board__ctx-menu {
  position: absolute;
  min-width: 180px;
  padding: var(--spacing-1);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-base);
  background: var(--color-surface);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.35);
}

.canvas-board__ctx-title {
  padding: 4px 10px;
  font-size: 12px;
  color: var(--color-text-tertiary);
  user-select: none;
}

.canvas-board__ctx-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  padding: 6px 10px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-primary);
  font-size: 13px;
  text-align: left;
  cursor: pointer;

  &:hover:not(:disabled) {
    background: color-mix(in srgb, var(--color-primary) 12%, transparent);
    color: var(--color-primary);
  }

  &:disabled {
    opacity: 0.35;
    cursor: not-allowed;
  }
}

.canvas-board__ctx-glyph {
  width: 14px;
  text-align: center;
  font-size: 14px;
}

/* 画布操作项右侧快捷键提示（不换行、弱化色） */
.canvas-board__ctx-kbd {
  margin-left: auto;
  font-size: 11px;
  color: var(--color-text-tertiary);
  user-select: none;
}

.canvas-board__ctx-sep {
  height: 1px;
  margin: var(--spacing-1) 4px;
  background: var(--color-border);
}
</style>
