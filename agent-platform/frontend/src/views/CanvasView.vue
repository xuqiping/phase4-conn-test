<template>
  <div class="canvas-view">
    <!-- 无权限兜底（直访 /canvas 无 canvas:write） -->
    <n-card v-if="!canEdit" class="canvas-view__forbid" title="无访问权限">
      <p>无限画布需要「canvas:write」权限。请联系管理员授权后重试。</p>
    </n-card>

    <!-- 列表模式：我的画布 -->
    <div v-else-if="!editingId" class="canvas-view__list">
      <div class="canvas-view__header">
        <h2 class="canvas-view__title">我的画布</h2>
        <n-button type="primary" :loading="creating" @click="onCreate">
          <template #icon><n-icon :component="AddOutline" /></template>
          新建画布
        </n-button>
      </div>

      <n-spin :show="loadingList">
        <n-empty v-if="!canvases.length" description="还没有画布，点击「新建画布」开始创作" />
        <div v-else class="canvas-view__grid">
          <div
            v-for="c in canvases"
            :key="c.id"
            class="canvas-card"
            @click="openEditor(c.id)"
          >
            <div class="canvas-card__icon">
              <n-icon size="28" :component="AppsOutline" />
            </div>
            <div class="canvas-card__body">
              <div class="canvas-card__name" :title="c.name">{{ c.name }}</div>
              <div class="canvas-card__meta">
                {{ c.nodeCount ?? 0 }} 节点 · {{ formatTime(c.updatedAt) }}
              </div>
            </div>
            <n-button
              class="canvas-card__del"
              quaternary
              size="tiny"
              @click.stop="onDelete(c)"
            >
              <n-icon :component="TrashOutline" />
            </n-button>
          </div>
        </div>
      </n-spin>
    </div>

    <!-- 编辑模式 -->
    <div v-else class="canvas-view__editor">
      <div class="canvas-view__editor-header">
        <n-button quaternary @click="backToList">
          <n-icon :component="ArrowBackOutline" /> 返回
        </n-button>
        <n-input
          v-model:value="currentName"
          class="canvas-view__name-input"
          placeholder="画布名"
          @blur="onRename"
        />
        <n-button :loading="saving" type="primary" @click="onSave(false)">
          <n-icon :component="SaveOutline" /> 保存
        </n-button>
        <n-button :loading="rerunning" :disabled="batchRunning" quaternary @click="onRerunAll" title="按拓扑序重跑全部可生成节点（环检测）">
          <n-icon :component="RefreshOutline" /> 重跑全链
        </n-button>
        <n-button quaternary @click="showStoryboard = true" title="故事板：视频段时间线排列 + 拼接成片">
          <n-icon :component="FilmOutline" /> 故事板
        </n-button>
      </div>

      <div class="canvas-view__main">
        <!-- 节点调色板（拖到画布即增节点；C3 起扩 5 类节点） -->
        <aside class="canvas-palette">
          <div class="canvas-palette__title">节点</div>
          <div
            v-for="p in palette"
            :key="p.type"
            class="canvas-palette__item"
            draggable="true"
            @dragstart="onPaletteDragStart($event, p)"
            @click="onPaletteClick(p)"
          >
            <n-icon :component="p.icon" />
            <span>{{ p.label }}</span>
          </div>
        </aside>

        <!-- 画布板 -->
        <CanvasBoard
          ref="boardRef"
          @node-selected="onNodeSelect"
          @nodes-selected="onNodesSelected"
          @node-context-menu="onNodeContextMenu"
          @quick-add="onQuickAdd"
          @structure-changed="scheduleSave"
          @group-rename-request="onGroupRenameRequest"
        />

        <!-- 3x-C1 框选批量工具条（≥2 节点选中时浮于画布顶部） -->
        <div
          v-if="multiSelectedIds.length >= 2"
          class="canvas-batchbar"
          role="toolbar"
          aria-label="批量操作工具条"
        >
          <span class="canvas-batchbar__count">已选 {{ multiSelectedIds.length }} 个节点</span>
          <n-button
            size="small"
            tertiary
            type="primary"
            title="把文本中出现的上游节点名自动替换为 @引用（LibTV Link 式）"
            @click="onAssociate"
          >
            一键关联
          </n-button>
          <n-button
            size="small"
            tertiary
            type="primary"
            :loading="batchRunning"
            :disabled="rerunning"
            title="按拓扑序（上游先跑）同时 2 个并发提交选中节点；并发超限自动退避重试"
            @click="onBatchRun"
          >
            批量生成
          </n-button>
          <n-button
            size="small"
            tertiary
            type="primary"
            title="把选中节点设为一组（彩框+组名；下游 @ 命中组内任一祖先即可引用组全员）"
            @click="onCreateGroup"
          >
            设为组
          </n-button>
          <n-button size="small" tertiary type="error" @click="onBatchDelete">
            <template #icon><n-icon :component="TrashOutline" /></template>
            批量删除
          </n-button>
        </div>

        <!-- 3x-C4 批量进度浮条（2x 四轮 S4：收起=停调度释放下游，在途任务不撤仅停释放） -->
        <div
          v-if="batchProgress"
          class="canvas-batchbar canvas-batchbar--progress"
          role="status"
          aria-live="polite"
        >
          <span class="canvas-batchbar__count">
            {{ batchFinished ? '批量生成已完成' : '批量生成中' }}：已提交 {{ batchProgress.submitted }}/{{ batchProgress.total }} · 完成 {{ batchProgress.done }} · 失败 {{ batchProgress.failed }}<template v-if="batchProgress.skipped"> · 跳过 {{ batchProgress.skipped }}</template><template v-if="batchWaiting"> · 等待上游 {{ batchWaiting }}</template>
          </span>
          <n-button size="tiny" quaternary @click="onBatchBarClose">{{ batchFinished ? '收起' : '停止调度' }}</n-button>
        </div>

        <!-- 属性面板（选中节点编辑 + 运行/上传触发） -->
        <PropertyPanel
          :node="selectedNode"
          :running="isNodeRunning(selectedNode?.id)"
          :candidates="mentionCandidates"
          :broken-mentions="brokenMentions"
          :all-labels="otherLabels"
          :image-ancestor-options="imageAncestorOptions"
          :references="referenceList"
          @run="onRunNode"
          @split-storyboard="onSplitStoryboard"
          @upload="onUploadFile"
          @focus-edit="onFocusEdit"
          @transform-image="onTransformImage"
          @annotate="onAnnotate"
          @extract-frame="onExtractFrame"
          @clip-video="onClipVideo"
          @save-to-asset="onSaveToAsset"
          @pick-from-asset="onPickFromAsset"
          @check-update="onCheckUpdate"
          @update-asset="onUpdateAsset"
          @data-changed="scheduleSave"
          @mention-focus="onMentionFocus"
        />
      </div>

      <!-- C10 焦点编辑沉浸 overlay（teleport to body） -->
      <FocusEditOverlay
        v-if="focusNode"
        :preview-url="(focusNode.data.previewUrl as string | undefined)"
        @confirm="onFocusConfirm"
        @cancel="focusNode = null"
      />

      <!-- 2x 四轮 S7：彩色标注沉浸 overlay（生成标注图 / AI 修改双出口） -->
      <AnnotateOverlay
        v-if="annotateNode"
        :preview-url="(annotateNode.data.previewUrl as string | undefined)"
        @confirm-annotate="onAnnotateConfirm"
        @confirm-ai="onAnnotateAi"
        @cancel="annotateNode = null"
      />

      <!-- C13 故事板抽屉（视频段时间线排列 + 顺序预览 + 拼接成片） -->
      <StoryboardPanel
        v-model:show="showStoryboard"
        :segments="storyboardSegments"
        :concating="concating"
        @concat="onStoryboardConcat"
      />

      <!-- S12 存入资产库弹窗（节点入库，L5） -->
      <SaveToAssetDialog
        v-model:show="showSaveAsset"
        :node="contextNode"
        :canvas-id="editingId"
        @imported="onAssetImported"
      />

      <!-- S12 从资产库选择（库→画布引用，L6） -->
      <AssetPicker
        v-model:show="showPicker"
        :node="contextNode"
        :canvas-id="editingId"
        @picked="onAssetPicked"
      />

      <!-- 3x-C2 一键关联预览确认弹窗 -->
      <AutoAssociateDialog
        v-model:show="showAssociate"
        :proposals="associateProposals"
        :skipped="associateSkipped"
        @apply="onAssociateApply"
      />

      <!-- 2x 四轮 S9：建组弹窗（批量工具条「设为组」）/ 组改名弹窗（包围盒头部点组名） -->
      <n-modal v-model:show="showGroupModal" preset="card" title="设为组" style="max-width: 360px">
        <n-input
          v-model:value="groupDraftName"
          placeholder="组名（如：角色设定组）"
          maxlength="30"
          @keydown.enter="confirmCreateGroup"
        />
        <div class="canvas-view__group-hint">选中 {{ multiSelectedIds.length }} 个节点将归入该组；成员节点带组色描边，下游 @ 引用可命中组全员。</div>
        <template #footer>
          <div class="canvas-view__group-footer">
            <n-button size="small" quaternary @click="showGroupModal = false">取消</n-button>
            <n-button size="small" type="primary" @click="confirmCreateGroup">建组</n-button>
          </div>
        </template>
      </n-modal>
      <n-modal
        :show="renameTargetGroup != null"
        preset="card"
        title="组改名"
        style="max-width: 360px"
        @update:show="(v: boolean) => { if (!v) renameTargetGroup = null }"
      >
        <n-input
          v-model:value="groupRenameDraft"
          placeholder="组名"
          maxlength="30"
          @keydown.enter="confirmGroupRename"
        />
        <template #footer>
          <div class="canvas-view__group-footer">
            <n-button size="small" quaternary @click="renameTargetGroup = null">取消</n-button>
            <n-button size="small" type="primary" @click="confirmGroupRename">保存</n-button>
          </div>
        </template>
      </n-modal>

      <!-- C6 双击画布空白处的「快速加节点」搜索框（ComfyUI 式）；2x-6 拉线到空白处也复用此弹窗并自动连线 -->
      <n-modal
        v-model:show="quickAddOpen"
        preset="card"
        :title="quickAddSourceNode ? '添加下一节点（自动连线）' : '快速添加节点'"
        style="max-width: 360px"
        @after-leave="resetQuickAdd"
      >
        <n-input
          v-model:value="quickAddQuery"
          placeholder="搜索节点类型（文本/图片/视频/音频/脚本）"
          @keydown="onQuickAddKey"
        />
        <div class="canvas-quickadd__list">
          <button
            v-for="(p, i) in quickAddFiltered"
            :key="p.type"
            type="button"
            class="canvas-quickadd__item"
            :class="{ 'is-active': i === quickAddIdx }"
            @mouseenter="quickAddIdx = i"
            @click="confirmQuickAdd(p)"
          >
            <n-icon :component="p.icon" />
            <span>{{ p.label }}</span>
          </button>
          <div v-if="!quickAddFiltered.length" class="canvas-quickadd__empty">无匹配节点类型</div>
        </div>
      </n-modal>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import {
  NButton, NCard, NEmpty, NIcon, NInput, NModal, NSpin, useDialog, useMessage
} from 'naive-ui'
import {
  AddOutline, AppsOutline, ArrowBackOutline, SaveOutline, TrashOutline, RefreshOutline,
  DocumentTextOutline, ImageOutline, VideocamOutline, MusicalNotesOutline, CodeSlashOutline,
  FilmOutline
} from '@vicons/ionicons5'
import { useAuthStore } from '@/stores/auth'
import { canvasApi, fetchCanvasPreview, type CanvasNodeDTO, type CanvasVO, type FrameMode, type ImageTransformOp } from '@/api/canvas'
import { mediaApi, fetchVideoBlob, fetchMediaBlob } from '@/api/media'
import type { AttachmentRef, ImageModelVO, ImageSubmitRequest } from '@/api/media'
import { buildCanvasReferenceList, resolveCanvasVideoAttachments, type CanvasReferenceItem } from '@/utils/canvasVideoAttachments'
import { expandGroupCandidates } from '@/utils/groupCandidates'
import { pollMediaTask } from '@/utils/mediaTaskPolling'
import { assetApi, assetBridgeApi } from '@/api/assets'
import type { ResolveVO } from '@/types/asset'
import { MEDIA_TYPE } from '@/types/asset'
import type { CanvasGroup, CanvasNode, CanvasSnapshot, MentionCandidate, StoryboardSegment } from '@/types/canvas'
import CanvasBoard from '@/components/canvas/CanvasBoard.vue'
import PropertyPanel from '@/components/canvas/PropertyPanel.vue'
import FocusEditOverlay from '@/components/canvas/FocusEditOverlay.vue'
import AnnotateOverlay, { type AnnotateConfirmPayload } from '@/components/canvas/AnnotateOverlay.vue'
import { ANNOTATE_COLOR_NAMES } from '@/api/canvas'
import StoryboardPanel from '@/components/canvas/StoryboardPanel.vue'
import SaveToAssetDialog from '@/components/canvas/SaveToAssetDialog.vue'
import AssetPicker from '@/components/canvas/AssetPicker.vue'
import AutoAssociateDialog from '@/components/canvas/AutoAssociateDialog.vue'
import type { CropRect } from '@/types/canvas'
import { ancestors, interpolate, findBrokenMentions, uniqueLabel, type MentionResolver } from '@/utils/interpolate'
import { buildProposals, applyProposals, textLikeFieldOf, type AssociationProposal, type SkippedNode } from '@/utils/autoAssociate'
import { BATCH_WINDOW, batchEligibilityOf, inducedTopoOrder, runDependencyScheduled } from '@/utils/batchRunner'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const message = useMessage()
const dialog = useDialog()

/** 4 层权限显隐兜底：菜单隐藏 + 页内 hasPermission + API 403（plan IC-16）。 */
const canEdit = computed(() => authStore.hasPermission('canvas:write'))

const canvases = ref<CanvasVO[]>([])
const loadingList = ref(false)
const creating = ref(false)
const saving = ref(false)
/** 一键重跑进行中（拓扑序串行跑可生成节点）。 */
const rerunning = ref(false)

/** 当前编辑画布 id（null=列表模式）。 */
const editingId = ref<number | null>(null)
const currentName = ref('')
const boardRef = ref<InstanceType<typeof CanvasBoard> | null>(null)
/** 当前选中节点（属性面板编辑目标；null=未选）。 */
const selectedNode = ref<CanvasNode | null>(null)
/** 正在运行的节点 id 集合（3x-C4 扩多节点：属性面板按钮 loading + 防重入；批量生成与单跑共存）。 */
const runningNodeIds = ref(new Set<string>())

/** 节点是否在运行（模板绑定用）。 */
function isNodeRunning(id?: string | null): boolean {
  return !!id && runningNodeIds.value.has(id)
}
/** 焦点编辑中的图节点（null=关闭沉浸 overlay）。 */
const focusNode = ref<CanvasNode | null>(null)

/** 2x 四轮 S7：彩色标注弹层锚定节点（FocusEditOverlay 同范式）。 */
const annotateNode = ref<CanvasNode | null>(null)
/** 3x-C1 框选多选集（≥2 驱动批量工具条；[] 回单选/空选）。 */
const multiSelectedIds = ref<string[]>([])

/** C13 故事板抽屉显隐。 */
const showStoryboard = ref(false)
/** C13 拼接进行中（按钮 loading + 防重入）。 */
const concating = ref(false)

/** S12 入库弹窗显隐。 */
const showSaveAsset = ref(false)
/** S12 资产选择器显隐。 */
const showPicker = ref(false)
/** S12 当前弹窗目标节点（右键/属性面板按钮触发）。 */
const contextNode = ref<CanvasNode | null>(null)

/** 3x-C2 一键关联弹窗（提案 + 跳过清单）。 */
const showAssociate = ref(false)
const associateProposals = ref<AssociationProposal[]>([])
const associateSkipped = ref<SkippedNode[]>([])

function onNodeSelect(node: CanvasNode | null) {
  selectedNode.value = node
}

/** 3x-C1：多选集同步（≥2 关属性面板显批量工具条；[] 回单选）。 */
function onNodesSelected(ids: string[]) {
  multiSelectedIds.value = ids
  if (ids.length >= 2) selectedNode.value = null
}

/** 3x-C1：批量删除（二次确认列节点名；removeNodes 连带删边并触发落库）。 */
function onBatchDelete() {
  const ids = [...multiSelectedIds.value]
  if (!ids.length) return
  const names = ids
    .map(id => String(boardRef.value?.getNode(id)?.data.label ?? id))
    .join('、')
  dialog.warning({
    title: '批量删除节点',
    content: `将删除 ${ids.length} 个节点及其连线：${names}`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: () => {
      boardRef.value?.removeNodes(ids)
      multiSelectedIds.value = []
    }
  })
}

/**
 * 3x-C2：一键关联入口。快照式读画布生成提案（不改数据）；
 * 无文本类节点/无匹配 → info 提示不弹窗。属性面板直编 node.data（reactive 实时），
 * 故选中节点即便正在编辑，读到的也是最新值。
 */
function onAssociate() {
  const board = boardRef.value
  if (!board || !multiSelectedIds.value.length) return
  const hasTextLike = multiSelectedIds.value.some(id => textLikeFieldOf(board.getNode(id) ?? { type: '' } as CanvasNode))
  if (!hasTextLike) {
    message.info('选中节点里没有文本类节点（文本/脚本/分镜），无法一键关联')
    return
  }
  const { proposals, skipped } = buildProposals({
    selectedIds: [...multiSelectedIds.value],
    getNodes: () => board.getNodes(),
    getEdges: () => board.getEdges()
  })
  if (!proposals.length) {
    const why = skipped[0]?.reason ?? '上游节点名未出现在文本中'
    message.info(`未找到可关联的匹配：${why}`)
    return
  }
  associateProposals.value = proposals
  associateSkipped.value = skipped
  showAssociate.value = true
}

/** 3x-C2：应用勾选提案（逆序替换防位移）→ 写回节点 + 落库。 */
function onAssociateApply(checked: AssociationProposal[]) {
  const board = boardRef.value
  if (!board || !checked.length) return
  const { applied, targets } = applyProposals(checked, {
    getNode: (id) => board.getNode(id),
    updateNodeData: (id, patch) => board.updateNodeData(id, patch)
  })
  scheduleSave()
  message.success(`已关联 ${applied} 处（覆盖 ${targets} 个节点），运行时将自动注入上游产出`)
}

// ---- 3x-C4：批量生成（2x 四轮 S4 升级为依赖调度：上游 SUCCEEDED 释放下游） ----

/** 批量提交进行中（工具条按钮 loading + 与「重跑全链」互斥）。 */
const batchRunning = ref(false)
/** 批量进度（null=无批量）。skipped=依赖调度跳过（上游失败/未入选集）；waiting 由 computed 派生。 */
const batchProgress = ref<{ total: number; submitted: number; done: number; failed: number; skipped: number } | null>(null)
/** 等待上游数 = 总数 − 已提交 − 已终态（done+failed+skipped）− 0（等待中含就绪待派发）。 */
const batchWaiting = computed(() => {
  const p = batchProgress.value
  if (!p) return 0
  return Math.max(0, p.total - p.submitted - p.done - p.failed - p.skipped)
})
/** 批量是否已全部终态（每个节点必落 done/failed/skipped 之一，跳过的永不提交）。 */
const batchFinished = computed(() => {
  const p = batchProgress.value
  return !!p && p.done + p.failed + p.skipped >= p.total
})
/** 2x 四轮 S4 取消令牌：浮条「停止调度」置 true；在途任务不撤仅停释放。 */
const batchCancelRequested = ref(false)

/** 浮条按钮：已完成=纯收起；进行中=停调度（未启动的不再派发，等待上游的一并终止）。 */
function onBatchBarClose() {
  if (!batchFinished.value) batchCancelRequested.value = true
  batchProgress.value = null
}

/**
 * 3x-C4 批量生成入口（S4 依赖调度版）。资格预检（缺提示词/模型、不支持类型列跳过原因）→
 * runDependencyScheduled：就绪=选集内全部上游 SUCCEEDED 才提交下游（互不依赖分支滑窗 2 并行）；
 * 上游 FAILED→下游级联标灰跳过；上游不在选集（含资格剔除）→ SKIPPED_UPSTREAM_MISSING（不静默）；
 * 429 冷却重排不占窗口槽；60min/节点看门狗；收浮条停调度。taskId 即时持久化语义不变。
 */
async function onBatchRun() {
  const board = boardRef.value
  if (!board || batchRunning.value || !editingId.value) return
  if (rerunning.value) {
    message.warning('重跑全链进行中，请等其结束后再批量生成')
    return
  }
  const selected = multiSelectedIds.value
    .map(id => board.getNode(id))
    .filter((n): n is CanvasNode => !!n)
  const eligible: CanvasNode[] = []
  const skippedMsg: string[] = []
  for (const n of selected) {
    const el = batchEligibilityOf(n)
    if (el.ok) eligible.push(n)
    else skippedMsg.push(`「${String((n.data as Record<string, unknown>).label ?? n.id)}」${el.reason}`)
  }
  if (skippedMsg.length) message.warning(`已跳过 ${skippedMsg.length} 个节点：${skippedMsg.join('；')}`)
  if (!eligible.length) {
    message.warning('选中节点均不可批量生成（文本/图片/视频需提示词，图片还需模型）')
    return
  }
  const eligibleIds = eligible.map(n => n.id)
  const { cycle } = inducedTopoOrder(eligibleIds, board.getEdges())
  if (cycle) {
    message.error('选中节点之间存在环路，无法按依赖调度批量生成（请检查连线）')
    return
  }
  batchRunning.value = true
  batchCancelRequested.value = false
  batchProgress.value = { total: eligibleIds.length, submitted: 0, done: 0, failed: 0, skipped: 0 }
  try {
    const out = await runDependencyScheduled(
      eligibleIds,
      board.getEdges(),
      {
        // 提交段（占窗口槽；429 抛给调度器冷却重排）。失败先标红节点保留原始报错再上抛。
        submit: async id => {
          const node = board.getNode(id)
          if (!node) throw new Error('节点已被删除')
          try {
            if (node.type === 'video') {
              await submitVideoOnly(node, String((node.data as Record<string, unknown>).prompt ?? '').trim())
            } else if (node.type === 'image') {
              await submitImageOnly(node)
            } else {
              // text：提交段同步跑完（无 taskId），终态在 awaitTerminal 按节点 status 读
              await onRunNode(node)
            }
          } catch (e) {
            const msg = (e as { msg?: string; message?: string })?.msg
              || (e as { message?: string })?.message
              || '批量提交失败'
            board.updateNodeData(id, { status: 'failed', errorMsg: msg })
            throw e
          }
          if (batchProgress.value) batchProgress.value.submitted++
        },
        // 终态段（不占槽）：video/image 轮询到终态；text 读同步结果。null=任务被替换/取消，按 FAILED 释放下游。
        awaitTerminal: async id => {
          const node = board.getNode(id)
          if (!node) return 'FAILED'
          const data = node.data as Record<string, unknown>
          if (node.type === 'text') return data.status === 'success' ? 'SUCCEEDED' : 'FAILED'
          const taskId = Number(data.taskId ?? 0)
          const st = node.type === 'video'
            ? await pollVideoTask(id, taskId)
            : await pollImageTask(id, taskId)
          return st === 'SUCCEEDED' ? 'SUCCEEDED' : 'FAILED'
        }
      },
      {
        window: BATCH_WINDOW,
        isCancelled: () => batchCancelRequested.value,
        onNodeState: (id, st) => {
          // 节点标灰与浮条计数解耦：收浮条（batchProgress=null）后跳过态仍要落到节点上
          if (st === 'SKIPPED_UPSTREAM_FAILED' || st === 'SKIPPED_UPSTREAM_MISSING') {
            board.updateNodeData(id, {
              status: 'skipped',
              errorMsg: st === 'SKIPPED_UPSTREAM_FAILED' ? '上游失败，已跳过（可重跑上游后再批量）' : '上游未在本次批量选集中，已跳过'
            })
          }
          const p = batchProgress.value
          if (!p) return
          if (st === 'SUCCEEDED') p.done++
          else if (st === 'FAILED') p.failed++
          else if (st === 'SKIPPED_UPSTREAM_FAILED' || st === 'SKIPPED_UPSTREAM_MISSING') p.skipped++
          // CANCELLED/waiting/submitting/running：不计数（waiting 由 batchWaiting 派生）
        }
      }
    )
    const skippedCount = [...out.values()].filter(v => v.startsWith('SKIPPED')).length
    if (skippedCount) message.warning(`${skippedCount} 个节点因上游失败/未执行被跳过（已标灰，见节点提示）`)
  } finally {
    batchRunning.value = false
    batchCancelRequested.value = false
  }
}

/**
 * A1：提示词 @chip 点击 → 聚焦被引用节点（居中 + 选中，属性面板切过去）。
 * 仅 node kind 有画布实体可跳；asset kind MVP 无独立跳转目标（忽略）。
 */
function onMentionFocus(payload: { kind: string; id: string }) {
  if (payload.kind === 'node') {
    boardRef.value?.focusNodeById(payload.id)
  }
}

// ==================== S13 节点 @引用（祖先链候选 + 运行前插值 + 断链检测） ====================

/**
 * 选中节点的祖先集（反向 BFS 沿 edges，visited 防环）。
 * 读 boardRef.getEdges()——其内部 `return edges.value` 在 computed 中被响应式追踪，
 * 故增删边/节点会自动重算（无需手动 tick）。
 */
const selectedAncestors = computed<Set<string>>(() => {
  const id = selectedNode.value?.id
  if (!id || !boardRef.value) return new Set<string>()
  return ancestors(id, boardRef.value.getEdges())
})

/**
 * @选择器候选（设计 §十三：@沿既有连线；2x 四轮 S9 组并集扩展）：
 * 散祖先节点 + 命中组全员分节（组内任一成员 ∈ 祖先 → 组全员可 @；孤立组不进候选，
 * 纯函数 utils/groupCandidates.ts 单测覆盖菱形/孤立组/多组）。
 */
const mentionCandidates = computed<MentionCandidate[]>(() => {
  const nodes = boardRef.value?.getNodes() ?? []
  const groups = boardRef.value?.getGroups() ?? []
  return expandGroupCandidates(
    selectedAncestors.value,
    nodes,
    groups,
    (n) => String((n.data as Record<string, unknown>).label ?? n.id)
  )
})

// ---- 2x 四轮 S9：建组/组改名（结构变更由 Board emit structure-changed → scheduleSave 落库） ----

/** 建组弹窗显隐 + 组名草稿（默认「组N」，N=现存组数+1）。 */
const showGroupModal = ref(false)
const groupDraftName = ref('')
/** 改名目标组（null=弹窗关）+ 草稿名（打开时预填现名）。 */
const renameTargetGroup = ref<CanvasGroup | null>(null)
const groupRenameDraft = ref('')

/** 批量工具条「设为组」→ 预填名开弹窗（≥2 选中才显工具条，此处不再重复校验下限）。 */
function onCreateGroup() {
  if (multiSelectedIds.value.length < 2) return
  groupDraftName.value = `组${(boardRef.value?.getGroups().length ?? 0) + 1}`
  showGroupModal.value = true
}

/** 建组确认：Board.createGroup 过滤死亡成员/查 50 上限/自动移出旧组；失败 reason toast。 */
function confirmCreateGroup() {
  const board = boardRef.value
  if (!board) return
  const count = multiSelectedIds.value.length
  const r = board.createGroup(groupDraftName.value, [...multiSelectedIds.value])
  if (!r.ok) {
    message.error(r.reason ?? '建组失败')
    return
  }
  showGroupModal.value = false
  message.success(`已建组（${count} 个成员），成员节点带组色描边`)
}

/** 包围盒头部点组名 → 预填现名开改名弹窗。 */
function onGroupRenameRequest(g: CanvasGroup) {
  renameTargetGroup.value = g
  groupRenameDraft.value = g.name
}

/** 改名确认（空名/同名 no-op）。 */
function confirmGroupRename() {
  const g = renameTargetGroup.value
  if (!g) return
  boardRef.value?.renameGroup(g.id, groupRenameDraft.value)
  renameTargetGroup.value = null
}

/** F3：祖先图节点选项（首/尾帧选择器用）—— 只列有 fileId 的 image 祖先节点。 */
const imageAncestorOptions = computed<{ label: string; value: string }[]>(() => {
  const set = selectedAncestors.value
  const nodes = boardRef.value?.getNodes() ?? []
  return nodes
    .filter((n) => set.has(n.id) && n.type === 'image' && (n.data as Record<string, unknown>).fileId)
    .map((n) => ({
      label: String((n.data as Record<string, unknown>).label ?? n.id),
      value: n.id
    }))
})

/**
 * 2x 四轮 S8：选中节点的参考媒体预览列表（属性面板「参考」区数据装配）。
 * buildCanvasReferenceList 与提交序号化共用 collectCanvasRefs——预览徽标（首帧/尾帧/图N/视频N）
 * 必与运行时 attachments 一致；互斥场景不抛（提交时才拒，L7 参考区仍渲染）。
 * 仅 image/video 节点有参考语义；prompt 直编 data → 增删 @ 实时反映（L7）。
 */
const referenceList = computed<CanvasReferenceItem[]>(() => {
  const node = selectedNode.value
  if (!node || (node.type !== 'image' && node.type !== 'video')) return []
  const d = node.data as Record<string, unknown>
  return buildCanvasReferenceList(d, String(d.prompt ?? ''), boardRef.value?.getNodes() ?? [])
})

/** 同画布其他节点 label（重命名查重 L9，按节点 id 剔除自身）。 */
const otherLabels = computed<string[]>(() => {
  const id = selectedNode.value?.id
  const nodes = boardRef.value?.getNodes() ?? []
  return nodes
    .filter((n) => n.id !== id)
    .map((n) => String((n.data as Record<string, unknown>).label ?? ''))
})

/**
 * 选中节点文本中的断链占位符（L7/L8）：
 * - node 占位符指向的节点不在祖先集（连线被删 / 上游节点被删）→ 断链
 * - asset 占位符视为非断链（资产不受祖先链约束；MVP 选择器不产生 asset 占位符）
 */
const brokenMentions = computed<string[]>(() => {
  const node = selectedNode.value
  if (!node) return []
  const d = node.data as Record<string, unknown>
  const text = [d.prompt, d.synopsis].filter((s): s is string => typeof s === 'string').join('\n')
  if (!text) return []
  const anc = selectedAncestors.value
  return findBrokenMentions(text, (kind, id) => kind === 'asset' || anc.has(id))
})

/**
 * 运行期 @占位符解析器（onRunNode/onRunVideo 调）：按节点类型注入上游产出文本。
 * - text → outputText
 * - script → scenes 序列化（无 scenes 回落 synopsis）
 * - image/video/audio → prompt + 产物元信息（文件本体走参考图通道，不文本插值）
 * - 找不到节点 → undefined（interpolate 降级「断链」标记）
 * **不递归**：返回串里的 @占位符不再二次解析（防 A@B、B 含 @ 死循环）。
 */
function buildMentionResolver(): MentionResolver {
  const nodes = boardRef.value?.getNodes() ?? []
  const byId = new Map(nodes.map((n) => [n.id, n]))
  return (kind, id) => {
    if (kind === 'asset') return undefined // MVP：运行期不预解析 asset 占位符（选择器仅产 node 占位符）
    const n = byId.get(id)
    if (!n) return undefined
    const d = n.data as Record<string, unknown>
    if (n.type === 'text') {
      // 文本节点内容：优先已运行产出 outputText；未运行回落到 prompt（用户输入的文本），
      // 修复「@文本节点不起效」——原仅取 outputText，没跑过的文本节点 @ 出来是断链。
      if (typeof d.outputText === 'string' && d.outputText) return d.outputText
      return typeof d.prompt === 'string' && d.prompt ? d.prompt : undefined
    }
    if (n.type === 'script') return serializeScenes(d.scenes, d.synopsis)
    if (n.type === 'storyboard') {
      // 分镜节点：@引用解析为画面描述 description（下游图/视频节点生画面用）。
      return typeof d.description === 'string' && d.description ? d.description : undefined
    }
    const meta = [typeof d.prompt === 'string' ? d.prompt : '', d.fileId ? `fileId:${d.fileId}` : '']
      .filter(Boolean).join(' ')
    return meta || undefined
  }
}

/** 脚本节点分镜序列化：scenes 数组逐条编号；无 scenes 回落 synopsis 原文。 */
function serializeScenes(scenes: unknown, synopsis: unknown): string {
  if (Array.isArray(scenes) && scenes.length) {
    return scenes
      .map((s, i) => `[分镜${i + 1}] ${typeof s === 'string' ? s : JSON.stringify(s)}`)
      .join('\n')
  }
  return typeof synopsis === 'string' ? synopsis : ''
}

/**
 * 运行前插值 + 断链预检：把节点 data 里的 @占位符替换为上游产出（不递归），
 * 返回**拷贝**（不污染 node.data —— 占位符原文须持久化）。
 * 有断链则 message.warning 提示（仍允许运行，断链处降级「【断链】」）。
 */
function interpolateForRun(node: CanvasNode): Record<string, unknown> {
  const data = { ...(node.data as Record<string, unknown>) }
  const resolver = buildMentionResolver()
  if (typeof data.prompt === 'string') data.prompt = interpolate(data.prompt, resolver)
  if (typeof data.synopsis === 'string') data.synopsis = interpolate(data.synopsis, resolver)
  // 3x-C2：分镜描述支持 @占位符（一键关联主目标字段之一；对无占位符节点为 no-op）
  if (typeof data.description === 'string') data.description = interpolate(data.description, resolver)
  if (brokenMentions.value.length && selectedNode.value?.id === node.id) {
    message.warning(`存在断链引用：${brokenMentions.value.join(' ')}（断链处将以「【断链】」注入）`)
  }
  return data
}

/** C10 焦点编辑：进入沉浸 overlay。 */
function onFocusEdit(node: CanvasNode) {
  focusNode.value = node
}

/**
 * C10 焦点编辑确认：按归一化框选区裁剪源图 → 产真实图片的新 image 节点（带 fileId+预览）+ 自动连边。
 * rect 为归一化 0-1（FocusEditOverlay 按 stage 尺寸换算，与源图分辨率解耦）；后端按源图实际像素裁剪。
 * 裁剪失败不产空节点（端点抛 → catch 标红源节点，不建图节点；overlay 保留供用户重新框选）。
 */
async function onFocusConfirm(payload: { rect: CropRect; description: string }) {
  const src = focusNode.value
  if (!src || !boardRef.value || !editingId.value) return
  runningNodeIds.value.add(src.id)
  boardRef.value.updateNodeData(src.id, { status: 'running', errorMsg: '' })
  try {
    const res = await canvasApi.cropImage(editingId.value, src.id, payload.rect)
    const f = res.data.data
    const previewUrl = await fetchCanvasPreview(f.fileId)
    const offsetX = (src.position?.x ?? 0) + 260
    const offsetY = src.position?.y ?? 0
    boardRef.value.addNode({
      type: 'image',
      position: { x: offsetX, y: offsetY },
      data: {
        label: '衍生图',
        fileId: f.fileId,
        previewUrl,
        parentFileId: (src.data as Record<string, unknown>).fileId as string | undefined,
        cropRect: payload.rect,
        prompt: payload.description,
        sourceNodeId: src.id,
        status: 'success'
      }
    })
    const nodes = boardRef.value.getNodes()
    const created = nodes[nodes.length - 1]
    if (created) boardRef.value.addEdge(src.id, created.id)
    boardRef.value.updateNodeData(src.id, { status: 'success', errorMsg: '' })
    focusNode.value = null
    message.success('已裁剪生成新图节点')
    scheduleSave()
  } catch (e: unknown) {
    const msg = (e as { msg?: string })?.msg || '裁剪失败'
    boardRef.value.updateNodeData(src.id, { status: 'failed', errorMsg: msg })
    message.error(msg)
    // 裁剪失败：保留 overlay 供用户重新框选或取消
  } finally {
    runningNodeIds.value.delete(src.id)
  }
}

/**
 * 2x 四轮 S6：确定性图片翻转/旋转（同 crop 链路范式）。
 * 后端 transform-image（EXIF 先归正 + op 白名单）→ 新 fileId（源图不可变）→
 * 建衍生图节点（label 按 op 中文命名 + parentFileId/sourceNodeId 溯源）+ 自动连边回源图节点。
 * 失败不产空节点（端点抛 → catch 标红源节点）。
 */
const TRANSFORM_LABELS: Record<ImageTransformOp, string> = {
  FLIP_H: '水平翻转',
  FLIP_V: '垂直翻转',
  ROTATE_90: '旋转90°',
  ROTATE_180: '旋转180°',
  ROTATE_270: '旋转270°'
}

async function onTransformImage(payload: { node: CanvasNode; op: ImageTransformOp }) {
  const src = payload.node
  if (!src || !boardRef.value || !editingId.value) return
  if (runningNodeIds.value.has(src.id)) return
  runningNodeIds.value.add(src.id)
  boardRef.value.updateNodeData(src.id, { status: 'running', errorMsg: '' })
  try {
    const res = await canvasApi.transformImage(editingId.value, src.id, payload.op)
    const f = res.data.data
    const previewUrl = await fetchCanvasPreview(f.fileId)
    boardRef.value.addNode({
      type: 'image',
      position: { x: (src.position?.x ?? 0) + 260, y: src.position?.y ?? 0 },
      data: {
        label: (src.data.label ? `${String(src.data.label)}·` : '') + TRANSFORM_LABELS[payload.op],
        fileId: f.fileId,
        previewUrl,
        parentFileId: (src.data as Record<string, unknown>).fileId as string | undefined,
        transformOp: f.op,
        sourceNodeId: src.id,
        status: 'success'
      }
    })
    const nodes = boardRef.value.getNodes()
    const created = nodes[nodes.length - 1]
    if (created) boardRef.value.addEdge(src.id, created.id)
    boardRef.value.updateNodeData(src.id, { status: 'success', errorMsg: '' })
    message.success(`已${TRANSFORM_LABELS[payload.op]}，生成新图节点`)
    scheduleSave()
  } catch (e: unknown) {
    const msg = (e as { msg?: string })?.msg || '图片变换失败'
    boardRef.value.updateNodeData(src.id, { status: 'failed', errorMsg: msg })
    message.error(msg)
  } finally {
    runningNodeIds.value.delete(src.id)
  }
}

// ---- 2x 四轮 S7：彩色框选标注（生成标注图 / AI 修改双出口，spec §6.2） ----

/** S7：进入彩色标注弹层。 */
function onAnnotate(node: CanvasNode) {
  annotateNode.value = node
}

/** 节点显示名（衍生节点 label 前缀用；空名回落「图」）。 */
function nodeLabelOf(n: CanvasNode): string {
  return (n.data.label as string | undefined)?.trim() || '图'
}

/**
 * S7 公共步：服务端 ANNOTATE 合成（EXIF 归正 + 框 + 序号徽标）→ 建「源名·标注」图节点 + 连边。
 * 返回新节点（供 AI 出口继续串下游）；失败上抛由调用方标红源节点。
 */
async function annotateSubmit(src: CanvasNode, payload: AnnotateConfirmPayload): Promise<CanvasNode | null> {
  const res = await canvasApi.annotateImage(editingId.value!, src.id, payload.boxes)
  const f = res.data.data
  const previewUrl = await fetchCanvasPreview(f.fileId)
  boardRef.value!.addNode({
    type: 'image',
    position: { x: (src.position?.x ?? 0) + 260, y: (src.position?.y ?? 0) + 120 },
    data: {
      label: `${nodeLabelOf(src)}·标注`,
      fileId: f.fileId,
      previewUrl,
      parentFileId: (src.data as Record<string, unknown>).fileId as string | undefined,
      sourceNodeId: src.id,
      annotateCount: payload.boxes.length,
      status: 'success'
    }
  })
  const nodes = boardRef.value!.getNodes()
  const created = nodes[nodes.length - 1]
  if (created) boardRef.value!.addEdge(src.id, created.id)
  return created ?? null
}

/** S7 出口①：仅合成标注图 → 新图节点（无 AI）。 */
async function onAnnotateConfirm(payload: AnnotateConfirmPayload) {
  const src = annotateNode.value
  if (!src || !boardRef.value || !editingId.value) return
  runningNodeIds.value.add(src.id)
  boardRef.value.updateNodeData(src.id, { status: 'running', errorMsg: '' })
  try {
    await annotateSubmit(src, payload)
    boardRef.value.updateNodeData(src.id, { status: 'success', errorMsg: '' })
    annotateNode.value = null
    message.success('已生成标注图节点')
    scheduleSave()
  } catch (e: unknown) {
    const msg = (e as { msg?: string })?.msg || '标注图生成失败'
    boardRef.value.updateNodeData(src.id, { status: 'failed', errorMsg: msg })
    message.error(msg)
  } finally {
    runningNodeIds.value.delete(src.id)
  }
}

/**
 * S7 出口②：AI 修改。先合成标注图节点，再建下游 AI 图节点——
 * prompt = @原图 @标注图 + 逐框指令清单（@图节点经 resolveCanvasVideoAttachments 序号化为图1/图2 参考图）；
 * 复制源节点生图参数（model/size/...）；有 model 即自动提交（走既有 onRunImage 链路），否则提示选模型后手动运行。
 */
async function onAnnotateAi(payload: AnnotateConfirmPayload) {
  const src = annotateNode.value
  if (!src || !boardRef.value || !editingId.value) return
  runningNodeIds.value.add(src.id)
  boardRef.value.updateNodeData(src.id, { status: 'running', errorMsg: '' })
  try {
    const annotated = await annotateSubmit(src, payload)
    if (!annotated) throw new Error('标注节点创建失败')
    const instructionList = payload.instructions
      .map(i => `第${i.index}（${ANNOTATE_COLOR_NAMES[i.color]}）框：${i.text || '按图示区域优化'}`)
      .join('；')
    // 复制源节点已配的生图参数（AI 节点继承尺寸/格式等 capability 字段）
    const GEN_PARAM_KEYS = ['model', 'size', 'customSize', 'outputFormat', 'optimizeMode',
      'guidanceScale', 'sequential', 'maxImages', 'watermark', 'webSearch']
    const genParams: Record<string, unknown> = {}
    for (const k of GEN_PARAM_KEYS) {
      const v = (src.data as Record<string, unknown>)[k]
      if (v !== undefined) genParams[k] = v
    }
    boardRef.value.addNode({
      type: 'image',
      position: { x: (annotated.position?.x ?? 0) + 260, y: annotated.position?.y ?? 0 },
      data: {
        label: `${nodeLabelOf(src)}·AI修改`,
        prompt: `@{{node:${src.id}}} @{{node:${annotated.id}}}\n参考图1为原图，参考图2为标注图。按标注图序号逐框修改：${instructionList}`,
        ...genParams,
        sourceNodeId: annotated.id,
        aiFromAnnotate: true
      }
    })
    const nodes = boardRef.value.getNodes()
    const aiNode = nodes[nodes.length - 1]
    if (aiNode) boardRef.value.addEdge(annotated.id, aiNode.id)
    boardRef.value.updateNodeData(src.id, { status: 'success', errorMsg: '' })
    annotateNode.value = null
    scheduleSave()
    if (aiNode && genParams.model) {
      message.info('已建 AI 修改节点并提交生成…')
      void onRunImage(aiNode)
    } else {
      message.warning('已建 AI 修改节点，选择图片模型后点「AI 生图」运行')
    }
  } catch (e: unknown) {
    const msg = (e as { msg?: string })?.msg || 'AI 修改链路失败'
    boardRef.value.updateNodeData(src.id, { status: 'failed', errorMsg: msg })
    message.error(msg)
  } finally {
    runningNodeIds.value.delete(src.id)
  }
}

/** 运行节点（C4 文本/图片 / C5 视频）：按类型分发。视频走 media API（media:gen gated）。 */
async function onRunNode(node: CanvasNode) {
  if (!editingId.value || !node) return
  if (runningNodeIds.value.has(node.id)) return
  if (node.type === 'video') {
    await onRunVideo(node)
    return
  }
  if (node.type === 'image') {
    await onRunImage(node)
    return
  }
  // text/image 走画布 runner（无状态）
  runningNodeIds.value.add(node.id)
  boardRef.value?.updateNodeData(node.id, { status: 'running', errorMsg: '' })
  try {
    // S13：运行前把 @占位符插值为上游产出（不递归，不污染 node.data 原文）
    const payload: CanvasNodeDTO = {
      id: node.id,
      type: node.type as CanvasNodeDTO['type'],
      data: interpolateForRun(node)
    }
    const res = await canvasApi.runNode(editingId.value, payload)
    const result = res.data.data
    if (result?.dataPatch) {
      boardRef.value?.updateNodeData(node.id, result.dataPatch)
    }
    message.success(result?.status === 'success' ? '生成完成' : '已处理')
    scheduleSave()
  } catch (e: unknown) {
    const msg = (e as { msg?: string })?.msg || '生成失败'
    boardRef.value?.updateNodeData(node.id, { status: 'failed', errorMsg: msg })
    message.error(msg)
  } finally {
    runningNodeIds.value.delete(node.id)
  }
}

/**
 * 脚本节点「拆分镜」专用处理器（替代脚本走通用 onRunNode）：
 * 后端按段数/规范拆出 scenes[] → 前端为每条 scene 扇出生成一个分镜节点（脚本→各分镜独立连线）。
 * 重拆=替换：先删该脚本上次自动生成的分镜子节点（data.sourceScriptId+autoGenerated 标记），再重建。
 */
async function onSplitStoryboard(node: CanvasNode) {
  if (!editingId.value || !boardRef.value) return
  if (runningNodeIds.value.has(node.id)) return
  runningNodeIds.value.add(node.id)
  boardRef.value.updateNodeData(node.id, { status: 'running', errorMsg: '' })
  try {
    const payload: CanvasNodeDTO = {
      id: node.id,
      type: 'script',
      data: interpolateForRun(node)
    }
    const res = await canvasApi.runNode(editingId.value, payload)
    const result = res.data.data
    const scenes = (result?.dataPatch?.scenes ?? []) as Array<{ index?: number; description?: string }>
    // 合并 dataPatch 进脚本节点（保 scenes 记录 + 已拆 N 分镜计数）
    if (result?.dataPatch) boardRef.value.updateNodeData(node.id, result.dataPatch)

    // 替换旧分镜：删该脚本上次自动生成的分镜子节点（连同其连线，removeNodes 内部已清相关 edge）
    const board = boardRef.value
    const oldIds = board.getNodes()
      .filter(n => (n.data as Record<string, unknown>).sourceScriptId === node.id
        && (n.data as Record<string, unknown>).autoGenerated === true)
      .map(n => n.id)
    if (oldIds.length) board.removeNodes(oldIds)

    // 扇出生成新分镜节点：脚本右侧竖排，每个连脚本（独立分支）
    const sx = node.position?.x ?? 0
    const sy = node.position?.y ?? 0
    scenes.forEach((scene, i) => {
      const id = board.addNode({
        type: 'storyboard',
        position: { x: sx + 320, y: sy + i * 175 },
        data: {
          label: `分镜${i + 1}`,
          index: scene.index ?? i + 1,
          description: scene.description ?? '',
          sourceScriptId: node.id,
          autoGenerated: true,
          status: 'success'
        }
      })
      board.addEdge(node.id, id)
    })
    message.success(`已拆 ${scenes.length} 个分镜`)
    scheduleSave()
  } catch (e: unknown) {
    const msg = (e as { msg?: string })?.msg || '拆分镜失败'
    boardRef.value?.updateNodeData(node.id, { status: 'failed', errorMsg: msg })
    message.error(msg)
  } finally {
    runningNodeIds.value.delete(node.id)
  }
}

/**
 * 运行视频节点（C5）：复用既有 media API。
 * 提交（文生/图生二选一，按 node.data.refFileId）→ 轮询至终态 → 成功 fetch blob 转 objectURL 预览。
 * 权限：media:gen gated，无权则 submit 403 → 节点 FAILED（plan 安全清单「各自权限」）。
 */
async function onRunVideo(node: CanvasNode) {
  if (!editingId.value) return
  const rawPrompt = String((node.data as Record<string, unknown>).prompt ?? '').trim()
  if (!rawPrompt) {
    message.warning('请先填写视频提示词')
    return
  }
  if (brokenMentions.value.length && selectedNode.value?.id === node.id) {
    message.warning(`存在断链引用：${brokenMentions.value.join(' ')}（断链处将以「【断链】」注入）`)
  }
  runningNodeIds.value.add(node.id)
  boardRef.value?.updateNodeData(node.id, { status: 'running', errorMsg: '' })
  try {
    const taskId = await submitVideoOnly(node, rawPrompt)
    message.info('视频已提交，生成中…')
    await pollVideoTask(node.id, taskId)
  } catch (e: unknown) {
    const msg = (e as { msg?: string; message?: string })?.msg
      || (e as { message?: string })?.message
      || '视频提交失败'
    boardRef.value?.updateNodeData(node.id, { status: 'failed', errorMsg: msg })
    message.error(msg)
  } finally {
    runningNodeIds.value.delete(node.id)
  }
}

/**
 * 3x-C4：仅提交视频任务并持久化 taskId（不轮询不弹消息）。onRunVideo 与批量生成共用。
 * F3：首/尾帧 + @参考图 统一走 attachments[]（不再自动取上游图作首帧）。
 * image 节点 @ → reference_image 附件 + 序号化为「图N」；非 image 节点 @ → 文本插值。
 * 抛错由调用方处理（批量侧走 429 退避）。
 */
async function submitVideoOnly(node: CanvasNode, rawPrompt: string): Promise<number> {
  const data = node.data as Record<string, unknown>
  const attachments = buildVideoAttachments(node, rawPrompt)
  const submit = await mediaApi.submitVideo({
    prompt: attachments.rewrittenPrompt,
    ratio: (data.ratio as MediaRatioArg) || '16:9',
    duration: Number(data.duration ?? 5),
    resolution: (data.resolution as MediaResArg) || '720p',
    watermark: Boolean(data.watermark),
    generateAudio: Boolean(data.generateAudio),
    taskType: attachments.refs.length > 0 ? 'IMAGE2VIDEO' : 'TEXT2VIDEO',
    attachments: attachments.refs.length > 0 ? attachments.refs : undefined,
    model: (data.model as string) || undefined
  })
  const taskId = submit.data.data.id
  boardRef.value?.updateNodeData(node.id, { taskId, status: 'running' })
  // 2x-2：taskId 立即持久化（不再走 800ms 防抖）——提交后立刻离开画布时
  // 快照里必须有 taskId，重进才能恢复任务状态续轮询。
  void onSave(true)
  return taskId
}

/** 轮询视频任务至终态；成功 fetch blob 预览，失败标红。返回终态（null=被替换/取消），批量进度条据此计数。 */
async function pollVideoTask(nodeId: string, taskId: number): Promise<string | null> {
  const detail = await pollMediaTask(
    async () => (await mediaApi.getTask(taskId)).data.data,
    () => !isCurrentNodeTask(nodeId, taskId),
    { onPending: () => boardRef.value?.updateNodeData(nodeId, { status: 'running' }) }
  )
  if (!detail) return null
  if (detail.status === 'SUCCEEDED') {
      const url = detail.videoUrl
      const objectUrl = url ? await fetchVideoBlob(url) : ''
      boardRef.value?.updateNodeData(nodeId, {
        status: 'success',
        mediaStatus: 'SUCCEEDED',
        previewUrl: objectUrl,
        // C11：存结果 fileId（stored_files），抽帧 loadPath 直读做 javacv seek
        fileId: detail.resultFileId ?? undefined,
        errorMsg: '',
        // 7x-4：保留审计字段，供属性面板查看推送参数 + 参考视频标志
        submittedRequest: detail.submittedRequest ?? undefined,
        providerRequestSnapshot: detail.providerRequestSnapshot ?? undefined,
        hasReference: detail.hasReference ?? undefined
      })
      backfillNodeLabel(nodeId, detail.prompt)
      message.success('视频生成完成')
  } else {
      boardRef.value?.updateNodeData(nodeId, {
        status: 'failed',
        mediaStatus: detail.status,
        errorMsg: '视频生成失败',
        // 7x-4：失败也保留审计字段（排查失败原因时需看推送参数）
        submittedRequest: detail.submittedRequest ?? undefined,
        providerRequestSnapshot: detail.providerRequestSnapshot ?? undefined,
        hasReference: detail.hasReference ?? undefined
      })
      message.error('视频生成失败')
  }
  scheduleSave()
  return detail.status
}

function isCurrentNodeTask(nodeId: string, taskId: number): boolean {
  const node = boardRef.value?.getNode(nodeId)
  return editingId.value != null && !!node && Number((node.data as Record<string, unknown>).taskId) === taskId
}

/**
 * 2x-6：任务完成后回填节点名。label 仍是默认类型名（「视频」「图片 2」等自动命名）
 * 时用提示词前 12 字命名；用户手动改过名字（不匹配默认模式）则不覆盖。
 */
function backfillNodeLabel(nodeId: string, prompt?: string | null) {
  const node = boardRef.value?.getNode(nodeId)
  if (!node) return
  const label = String((node.data as Record<string, unknown>).label ?? '')
  if (!/^(视频|图片|音频|文本)(\s\d+)?$/.test(label)) return
  const base = (prompt ?? '').trim().replace(/\s+/g, ' ').slice(0, 12)
  if (base && base !== label) {
    boardRef.value?.updateNodeData(nodeId, { label: base })
  }
}

type MediaRatioArg = Parameters<typeof mediaApi.submitVideo>[0]['ratio']
type MediaResArg = Parameters<typeof mediaApi.submitVideo>[0]['resolution']

/**
 * F3 视频帧重构：把视频节点的首/尾帧 + 提示词里 @ 的图节点统一收集成 attachments[]。
 * 纯逻辑委托 resolveCanvasVideoAttachments（可单测）；本函数补 boardRef 节点源 + 文本插值器。
 *
 * 三类来源（顺序即 attachments 顺序，Ark 按序认参考图）：
 * 1. data.firstFrameNodeId / data.lastFrameNodeId → 显式首/尾帧（image + frameRole）。
 * 2. prompt 内 `@{{node:id}}` 指向的 **image** 节点 → reference_image 附件，并按出现顺序
 *    序号化为「图N」写回 prompt（首/尾帧节点不参与图N序号，去重，排除已是帧的节点）。
 * 3. 其余 `@{{node:id}}` / `@{{asset:id}}` → 走 buildMentionResolver 文本插值（非图节点内容）。
 *
 * 不再自动取上游连线图作首帧（旧画布已存 refFileId 仍走后端 legacy 通道）。
 */
function buildVideoAttachments(
  node: CanvasNode,
  rawPrompt: string
): { refs: AttachmentRef[]; rewrittenPrompt: string } {
  const data = node.data as Record<string, unknown>
  const allNodes = boardRef.value?.getNodes() ?? []
  return resolveCanvasVideoAttachments(data, rawPrompt, allNodes, buildMentionResolver())
}

/**
 * 运行图片节点（#7：画布图片节点接入 AI 生图，复用 media 图片管线）。
 * 提交（文生/图生二选一，@ 的图节点作参考图）→ 轮询至终态 → 成功 fetch 首张 blob 转 objectURL 预览。
 * 权限：media:gen gated，无权则 submit 403 → 节点 FAILED。首张 imageFileIds[0] 存为节点 fileId（焦点编辑裁剪源）。
 */
async function onRunImage(node: CanvasNode) {
  if (!editingId.value) return
  const data = node.data as Record<string, unknown>
  const rawPrompt = String(data.prompt ?? '').trim()
  if (!rawPrompt) {
    message.warning('请先填写图片提示词')
    return
  }
  // 生图 model 必填（后端图片任务无默认 provider 回退，须指定模型反查 IMAGE provider）
  const model = (data.model as string) || ''
  if (!model) {
    message.warning('请先选择图片模型')
    return
  }
  if (brokenMentions.value.length && selectedNode.value?.id === node.id) {
    message.warning(`存在断链引用：${brokenMentions.value.join(' ')}（断链处将以「【断链】」注入）`)
  }
  runningNodeIds.value.add(node.id)
  boardRef.value?.updateNodeData(node.id, { status: 'running', errorMsg: '' })
  try {
    const taskId = await submitImageOnly(node)
    message.info('图片已提交，生成中…')
    await pollImageTask(node.id, taskId)
  } catch (e: unknown) {
    const msg = (e as { msg?: string })?.msg || '图片提交失败'
    boardRef.value?.updateNodeData(node.id, { status: 'failed', errorMsg: msg })
    message.error(msg)
  } finally {
    runningNodeIds.value.delete(node.id)
  }
}

/**
 * 3x-C4：仅提交图片任务并持久化 taskId（不轮询不弹消息）。onRunImage 与批量生成共用。
 * @ 的图节点 → 参考图 refFileIds（复用 resolveCanvasVideoAttachments 的序号化逻辑）；其余 @ → 文本插值。
 * 抛错由调用方处理（批量侧走 429 退避）。
 */
async function submitImageOnly(node: CanvasNode): Promise<number> {
  const data = node.data as Record<string, unknown>
  const rawPrompt = String(data.prompt ?? '').trim()
  const model = (data.model as string) || ''
  const refs = buildImageRefs(node, rawPrompt)
  const submit = await mediaApi.submitImage(buildImageRequest(node, model, refs.rewrittenPrompt, refs.fileIds))
  const taskId = submit.data.data.id
  boardRef.value?.updateNodeData(node.id, { taskId, status: 'running' })
  // 2x-2：同视频节点——taskId 即时持久化，防离开丢任务关联。
  void onSave(true)
  return taskId
}

/**
 * 收集图片节点的参考图：提示词里 @ 的图节点 → refFileIds（fileId 列表），并序号化「图N」写回 prompt。
 * 复用 resolveCanvasVideoAttachments（图片节点无首/尾帧字段，全部 @ 图节点落为普通参考图）。
 */
function buildImageRefs(node: CanvasNode, rawPrompt: string): { fileIds: string[]; rewrittenPrompt: string } {
  const data = node.data as Record<string, unknown>
  const allNodes = boardRef.value?.getNodes() ?? []
  const { refs, rewrittenPrompt } = resolveCanvasVideoAttachments(data, rawPrompt, allNodes, buildMentionResolver())
  return { fileIds: refs.map(r => r.fileId), rewrittenPrompt }
}

// ---- 2x-3：图片模型目录（capability 判定，提交时只带该模型支持的字段） ----
const imageModels = ref<ImageModelVO[]>([])

/** 按模型 id 取 capability（模型目录未加载/未知模型 → null，只提交基础字段）。 */
function imageCapOf(modelId: string): ImageModelVO['capability'] | null {
  return imageModels.value.find(m => m.modelId === modelId)?.capability ?? null
}

/**
 * 2x-3：组装图片提交请求——节点 data 里的尺寸/格式/优化模式/引导尺度/组图/水印/联网
 * 按模型 capability 过滤后带上（后端对「不支持字段传值」直接拒绝，不能盲传）。
 */
function buildImageRequest(
  node: CanvasNode, model: string, rewrittenPrompt: string, refFileIds: string[]
): ImageSubmitRequest {
  const data = node.data as Record<string, unknown>
  const req: ImageSubmitRequest = { model, prompt: rewrittenPrompt }
  if (refFileIds.length > 0) req.refFileIds = refFileIds
  const cap = imageCapOf(model)
  if (cap) {
    if (data.size === '__custom__') {
      const custom = typeof data.customSize === 'string' ? data.customSize.trim() : ''
      if (custom) req.size = custom
    } else if (typeof data.size === 'string' && data.size) {
      req.size = data.size
    }
    if (typeof data.outputFormat === 'string' && data.outputFormat) req.outputFormat = data.outputFormat
    if (typeof data.optimizeMode === 'string' && data.optimizeMode) req.optimizeMode = data.optimizeMode
    req.watermark = typeof data.watermark === 'boolean' ? data.watermark : cap.watermarkDefault
    if (cap.supportsGuidanceScale && typeof data.guidanceScale === 'number') {
      req.guidanceScale = data.guidanceScale
    }
    if (cap.supportsSequential && data.sequential === 'auto') {
      req.sequential = 'auto'
      req.maxImages = typeof data.maxImages === 'number' ? data.maxImages : 4
    }
    if (cap.supportsWebSearch && data.webSearch === true) req.webSearch = true
  }
  return req
}

/** 轮询图片任务至终态；成功 fetch 首张 blob 预览 + 存首张 fileId（焦点编辑裁剪源），失败标红。返回终态（null=被替换/取消）。 */
async function pollImageTask(nodeId: string, taskId: number): Promise<string | null> {
  const detail = await pollMediaTask(
    async () => (await mediaApi.getTask(taskId)).data.data,
    () => !isCurrentNodeTask(nodeId, taskId),
    { onPending: () => boardRef.value?.updateNodeData(nodeId, { status: 'running' }) }
  )
  if (!detail) return null
  if (detail.status === 'SUCCEEDED') {
      const urls = detail.imageUrls ?? []
      const fileIds = detail.imageFileIds ?? []
      // 首张下载端点带 auth → fetchMediaBlob 拉 blob 转 objectURL（<img src> 无法带 header）
      const objectUrl = urls[0] ? await fetchMediaBlob(urls[0]) : ''
      boardRef.value?.updateNodeData(nodeId, {
        status: 'success',
        mediaStatus: 'SUCCEEDED',
        previewUrl: objectUrl,
        // 首张 stored_files.file_id：焦点编辑裁剪据此 loadPath 取源图（同视频 resultFileId 范式）
        fileId: fileIds[0] ?? undefined,
        errorMsg: ''
      })
      backfillNodeLabel(nodeId, detail.prompt)
      message.success('图片生成完成')
  } else {
      boardRef.value?.updateNodeData(nodeId, {
        status: 'failed',
        mediaStatus: detail.status,
        errorMsg: '图片生成失败'
      })
      message.error('图片生成失败')
  }
  scheduleSave()
  return detail.status
}

/** C9 一键重跑：拓扑排序（Kahn）+ 环检测 → 按序串行跑可生成节点。3x-C4：批量进行中互斥禁入。 */
async function onRerunAll() {
  if (!boardRef.value || rerunning.value) return
  if (batchRunning.value) {
    message.warning('批量生成进行中，请等本轮结束后再重跑全链')
    return
  }
  const nodes = boardRef.value.getNodes()
  const edges = boardRef.value.getEdges()
  if (!nodes.length) {
    message.warning('画布为空')
    return
  }
  const { order, cycle } = topoSort(nodes, edges)
  if (cycle) {
    message.error('画布存在环路，无法拓扑重跑（请检查连线）')
    return
  }
  rerunning.value = true
  try {
    let ran = 0
    for (const id of order) {
      const node = boardRef.value.getNode(id)
      if (!node) continue
      if (isRunnable(node.type)) {
        await onRunNode(node)
        ran++
      }
    }
    message.success(`重跑完成（${ran} 个节点）`)
  } finally {
    rerunning.value = false
  }
}

/** 可生成节点类型（image 走 AI 生图 / 上传二选一；audio 为上传型，跳过）。 */
function isRunnable(type: string): boolean {
  return type === 'text' || type === 'script' || type === 'video' || type === 'image'
}

/**
 * 拓扑排序 + 环检测（Kahn 算法）。
 * 入度表 + 邻接表 → 入度为 0 的入队 → 逐出队减邻居入度。
 * order.length < 节点数 ⇒ 存在环（plan R-6 环检测报错）。
 */
function topoSort(
  nodes: CanvasNode[],
  edges: { source: string; target: string }[]
): { order: string[]; cycle: boolean } {
  const inDegree = new Map<string, number>()
  const adj = new Map<string, string[]>()
  for (const n of nodes) {
    inDegree.set(n.id, 0)
    adj.set(n.id, [])
  }
  for (const e of edges) {
    if (!inDegree.has(e.source) || !inDegree.has(e.target)) continue
    adj.get(e.source)!.push(e.target)
    inDegree.set(e.target, (inDegree.get(e.target) ?? 0) + 1)
  }
  const queue: string[] = []
  for (const [id, deg] of inDegree) if (deg === 0) queue.push(id)
  const order: string[] = []
  while (queue.length) {
    const cur = queue.shift()!
    order.push(cur)
    for (const nb of adj.get(cur) ?? []) {
      const nd = (inDegree.get(nb) ?? 1) - 1
      inDegree.set(nb, nd)
      if (nd === 0) queue.push(nb)
    }
  }
  return { order, cycle: order.length < nodes.length }
}

/** 上传产出物（C4 图片 / C6 音频 / 视频参考图）：落 SOURCE_CANVAS，写回 fileId + 会话级预览。 */
async function onUploadFile(payload: { node: CanvasNode; file: File }) {
  if (!editingId.value || !payload.node) return
  const { node, file } = payload
  runningNodeIds.value.add(node.id)
  boardRef.value?.updateNodeData(node.id, { status: 'running' })
  try {
    const res = await canvasApi.upload(editingId.value, file)
    const f = res.data.data
    // /api/files/{id} 需 auth header，<img>/<audio> src 无法带 → axios 拉 blob 转 objectURL（会话级）
    const previewUrl = await fetchCanvasPreview(f.fileId)
    boardRef.value?.updateNodeData(node.id, {
      status: 'success',
      fileId: f.fileId,
      previewUrl,
      mime: f.mimeType,
      errorMsg: ''
    })
    message.success('上传成功')
    scheduleSave()
  } catch (e: unknown) {
    const msg = (e as { msg?: string })?.msg || '上传失败'
    boardRef.value?.updateNodeData(node.id, { status: 'failed', errorMsg: msg })
    message.error(msg)
  } finally {
    runningNodeIds.value.clear()
  }
}

/**
 * C11 视频抽帧：调后端抽首/尾/指定秒 → 返新图片 fileId → 产图节点（带 fileId+预览）+ 自动连回视频节点。
 * 失败不产空节点（后端抛 → catch 标红源视频节点，不建图节点，plan 边界）。
 */
async function onExtractFrame(payload: { node: CanvasNode; mode: FrameMode; second?: number }) {
  if (!editingId.value || !payload.node) return
  const src = payload.node
  const srcFileId = (src.data as Record<string, unknown>).fileId as string | undefined
  if (!srcFileId) {
    message.warning('视频节点无源文件，请先生成或等待视频完成')
    return
  }
  runningNodeIds.value.add(src.id)
  try {
    const res = await canvasApi.extractFrame(editingId.value, src.id, {
      mode: payload.mode,
      second: payload.second
    })
    const f = res.data.data
    const previewUrl = await fetchCanvasPreview(f.fileId)
    const offsetX = (src.position?.x ?? 0) + 260
    const offsetY = (src.position?.y ?? 0)
    const labelTail = payload.mode === 'AT' ? ` ${payload.second}s` : ''
    boardRef.value?.addNode({
      type: 'image',
      position: { x: offsetX, y: offsetY },
      data: {
        label: `抽帧(${payload.mode}${labelTail})`,
        fileId: f.fileId,
        previewUrl,
        parentFileId: srcFileId,
        sourceNodeId: src.id,
        status: 'success'
      }
    })
    const nodes = boardRef.value!.getNodes()
    const created = nodes[nodes.length - 1]
    if (created) boardRef.value!.addEdge(src.id, created.id)
    message.success('抽帧完成，已产新图节点')
    scheduleSave()
  } catch (e: unknown) {
    const msg = (e as { msg?: string })?.msg || '抽帧失败'
    boardRef.value?.updateNodeData(src.id, { errorMsg: msg })
    message.error(msg)
  } finally {
    runningNodeIds.value.clear()
  }
}

/**
 * C12 视频截取：调后端截 [startSec,endSec) → 返新视频 fileId → 产视频节点（带 fileId+预览）+ 自动连回源视频节点。
 * 失败不产空节点（后端抛 → catch 标红源视频节点，不建视频节点，plan 边界）。
 * 预览：clip 产物落 stored_files(SOURCE_CANVAS)，/api/files/{id} 需 auth header → fetchVideoBlob 拉 blob 转 objectURL。
 */
async function onClipVideo(payload: { node: CanvasNode; startSec: number; endSec: number }) {
  if (!editingId.value || !payload.node) return
  const src = payload.node
  const srcFileId = (src.data as Record<string, unknown>).fileId as string | undefined
  if (!srcFileId) {
    message.warning('视频节点无源文件，请先生成或等待视频完成')
    return
  }
  runningNodeIds.value.add(src.id)
  try {
    const res = await canvasApi.clipVideo(editingId.value, src.id, {
      startSec: payload.startSec,
      endSec: payload.endSec
    })
    const f = res.data.data
    const previewUrl = await fetchVideoBlob(f.url)
    const offsetX = (src.position?.x ?? 0) + 260
    const offsetY = (src.position?.y ?? 0) + 140
    boardRef.value?.addNode({
      type: 'video',
      position: { x: offsetX, y: offsetY },
      data: {
        label: `截取(${payload.startSec}-${payload.endSec}s)`,
        fileId: f.fileId,
        previewUrl,
        mediaStatus: 'SUCCEEDED',
        status: 'success',
        parentFileId: srcFileId,
        sourceNodeId: src.id,
        prompt: ''
      }
    })
    const nodes = boardRef.value!.getNodes()
    const created = nodes[nodes.length - 1]
    if (created) boardRef.value!.addEdge(src.id, created.id)
    message.success('截取完成，已产新视频节点')
    scheduleSave()
  } catch (e: unknown) {
    const msg = (e as { msg?: string })?.msg || '截取失败'
    boardRef.value?.updateNodeData(src.id, { errorMsg: msg })
    message.error(msg)
  } finally {
    runningNodeIds.value.clear()
  }
}

/**
 * C13 故事板段：抽屉打开时（依赖 showStoryboard 触发重算）从画布视频节点投影。
 * 仅取 type=video 且已产出 fileId 的节点（生成成功 / 截取 / 上传均可）。
 */
const storyboardSegments = computed<StoryboardSegment[]>(() => {
  if (!showStoryboard.value) return []
  const nodes = boardRef.value?.getNodes() ?? []
  const segs: StoryboardSegment[] = []
  for (const n of nodes) {
    if (n.type !== 'video') continue
    const d = n.data as Record<string, unknown>
    const fileId = d.fileId as string | undefined
    if (!fileId) continue
    segs.push({
      nodeId: n.id,
      fileId,
      label: (d.label as string) ?? '视频段',
      durationSec: typeof d.duration === 'number' ? d.duration : undefined,
      previewUrl: d.previewUrl as string | undefined
    })
  }
  return segs
})

/**
 * C13 拼接成片：按故事板顺序把多段视频首尾相接 → 新成片视频节点。
 * 后端去重保序 + 逐段 loadPath 归属校验 → javacv concat（H.264/mp4）。
 */
async function onStoryboardConcat(fileIds: string[]) {
  if (!editingId.value) return
  concating.value = true
  try {
    const res = await canvasApi.concatStoryboard(editingId.value, fileIds)
    const f = res.data.data
    const previewUrl = await fetchVideoBlob(f.url)
    boardRef.value?.addNode({
      type: 'video',
      position: { x: 120 + Math.random() * 120, y: 360 },
      data: {
        label: `成片(${f.segmentCount}段/${f.totalDurationSec}s)`,
        fileId: f.fileId,
        previewUrl,
        mediaStatus: 'SUCCEEDED',
        status: 'success',
        duration: f.totalDurationSec || undefined,
        prompt: ''
      }
    })
    message.success(`拼接完成（${f.segmentCount} 段，约 ${f.totalDurationSec}s）`)
    scheduleSave()
  } catch (e: unknown) {
    const msg = (e as { msg?: string })?.msg || '拼接失败'
    message.error(msg)
  } finally {
    concating.value = false
  }
}

// ==================== S12 画布↔资产库打通（L5 入库 / L6 库→画布引用 + 徽标） ====================

/** 节点右键 → 开「存入资产库」弹窗（L5）。 */
function onNodeContextMenu(node: CanvasNode) {
  contextNode.value = node
  showSaveAsset.value = true
}

/** 属性面板「存入资产库」按钮 → 同上（另一入口）。 */
function onSaveToAsset(node: CanvasNode) {
  contextNode.value = node
  showSaveAsset.value = true
}

/** 属性面板「从库选择」按钮 → 开 AssetPicker（L6）。 */
function onPickFromAsset(node: CanvasNode) {
  contextNode.value = node
  showPicker.value = true
}

/** 入库成功回写徽标（assetId/Name/Version，L5 PRODUCED 绑定由后端落表）。 */
function onAssetImported(payload: { node: CanvasNode; assetId: number; name: string; version: number }) {
  boardRef.value?.updateNodeData(payload.node.id, {
    assetId: payload.assetId,
    assetName: payload.name,
    assetVersion: payload.version,
    assetHasUpdate: false
  })
  scheduleSave()
}

/**
 * 把资产版本快照（resolve 结果）写回节点 data（L6 库→画布引用 / 更新到最新版共用）。
 * - PROMPT：content.body → outputText
 * - SCRIPT：content.synopsis/scenes → synopsis/scenes
 * - IMAGE/AUDIO：fileId + fetchCanvasPreview → previewUrl
 * - VIDEO：fileId + resolve.url fetchVideoBlob → previewUrl
 * 徽标三字段同步刷新；assetHasUpdate 置 false（已对齐该版）。
 */
async function applyAssetResolve(node: CanvasNode, resolve: ResolveVO) {
  const patch: Record<string, unknown> = {
    assetId: resolve.assetId,
    assetName: resolve.name ?? '资产',
    assetVersion: resolve.version,
    assetHasUpdate: false
  }
  if (resolve.mediaType === MEDIA_TYPE.PROMPT || resolve.mediaType === MEDIA_TYPE.SCRIPT) {
    const parsed = parseAssetContent(resolve.content)
    if (resolve.mediaType === MEDIA_TYPE.PROMPT) {
      patch.outputText = typeof parsed.body === 'string' ? parsed.body : (resolve.content ?? '')
    } else {
      if (typeof parsed.synopsis === 'string') patch.synopsis = parsed.synopsis
      if (Array.isArray(parsed.scenes)) patch.scenes = parsed.scenes
    }
  } else if (resolve.mediaType === MEDIA_TYPE.STORYBOARD) {
    // 分镜资产正文 JSON = {description, index?}；写回 node.data.description（+index）
    const parsed = parseAssetContent(resolve.content)
    patch.description = typeof parsed.description === 'string' ? parsed.description : (resolve.content ?? '')
    if (typeof parsed.index === 'number') patch.index = parsed.index
  } else if (resolve.fileId) {
    patch.fileId = resolve.fileId
    // 文件类需带鉴权 fetch 转 objectURL 预览（/api/files/{id} 需 auth header）。
    // 2x-5 修复：后端 ResolveVO.mediaType 是受控词汇中文（'视频'/'图片'/'音频'），
    // 此前误用英文 'VIDEO'/'IMAGE' 比较 → 分支永不命中，拉取后节点无预览（像没拉到）。
    if (resolve.mediaType === MEDIA_TYPE.VIDEO && resolve.url) {
      try { patch.previewUrl = await fetchVideoBlob(resolve.url) } catch { /* 预览失败不阻断 */ }
    } else if (resolve.mediaType === MEDIA_TYPE.IMAGE || resolve.mediaType === MEDIA_TYPE.AUDIO) {
      try { patch.previewUrl = await fetchCanvasPreview(resolve.fileId) } catch { /* 同上 */ }
    }
  }
  boardRef.value?.updateNodeData(node.id, patch)
  scheduleSave()
}

/** 解析资产正文 JSON（PROMPT/SCRIPT；容错：非 JSON 返空对象）。 */
function parseAssetContent(raw: string | undefined): Record<string, unknown> {
  if (!raw) return {}
  try { return JSON.parse(raw) as Record<string, unknown> } catch { return {} }
}

/** AssetPicker 选定 → resolve 写回节点（L6 库→画布引用）。 */
async function onAssetPicked(payload: { node: CanvasNode; resolve: ResolveVO }) {
  await applyAssetResolve(payload.node, payload.resolve)
  message.success(`已引用资产 ${payload.resolve.name ?? ''} v${payload.resolve.version}`)
}

/** 检查资产是否有新版：asset.get 比对 currentVersion > 节点绑定版（L6 不自动变，仅提示）。 */
async function onCheckUpdate(node: CanvasNode) {
  const assetId = (node.data as Record<string, unknown>).assetId as number | undefined
  if (assetId == null) return
  try {
    const res = await assetApi.get(assetId)
    const cur = res.data.data.currentVersion
    const bound = (node.data as Record<string, unknown>).assetVersion as number | undefined
    if (bound != null && cur > bound) {
      boardRef.value?.updateNodeData(node.id, { assetHasUpdate: true })
      message.info(`资产已升至 v${cur}（当前引用 v${bound}），可「更新到最新版」`)
    } else {
      boardRef.value?.updateNodeData(node.id, { assetHasUpdate: false })
      message.info('已是最新版本')
    }
  } catch (e: unknown) {
    message.error((e as { msg?: string })?.msg || '检查更新失败')
  }
}

/** 更新节点引用到资产最新版：re-resolve（不带 version=当前）→ 写回（L6 手动更新）。 */
async function onUpdateAsset(node: CanvasNode) {
  const assetId = (node.data as Record<string, unknown>).assetId as number | undefined
  if (assetId == null) return
  try {
    const res = await assetBridgeApi.resolve(assetId)
    await applyAssetResolve(node, res.data.data)
    message.success('已更新到最新版')
  } catch (e: unknown) {
    message.error((e as { msg?: string })?.msg || '更新失败')
  }
}

/** 节点产出后自动保存快照（防丢结果）；保存节流复用 saving 标志。 */
let saveTimer: ReturnType<typeof setTimeout> | null = null
function scheduleSave() {
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(() => { onSave() }, 800)
}

/** 节点调色板（C3 起接入各自属性面板与产出触发；MVP 先通用节点占位）。 */
const palette = [
  { type: 'text', label: '文本', icon: DocumentTextOutline },
  { type: 'image', label: '图片', icon: ImageOutline },
  { type: 'video', label: '视频', icon: VideocamOutline },
  { type: 'audio', label: '音频', icon: MusicalNotesOutline },
  { type: 'script', label: '脚本', icon: CodeSlashOutline },
  { type: 'storyboard', label: '分镜', icon: FilmOutline }
]

async function loadList() {
  loadingList.value = true
  try {
    const res = await canvasApi.list()
    canvases.value = res.data.data
  } catch {
    message.error('画布列表加载失败')
  } finally {
    loadingList.value = false
  }
}

async function onCreate() {
  creating.value = true
  try {
    const res = await canvasApi.create()
    canvases.value.unshift(res.data.data)
    openEditor(res.data.data.id)
  } catch {
    message.error('新建画布失败')
  } finally {
    creating.value = false
  }
}

function openEditor(id: number) {
  router.push(`/canvas/${id}`)
}

async function loadCanvas(id: number) {
  try {
    const res = await canvasApi.get(id)
    const c = res.data.data
    editingId.value = c.id
    currentName.value = c.name
    const snap = parseSnapshot(c.snapshot)
    // B2：旧画布（C6 前）节点 label 可能空 → 头部显「未命名」。加载时按类型补默认名（uniqueLabel 去重），
    // 下次保存即持久化修复存量数据。
    const usedLabels = snap.nodes.map((n) => String(n.data.label ?? '')).filter(Boolean)
    for (const n of snap.nodes) {
      if (!String(n.data.label ?? '').trim()) {
        const base = palette.find((p) => p.type === n.type)?.label ?? '节点'
        const filled = uniqueLabel(base, usedLabels)
        n.data.label = filled
        usedLabels.push(filled)
      }
    }
    // 等编辑器+CanvasBoard 挂载：editingId 触发 v-else 渲染，boardRef 下一 tick 才赋值，
    // 否则此处 boardRef.value===null，可选链 ?. 静默吞掉 loadSnapshot → 重进画布空白（历史 bug）。
    await nextTick()
    boardRef.value?.loadSnapshot(snap)
    // 重取会话级预览：快照只存 fileId（previewUrl blob 上次保存已剥），image/audio 节点按 fileId 拉 blob
    hydratePreviews(snap.nodes)
    // 视频节点：按 taskId 重取视频预览（若有终态任务）
    hydrateVideoPreviews(snap.nodes)
    // 2x-2：生成中离开后重进——对 status=running 且带 taskId 的节点续轮询至终态并回填结果
    resumePendingTasks(snap.nodes)
  } catch {
    message.error('画布加载失败')
    backToList()
  }
}

/** image/audio 节点按 fileId 重取 objectURL 预览（失败静默，不阻断加载）。 */
function hydratePreviews(nodes: CanvasNode[]) {
  for (const n of nodes) {
    const fileId = (n.data as Record<string, unknown>).fileId as string | undefined
    if (fileId && !(n.data as Record<string, unknown>).previewUrl && (n.type === 'image' || n.type === 'audio')) {
      fetchCanvasPreview(fileId)
        .then(url => boardRef.value?.updateNodeData(n.id, { previewUrl: url }))
        .catch(() => { /* 预览取失败不阻断 */ })
    }
  }
}

/** 视频节点按已终态 taskId 重取视频预览。 */
function hydrateVideoPreviews(nodes: CanvasNode[]) {
  for (const n of nodes) {
    if (n.type !== 'video') continue
    const taskId = (n.data as Record<string, unknown>).taskId as number | undefined
    const mediaStatus = (n.data as Record<string, unknown>).mediaStatus as string | undefined
    if (taskId && mediaStatus === 'SUCCEEDED') {
      mediaApi.getTask(taskId)
        .then(async r => {
          const url = r.data.data.videoUrl
          if (url) {
            const obj = await fetchVideoBlob(url)
            boardRef.value?.updateNodeData(n.id, {
              previewUrl: obj,
              status: 'success',
              fileId: r.data.data.resultFileId ?? undefined,
              // 7x-4：hydrate 时也补审计字段（旧快照节点缺失时回填）
              submittedRequest: r.data.data.submittedRequest ?? undefined,
              providerRequestSnapshot: r.data.data.providerRequestSnapshot ?? undefined,
              hasReference: r.data.data.hasReference ?? undefined
            })
          }
        })
        .catch(() => { /* 静默 */ })
    }
  }
}

/**
 * 2x-2：恢复未终态任务。生成中离开画布后，快照里节点是 taskId + status='running'
 * （mediaStatus 只在轮询到终态时才写）。重进画布时按节点类型重新续轮询：
 * 任务早已完成 → 首次轮询即回填 success/预览/审计字段；仍在跑 → 继续轮到终态。
 */
function resumePendingTasks(nodes: CanvasNode[]) {
  for (const n of nodes) {
    const data = n.data as Record<string, unknown>
    const taskId = data.taskId as number | undefined
    if (!taskId || data.status !== 'running') continue
    if (n.type === 'video') {
      void pollVideoTask(n.id, taskId)
    } else if (n.type === 'image') {
      void pollImageTask(n.id, taskId)
    }
  }
}

function parseSnapshot(raw: string | null): CanvasSnapshot {
  if (!raw) return { nodes: [], edges: [], groups: [] }
  try {
    const obj = JSON.parse(raw)
    return {
      nodes: obj.nodes ?? [],
      edges: obj.edges ?? [],
      // 2x 四轮 S9：老快照无 groups 字段 = 空数组语义（Board.loadSnapshot 同口径兜底）
      groups: Array.isArray(obj.groups) ? obj.groups : [],
      viewport: obj.viewport
    }
  } catch {
    return { nodes: [], edges: [], groups: [] }
  }
}

async function onSave(silent = false) {
  if (!editingId.value || !boardRef.value) return
  saving.value = true
  try {
    const snap = boardRef.value.getSnapshot()
    // 剥离会话级字段：视频 previewUrl 是 blob: objectURL（带鉴权 fetch 产物），跨会话失效，不入快照。
    // taskId/status 入快照（加载时可按 taskId 重新 fetch 预览）。
    const cleanNodes = snap.nodes.map(n => {
      const dataCopy = { ...n.data } as Record<string, unknown>
      delete dataCopy.previewUrl
      return { ...n, data: dataCopy }
    })
    await canvasApi.save(editingId.value, {
      name: currentName.value || '未命名画布',
      snapshot: JSON.stringify({ ...snap, nodes: cleanNodes })
    })
    if (!silent) message.success('已保存')
    await loadList()
  } catch {
    if (!silent) message.error('保存失败')
  } finally {
    saving.value = false
  }
}

async function onRename() {
  if (!editingId.value) return
  const name = currentName.value.trim()
  if (!name) return
  try {
    await canvasApi.rename(editingId.value, name)
  } catch {
    message.error('重命名失败')
  }
}

async function onDelete(c: CanvasVO) {
  if (!confirm(`确认删除画布「${c.name}」？产出物文件会保留，不级联清理。`)) return
  try {
    await canvasApi.remove(c.id)
    canvases.value = canvases.value.filter(x => x.id !== c.id)
    message.success('已删除')
  } catch {
    message.error('删除失败')
  }
}

function backToList() {
  editingId.value = null
  currentName.value = ''
  router.push('/canvas')
}

function onPaletteDragStart(event: DragEvent, p: { type: string; label: string }) {
  if (!event.dataTransfer) return
  event.dataTransfer.setData('application/vueflow', JSON.stringify({ type: p.type, label: p.label }))
  event.dataTransfer.effectAllowed = 'move'
}

function onPaletteClick(p: { type: string; label: string }) {
  boardRef.value?.addNode({ type: p.type, data: { label: p.label } })
}

// ---------- C6 双击画布空白处 → 快速加节点搜索框（ComfyUI 式） ----------
/** 搜索框是否展开 + 记住双击坐标（选中类型后在该坐标加节点）。 */
const quickAddOpen = ref(false)
const quickAddPos = ref<{ x: number; y: number } | null>(null)
const quickAddQuery = ref('')
const quickAddIdx = ref(0)
/** 2x-6：拉线建节点——quick-add 弹窗的拉线起点（双击空白打开时为 null）。 */
const quickAddSourceNode = ref<string | null>(null)
/** 按 query 过滤调色板（label 中文 / type 英文任一命中）。 */
const quickAddFiltered = computed(() => {
  const q = quickAddQuery.value.trim().toLowerCase()
  if (!q) return palette
  return palette.filter(p => p.label.toLowerCase().includes(q) || p.type.toLowerCase().includes(q))
})
/** CanvasBoard 双击空白处 / 2x-6 拉线到空白处 → 记坐标(+拉线起点)、清查询、开弹窗。 */
function onQuickAdd(position: { x: number; y: number }, sourceNodeId?: string) {
  quickAddPos.value = position
  quickAddSourceNode.value = sourceNodeId ?? null
  quickAddQuery.value = ''
  quickAddIdx.value = 0
  quickAddOpen.value = true
}
/** 弹窗关闭后清状态（防下次打开残留旧查询/坐标/拉线起点）。 */
function resetQuickAdd() {
  quickAddQuery.value = ''
  quickAddIdx.value = 0
  quickAddPos.value = null
  quickAddSourceNode.value = null
}
/** 选定类型 → 在坐标处加节点并关弹窗；若由拉线触发则自动连边（2x-6）。 */
function confirmQuickAdd(p: { type: string; label: string }) {
  const newId = boardRef.value?.addNode({
    type: p.type,
    position: quickAddPos.value ?? undefined,
    data: { label: p.label }
  })
  if (quickAddSourceNode.value && newId) {
    boardRef.value?.addEdge(quickAddSourceNode.value, newId)
    scheduleSave()
  }
  quickAddOpen.value = false
}
/** 搜索框键盘：↑↓ 移高亮 / Enter 选中 / Esc 关闭。 */
function onQuickAddKey(e: KeyboardEvent) {
  if (e.key === 'ArrowDown') {
    quickAddIdx.value = Math.min(quickAddIdx.value + 1, quickAddFiltered.value.length - 1)
    e.preventDefault()
  } else if (e.key === 'ArrowUp') {
    quickAddIdx.value = Math.max(quickAddIdx.value - 1, 0)
    e.preventDefault()
  } else if (e.key === 'Enter') {
    const pick = quickAddFiltered.value[quickAddIdx.value] ?? quickAddFiltered.value[0]
    if (pick) {
      confirmQuickAdd(pick)
      e.preventDefault()
    }
  } else if (e.key === 'Escape') {
    quickAddOpen.value = false
    e.preventDefault()
  }
}

function formatTime(t: string | null): string {
  if (!t) return ''
  return t.slice(0, 16).replace('T', ' ')
}

// 路由 param 驱动 列表/编辑 切换
watch(
  () => route.params.id,
  (id) => {
    if (id) loadCanvas(Number(id))
    else { editingId.value = null }
  }
)

onMounted(() => {
  loadList()
  if (route.params.id) loadCanvas(Number(route.params.id))
  // 2x-3：图片模型目录（capability 判定用；失败静默，提交时退化为仅基础字段）
  mediaApi.listImageModels().then(r => { imageModels.value = r.data.data ?? [] }).catch(() => { /* 静默 */ })
  // 2x-2：关标签/刷新前把防抖窗口内的未落盘编辑 flush 掉（尽力而为，请求已发出不等待）
  window.addEventListener('beforeunload', flushPendingSave)
})

onUnmounted(() => {
  window.removeEventListener('beforeunload', flushPendingSave)
})

/** 2x-2：路由离开画布前 flush 防抖保存——800ms 窗口内的编辑不再因导航丢失。 */
onBeforeRouteLeave(() => {
  flushPendingSave()
  return true
})

/** 防抖窗口内未落盘的保存立即执行（taskId 关键字段已即时保存，此处兜底其余编辑）。 */
function flushPendingSave() {
  if (saveTimer) {
    clearTimeout(saveTimer)
    saveTimer = null
    void onSave(true)
  }
}
</script>

<style lang="scss" scoped>
.canvas-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: var(--spacing-4);
  gap: var(--spacing-3);
}

.canvas-view__forbid {
  max-width: 480px;
  margin: auto;
}

// 列表模式
.canvas-view__list {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-3);
}

.canvas-view__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.canvas-view__title {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  margin: 0;
}

.canvas-view__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: var(--spacing-3);
}

.canvas-card {
  display: flex;
  align-items: center;
  gap: var(--spacing-3);
  padding: var(--spacing-3) var(--spacing-4);
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-in-out);
  position: relative;

  &:hover {
    border-color: var(--color-primary);
    transform: translateY(-2px);
    box-shadow: var(--shadow-md);
  }

  &__icon {
    color: var(--color-primary);
    flex-shrink: 0;
  }

  &__body {
    flex: 1;
    min-width: 0;
  }

  &__name {
    font-size: var(--font-size-base);
    font-weight: var(--font-weight-medium);
    color: var(--color-text-primary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  &__meta {
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
    margin-top: 2px;
  }

  &__del {
    opacity: 0;
    transition: opacity var(--duration-fast);
  }

  &:hover &__del {
    opacity: 1;
  }
}

// 编辑模式
.canvas-view__editor {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-2);
}

.canvas-view__editor-header {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
}

.canvas-view__name-input {
  max-width: 320px;
}

.canvas-view__main {
  flex: 1;
  display: flex;
  gap: var(--spacing-2);
  min-height: 0;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  overflow: hidden;
  position: relative; /* 3x-C1 批量工具条定位锚 */
}

/* 3x-C1 框选批量工具条：浮于画布顶部居中 */
.canvas-batchbar {
  position: absolute;
  top: var(--spacing-3);
  left: 50%;
  transform: translateX(-50%);
  z-index: 20;
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  padding: var(--spacing-1) var(--spacing-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-full, 999px);
  background: var(--color-surface);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.35);

  &__count {
    font-size: var(--font-size-xs);
    color: var(--color-text-secondary);
    white-space: nowrap;
  }

  /* 3x-C4 批量生成进度浮条：错开工具条下方，role=status 供读屏器播报 */
  &--progress {
    top: calc(var(--spacing-3) + 44px);
  }
}

/* 2x 四轮 S9：建组/改名弹窗 footer 与提示行 */
.canvas-view__group-footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--spacing-2);
}

.canvas-view__group-hint {
  margin-top: var(--spacing-2);
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  line-height: 1.5;
}

.canvas-palette {
  width: 120px;
  flex-shrink: 0;
  padding: var(--spacing-2);
  background: var(--color-surface);
  border-right: 1px solid var(--color-border-light);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-1);

  &__title {
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
    padding: var(--spacing-1) var(--spacing-2);
    text-transform: uppercase;
  }

  &__item {
    display: flex;
    align-items: center;
    gap: var(--spacing-2);
    padding: var(--spacing-2);
    border-radius: var(--radius-base);
    color: var(--color-text-secondary);
    cursor: grab;
    font-size: var(--font-size-sm);
    transition: all var(--duration-instant) var(--ease-in-out);

    &:hover {
      background: var(--color-primary-light);
      color: var(--color-primary);
    }
  }
}

// C6 快速加节点搜索框（双击画布空白处弹出）
.canvas-quickadd__list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-1);
  margin-top: var(--spacing-2);
  max-height: 260px;
  overflow-y: auto;
}

.canvas-quickadd__item {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  padding: var(--spacing-1) var(--spacing-2);
  border: 1px solid transparent;
  border-radius: var(--radius-base);
  background: transparent;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  text-align: left;
  cursor: pointer;

  &.is-active,
  &:hover {
    background: var(--color-primary-light);
    border-color: rgba(var(--color-primary-rgb), 0.4);
    color: var(--color-primary);
  }
}

.canvas-quickadd__empty {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  padding: var(--spacing-2);
  text-align: center;
}
</style>
